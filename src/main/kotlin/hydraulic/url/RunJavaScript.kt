package hydraulic.url

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.nio.file.Path

internal data class RunContext(
    val os: String,
    val arch: String,
    val ver: String?,
    val args: List<String>,
    val packageDir: Path
)

internal data class LaunchPlan(val executable: Path, val arguments: List<String>)

private data class UrlRequest(
    val key: String,
    val name: String?,
    val grouped: Boolean,
    val url: String
)

/** Evaluates a run.js package and resolves its URLs through the host. */
internal fun evaluateRunJavaScript(
    packageFile: Path,
    context: RunContext,
    resolve: (Map<String, String>) -> Map<String, Path>
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
        .option("js.esm-eval-returns-exports", "true")
        .out(System.err)
        .err(System.err)
        .build().use { js ->
            val bindings = js.getBindings("js")
            bindings.putMember("context", js.eval("js", contextJavaScript(context)))
            bindings.putMember("urls", ProxyExecutable { arguments: Array<out Value> ->
                require(arguments.size == 1) { "run.js function urls() expects one object or array" }
                val request = arguments[0]
                resolveUrls(request, resolve)
            })

            val source = Source.newBuilder("js", packagePath.toFile())
                .name(packagePath.toString())
                .mimeType("application/javascript+module")
                .build()
            val result = try {
                js.eval(source)
            } catch (exception: PolyglotException) {
                if (exception.isHostException)
                    throw exception.asHostException()
                throw exception
            }
            val exportedPlan = result.getMember("default")
            require(exportedPlan != null) { "run.js must export its launch plan as the default export" }
            launchPlan(exportedPlan, packageDir)
        }
}

private fun resolveUrls(
    request: Value,
    resolve: (Map<String, String>) -> Map<String, Path>
): Any {
    require(request.hasArrayElements() || request.hasMembers()) {
        "run.js function urls() expects an object or array"
    }

    val reservedKeys = request.getMemberKeys().toMutableSet()
    var nextKey = 0
    fun nextInternalKey(): String {
        while (true) {
            val key = "url${nextKey++}"
            if (reservedKeys.add(key))
                return key
        }
    }

    fun nextRequest(name: String?, grouped: Boolean, url: String): UrlRequest {
        val key = if (name != null && !grouped) name else nextInternalKey()
        return UrlRequest(key = key, name = name, grouped = grouped, url = url)
    }

    val requests = if (request.hasArrayElements()) {
        (0 until request.arraySize.toInt()).map { index ->
            val value = request.getArrayElement(index.toLong())
            require(value.isString) { "run.js URL at index $index must be a string" }
            nextRequest(null, false, value.asString())
        }
    } else {
        request.getMemberKeys().flatMap { name ->
            val value = request.getMember(name)
            when {
                value.isString -> listOf(nextRequest(name, false, value.asString()))
                value.hasArrayElements() -> (0 until value.arraySize.toInt()).map { index ->
                    val item = value.getArrayElement(index.toLong())
                    require(item.isString) { "run.js URL '$name[$index]' must be a string" }
                    nextRequest(name, true, item.asString())
                }
                else -> throw IllegalArgumentException(
                    "run.js URL '$name' must be a string or an array of strings"
                )
            }
        }
    }

    val resolved = resolve(requests.associate { it.key to it.url })
    val paths = requests.associate { requestEntry ->
        requestEntry.key to resolved.getValue(requestEntry.key).toAbsolutePath().normalize().toString()
    }

    if (request.hasArrayElements()) {
        return ProxyArray.fromArray(*requests.map { paths.getValue(it.key) }.toTypedArray())
    }

    return ProxyObject.fromMap(requests.groupBy { it.name!! }.mapValues { (_, entries) ->
        if (entries.first().grouped) {
            ProxyArray.fromArray(*entries.map { paths.getValue(it.key) }.toTypedArray())
        } else {
            paths.getValue(entries.single().key)
        }
    })
}

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
