package hydraulic.url

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class RunContext(
    val os: String,
    val arch: String,
    val ver: String?,
    val args: List<String>,
    val packageDir: Path
)

internal data class LaunchPlan(val executable: Path, val arguments: List<String>)

private data class UrlRequest(val name: String?, val url: String)

private const val MAX_PARALLEL_RUN_RESOLUTIONS = 8

/**
 * Evaluates a run.js package. The `url`, `urls` and `compose` functions return promises; [resolve] and [compose] run on
 * worker threads, concurrently with each other and with JavaScript, and must be thread-safe.
 */
internal fun evaluateRunJavaScript(
    packageFile: Path,
    context: RunContext,
    compose: (List<ComposeOperation>) -> Path = { throw UnsupportedOperationException("compose() is unavailable") },
    abandonmentGrace: Duration = Duration.ofSeconds(10),
    resolve: (String) -> Path
): LaunchPlan {
    val packagePath = packageFile.toRealPath()
    val packageDir = packagePath.parent ?: error("run.js must have a parent directory")
    val packageFileSystem = packageFileSystem(packageDir)
    return Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(IOAccess.newBuilder().fileSystem(packageFileSystem).build())
        .allowNativeAccess(false)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .option("engine.WarnInterpreterOnly", "false")
        .option("js.unhandled-rejections", "throw")
        .out(System.err)
        .err(System.err)
        .build().use { js ->
            HostOperations(js, resolve, compose, abandonmentGrace).use { host ->
                js.getBindings("js").putMember("context", js.eval("js", contextJavaScript(context)))
                val exports = host.evaluate(packagePath)
                val exportedPlan = exports.getMember("default")
                require(exportedPlan != null) { "run.js must export its launch plan as the default export" }
                launchPlan(exportedPlan, packageDir)
            }
        }
}

/**
 * Runs the host side of the promise-based run.js functions. A context may only be used by one thread, so JavaScript only
 * runs on the evaluating thread: workers post completions to a queue that [evaluate] drains, settling the promises.
 */
private class HostOperations(
    js: Context,
    private val resolve: (String) -> Path,
    private val compose: (List<ComposeOperation>) -> Path,
    private val abandonmentGrace: Duration
) : AutoCloseable {
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("run.js host operation").factory())
    private val resolutions = Semaphore(MAX_PARALLEL_RUN_RESOLUTIONS)
    private val completions = LinkedBlockingQueue<() -> Unit>()

    private val bindings = js.getBindings("js")
    private val promise = bindings.getMember("Promise")
    private val error = bindings.getMember("Error")
    private val import = js.eval("js", "specifier => import(specifier)")
    private val describe = js.eval("js", "value => { try { return JSON.stringify(value) ?? String(value) } catch { return String(value) } }")

    /** Operations started and not yet settled. Only accessed on the evaluating thread. */
    private var outstanding = 0

    /** Tasks submitted to workers that have not yet finished. Each posts its completion before it finishes. */
    private val working = AtomicInteger()

    init {
        bindings.putMember("url", ProxyExecutable { arguments ->
            operation { complete ->
                val url = singleArgument("url", arguments)
                require(url.isString) { "run.js function url() expects a URL string" }
                val location = url.asString()
                work { complete(runCatching { resolveURL(location) }) }
            }
        })
        bindings.putMember("urls", ProxyExecutable { arguments ->
            operation { complete -> startUrls(urlsRequest(singleArgument("urls", arguments)), complete) }
        })
        bindings.putMember("compose", ProxyExecutable { arguments ->
            operation { complete ->
                // The recipe is copied now, so later changes to it have no effect. Copy sources may be promises, so the
                // copy is checked once they have all settled.
                val recipe = runCatching { composeRecipe(singleArgument("compose", arguments)) }
                val sources = recipe.getOrNull().orEmpty().map { it?.get("from") }
                promise.invokeMember("all", ProxyArray.fromList(sources)).invokeMember(
                    "then",
                    ProxyExecutable { resolved ->
                        try {
                            val operations = composeOperations(recipe.getOrThrow(), resolved[0])
                            work { complete(runCatching { compose(operations).toAbsolutePath().normalize().toString() }) }
                        } catch (e: Throwable) {
                            complete(Result.failure(e))
                        }
                        null
                    },
                    ProxyExecutable { reasons -> complete(Result.failure(GuestRejection(reasons[0]))); null }
                )
            }
        })
    }

    /** Evaluates [module] and returns its namespace once it and every host operation it started have settled. */
    fun evaluate(module: Path): Value {
        var settled = false
        var exports: Value? = null
        var rejection: Value? = null
        guest {
            import.execute(module.toUri().toString()).invokeMember(
                "then",
                ProxyExecutable { arguments -> settled = true; exports = arguments[0]; null },
                ProxyExecutable { arguments -> settled = true; rejection = arguments[0]; null }
            )
        }
        while (!settled || (rejection == null && outstanding > 0)) {
            // Only completions from workers can make progress, so with none running or queued, evaluation is stuck. An
            // outstanding operation may itself be waiting, as compose() does for its sources.
            if (working.get() == 0 && completions.isEmpty())
                throw IllegalStateException("run.js did not finish evaluating because it waits for a promise that never settles")
            guest(completions.take())
        }
        rejection?.let { reason ->
            original(reason)?.let { throw it }
            if (reason.isException)
                guest { reason.throwException() }
            // Values other than errors carry no stack trace, so describe the value itself.
            throw IllegalStateException("run.js failed by throwing a value that is not an Error: ${describe.execute(reason).asString()}")
        }
        return exports!!
    }

    /**
     * Returns a promise for an operation started by [begin], which calls its argument once from any thread with the
     * result; later calls are ignored. Failures, including those thrown by [begin], reject the promise with an Error whose
     * cause is the original exception, so it can be reported if run.js does not handle it. JavaScript failures, such as a
     * throwing getter, reject it with the value that was thrown.
     */
    private fun operation(begin: (complete: (Result<Any>) -> Unit) -> Unit): Value {
        lateinit var resolve: Value
        lateinit var reject: Value
        val result = promise.newInstance(ProxyExecutable { functions ->
            resolve = functions[0]
            reject = functions[1]
            null
        })
        outstanding++
        val completed = AtomicBoolean(false)
        val complete = { outcome: Result<Any> ->
            if (completed.compareAndSet(false, true)) completions.put {
                outstanding--
                outcome.fold(
                    onSuccess = { resolve.execute(it) },
                    onFailure = { failure ->
                        val guestValue = when {
                            failure is GuestRejection -> failure.reason
                            failure is PolyglotException && failure.isGuestException -> failure.guestObject
                            else -> null
                        }
                        if (guestValue != null) {
                            reject.execute(guestValue)
                        } else {
                            val message = failure.message ?: failure.toString()
                            reject.execute(error.newInstance(message, ProxyObject.fromMap(mapOf("cause" to failure))))
                        }
                    }
                )
            }
        }
        try {
            begin(complete)
        } catch (e: Throwable) {
            complete(Result.failure(e))
        }
        return result
    }

    private fun startUrls(request: UrlsRequest, complete: (Result<Any>) -> Unit) {
        val requests = request.requests
        if (requests.isEmpty()) {
            complete(Result.success(request.result(emptyList())))
            return
        }
        // Each URL resolves independently; the promise rejects as soon as one fails, and later outcomes are ignored.
        val paths = arrayOfNulls<String>(requests.size)
        val remaining = AtomicInteger(requests.size)
        requests.forEachIndexed { index, entry ->
            work {
                try {
                    paths[index] = resolveURL(entry.url)
                    if (remaining.decrementAndGet() == 0)
                        complete(Result.success(request.result(paths.map { it!! })))
                } catch (e: Throwable) {
                    complete(Result.failure(e))
                }
            }
        }
    }

    /** Runs [task] on a worker. The task must post a completion before it finishes. */
    private fun work(task: () -> Unit) {
        working.incrementAndGet()
        try {
            executor.execute {
                try {
                    task()
                } finally {
                    working.decrementAndGet()
                }
            }
        } catch (e: Throwable) {
            working.decrementAndGet()
            throw e
        }
    }

    private fun resolveURL(url: String): String {
        resolutions.acquire()
        try {
            return resolve(url).toAbsolutePath().normalize().toString()
        } finally {
            resolutions.release()
        }
    }

    /** Runs JavaScript, translating host operation failures that it did not handle back into their original exceptions. */
    private fun <T> guest(action: () -> T): T {
        try {
            return action()
        } catch (exception: PolyglotException) {
            if (exception.isHostException)
                throw exception.asHostException()
            throw exception.guestObject?.let(::original) ?: exception
        }
    }

    /** The exception that failed a host operation, if [reason] is the Error that rejected its promise. */
    private fun original(reason: Value): Throwable? {
        if (!reason.hasMembers())
            return null
        val cause = reason.getMember("cause") ?: return null
        return if (cause.isHostObject) cause.asHostObject<Any>() as? Throwable else null
    }

    override fun close() {
        // Abandoned operations are interrupted, and should finish before their caller releases the cache entries they
        // use. An operation that ignores interruption is abandoned after a grace period rather than hanging the run.
        executor.shutdownNow()
        if (!executor.awaitTermination(abandonmentGrace.toMillis(), TimeUnit.MILLISECONDS))
            System.err.println("Warning: run.js host operations did not stop within ${abandonmentGrace.toMillis()} ms of being abandoned")
    }
}

/** Fails an operation with a JavaScript value, such as the rejection of a compose() source, rather than a host exception. */
private class GuestRejection(val reason: Value) : RuntimeException(null, null, false, false)

private fun singleArgument(function: String, arguments: Array<out Value>): Value {
    require(arguments.size == 1) { "run.js function $function() expects one argument" }
    return arguments[0]
}

/** A parsed urls() argument. [names] lists an object's properties and whether each holds an array, or is null for an array. */
private class UrlsRequest(val requests: List<UrlRequest>, val names: List<Pair<String, Boolean>>?)

private fun urlsRequest(request: Value): UrlsRequest {
    require(request.hasArrayElements() || request.hasMembers()) {
        "run.js function urls() expects an object or array"
    }
    if (request.hasArrayElements()) {
        return UrlsRequest((0 until request.arraySize.toInt()).map { index ->
            val value = request.getArrayElement(index.toLong())
            require(value.isString) { "run.js URL at index $index must be a string" }
            UrlRequest(null, value.asString())
        }, null)
    }
    val names = ArrayList<Pair<String, Boolean>>()
    val requests = request.memberKeys.flatMap { name ->
        val value = request.getMember(name)
        when {
            value.isString -> {
                names += name to false
                listOf(UrlRequest(name, value.asString()))
            }
            value.hasArrayElements() -> {
                names += name to true
                (0 until value.arraySize.toInt()).map { index ->
                    val item = value.getArrayElement(index.toLong())
                    require(item.isString) { "run.js URL '$name[$index]' must be a string" }
                    UrlRequest(name, item.asString())
                }
            }
            else -> throw IllegalArgumentException(
                "run.js URL '$name' must be a string or an array of strings"
            )
        }
    }
    return UrlsRequest(requests, names)
}

private fun UrlsRequest.result(paths: List<String>): Any {
    if (names == null)
        return ProxyArray.fromArray(*paths.toTypedArray())
    val byName = requests.zip(paths).groupBy({ it.first.name!! }, { it.second })
    return ProxyObject.fromMap(names.associate { (name, grouped) ->
        val values = byName[name].orEmpty()
        name to if (grouped) ProxyArray.fromArray(*values.toTypedArray()) else values.single()
    })
}

/** Copies a compose() recipe: each operation's properties, or null for an operation that is not an object. */
private fun composeRecipe(request: Value): List<Map<String, Value>?>? {
    if (!request.hasArrayElements())
        return null
    return (0 until request.arraySize).map { index ->
        val operation = request.getArrayElement(index)
        if (operation.hasMembers() && !operation.hasArrayElements() && !operation.canExecute())
            operation.memberKeys.associateWith { operation.getMember(it) }
        else
            null
    }
}

/** Checks the shape of a copied compose() recipe. Path rules and source checks are applied by [Composer]. */
private fun composeOperations(recipe: List<Map<String, Value>?>?, sources: Value): List<ComposeOperation> {
    require(!recipe.isNullOrEmpty()) {
        "run.js function compose() expects a non-empty array of operations"
    }
    return recipe.mapIndexed { index, operation ->
        val label = "compose() operation $index"
        require(operation != null) { "$label must be an object" }
        val keys = operation.keys
        fun string(name: String): String? {
            if (name !in keys)
                return null
            // Sources were read and awaited before the recipe was checked.
            val value = if (name == "from") sources.getArrayElement(index.toLong()) else operation.getValue(name)
            require(value.isString && value.asString().isNotEmpty()) { "$label property '$name' must be a non-empty string" }
            return value.asString()
        }
        if ("remove" in keys) {
            require(keys == setOf("remove")) { "$label must have only a 'remove' property, or be a copy operation" }
            ComposeOperation.Remove(string("remove")!!)
        } else {
            val unknown = keys - COPY_PROPERTIES
            require(unknown.isEmpty()) { "$label has unknown properties: ${unknown.sorted().joinToString()}" }
            val replace = if ("replace" in keys) {
                val value = operation.getValue("replace")
                require(value.isBoolean) { "$label property 'replace' must be a boolean" }
                value.asBoolean()
            } else {
                false
            }
            ComposeOperation.Copy(
                from = requireNotNull(string("from")) { "$label requires a 'from' property" },
                select = string("select") ?: ".",
                to = requireNotNull(string("to")) { "$label requires a 'to' property" },
                replace = replace
            )
        }
    }
}

private val COPY_PROPERTIES = setOf("from", "select", "to", "replace")

private fun packageFileSystem(packageDir: Path): FileSystem {
    val root = packageDir.toRealPath()
    val default = FileSystem.newDefaultFileSystem()
    return FileSystem.newCompositeFileSystem(
        FileSystem.newDenyIOFileSystem(),
        FileSystem.Selector.of(default) { path ->
            val absolute = default.toAbsolutePath(path).normalize()
            runCatching { absolute.toRealPath() }
                .getOrNull()
                ?.startsWith(root) == true
        }
    )
}

private fun launchPlan(value: Value, packageDir: Path): LaunchPlan {
    require(value.hasMembers() && !value.hasArrayElements()) {
        "run.js default export must be an object with executable and arguments"
    }
    val executableValue = value.getMember("executable")
    require(executableValue != null && executableValue.isString && executableValue.asString().isNotEmpty()) {
        "run.js launch plan property 'executable' must be a non-empty string"
    }
    val executable = Path.of(executableValue.asString()).let { path ->
        when {
            path.isAbsolute() -> path.normalize()
            path.parent == null -> path
            else -> packageDir.resolve(path).toAbsolutePath().normalize()
        }
    }

    val argumentsValue = value.getMember("arguments")
    require(argumentsValue != null && argumentsValue.hasArrayElements()) {
        "run.js launch plan property 'arguments' must be an array of strings"
    }
    val arguments = (0 until argumentsValue.arraySize.toInt()).map { index ->
        val argument = argumentsValue.getArrayElement(index.toLong())
        require(argument.isString) { "run.js launch plan property 'arguments[$index]' must be a string" }
        argument.asString()
    }
    return LaunchPlan(executable, arguments)
}

private fun contextJavaScript(context: RunContext): String = buildString {
    append("Object.freeze({os: ")
    append(jsString(context.os))
    append(", arch: ")
    append(jsString(context.arch))
    append(", ver: ")
    append(context.ver?.let(::jsString) ?: "null")
    append(", args: Object.freeze([")
    append(context.args.joinToString(",", transform = ::jsString))
    append("]), packageDir: ")
    append(jsString(context.packageDir.toAbsolutePath().normalize().toString()))
    append("})")
}

private fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            in '\u0000'..'\u001F' -> append("\\u%04x".format(character.code))
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(character)
        }
    }
    append('"')
}
