package hydraulic.url

import dev.progress4j.api.ProgressReport
import dev.progress4j.terminal.TerminalProgressTracker
import dev.progress4j.utils.ProgressJSONWriter
import dev.progress4j.utils.OscProgressBarTracker
import dev.progress4j.utils.ProgressPacer
import dev.progress4j.utils.ProgressPrinter
import dev.progress4j.utils.ProgressStreamCombiner
import hydraulic.diskcache.LocalDiskCache
import hydraulic.utils.os.OperatingSystemPaths
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.net.URI
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Resolves an HTTP(S) URL to a local path, downloading and revalidating it through a shared disk cache. */
@Command(name = "url", description = ["Print the cached local path of an HTTP(S) resource."], mixinStandardHelpOptions = true)
class URL(
    private val stdout: PrintStream = System.out,
    private val stdin: BufferedReader = System.`in`.bufferedReader(),
    private val environment: Map<String, String> = System.getenv()
) : Callable<Int> {
    @Parameters(
        index = "0..*",
        paramLabel = "URL",
        description = ["HTTP(S) URLs to resolve; https:// is optional. Reads a commented list from stdin when omitted."]
    )
    var urls: List<String> = emptyList()

    @Option(names = ["--cache-dir"], description = ["Shared cache directory."])
    var cacheDirectory: Path = OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache

    @Option(
        names = ["--cache-key-url"],
        description = ["Use this URL as the cache identity while requesting URL exactly as supplied."]
    )
    var cacheKeyURL: String? = null

    @Option(names = ["--print0"], description = ["Terminate the returned path with a NUL byte instead of a newline."])
    var print0: Boolean = false

    @Option(names = ["--print-separator"], paramLabel = "CHAR", description = ["Terminate each returned path with this character."])
    var printSeparator: String? = null

    @Option(names = ["--no-gatekeeper"], description = ["Do not attach macOS Gatekeeper quarantine metadata to Mach-O results."])
    var noGatekeeper: Boolean = false

    @Option(
        names = ["--progress"],
        paramLabel = "MODE",
        defaultValue = "term",
        description = ["Write progress indicators to stderr: bar (animated unicode progress bar), term (OSC escapes for terminal emulator rendered bars), never, plain, or json (default: ${'$'}{DEFAULT-VALUE}). If stderr isn't a terminal then bar/term have no effect."]
    )
    lateinit var progress: String

    @Mixin
    lateinit var downloadPolicy: DownloadPolicy

    override fun call(): Int {
        require(printSeparator == null || printSeparator!!.length == 1) { "--print-separator requires exactly one character" }
        require(!print0 || printSeparator == null) { "--print0 and --print-separator cannot be used together" }
        val inputs = urls.ifEmpty { readURLsFromStdin(stdin) }
        require(inputs.isNotEmpty()) { "No URLs were supplied as arguments or on stdin" }
        require(cacheKeyURL == null || inputs.size == 1) { "--cache-key-url requires exactly one input URL" }
        val progressTracker = progressTracker(progress, System.err, environment, ::isStderrInteractive)
        val separator = when {
            print0 -> '\u0000'
            printSeparator != null -> printSeparator!![0]
            else -> '\n'
        }
        val minimumFreeSpace = downloadPolicy.minimumFreeSpaceBytes(environment)
        val cacheConfiguration = downloadPolicy.cacheConfiguration()
        try {
            LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
                val transport = MinimumFreeSpaceHttpTransport(
                    UserAgentHttpTransport(),
                    minimumFreeSpace
                ) { Files.getFileStore(cacheDirectory).usableSpace }
                val parallelProgress = ParallelURLProgress(inputs, progressTracker)
                val paths = parallelMapOrdered(inputs) { index, input ->
                    val uri = parseURL(input)
                    try {
                        URLResolver(cache, parallelProgress.tracker(index), gatekeeper = !noGatekeeper, transport = transport)
                            .resolve(uri, cacheKeyURL?.let(::parseURL) ?: uri)
                            .use { it.path.toAbsolutePath() }
                    } finally {
                        parallelProgress.complete(index)
                    }
                }
                // Keep stdout machine-readable and deterministic: diagnostics and
                // progress use stderr, and results retain input order even when a
                // later download finishes first.
                for (path in paths) {
                    stdout.print(path)
                    stdout.print(separator)
                }
            }
        } finally {
            (progressTracker as? AutoCloseable)?.close()
        }
        return 0
    }
}

private const val MAX_PARALLEL_RESOLUTIONS = 8

/** Runs independent inputs concurrently while retaining their original order in the returned list. */
internal fun <T, R> parallelMapOrdered(
    inputs: List<T>,
    maxParallelism: Int = MAX_PARALLEL_RESOLUTIONS,
    action: (Int, T) -> R
): List<R> {
    require(maxParallelism > 0) { "Parallelism must be positive" }
    if (inputs.isEmpty())
        return emptyList()
    if (inputs.size == 1)
        return listOf(action(0, inputs.single()))

    val parallelism = minOf(inputs.size, maxParallelism)
    val executor = Executors.newFixedThreadPool(parallelism)
    val completions = ExecutorCompletionService<IndexedValue<R>>(executor)
    val futures = ArrayList<Future<IndexedValue<R>>>(inputs.size)
    try {
        inputs.forEachIndexed { index, input ->
            futures += completions.submit { IndexedValue(index, action(index, input)) }
        }
        val results = arrayOfNulls<Any?>(inputs.size)
        repeat(inputs.size) {
            val result = try {
                completions.take().get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            results[result.index] = result.value
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as R }
    } finally {
        futures.forEach { it.cancel(true) }
        executor.close()
    }
}

/** Gives each parallel resolution its own progress4j subreport. */
internal class ParallelURLProgress(inputs: List<String>, tracker: ProgressReport.Tracker?) {
    private val labels = inputs.map(::progressLabel)
    private val combiner = tracker?.let { ProgressStreamCombiner(false, it) }
    private val completed = AtomicInteger()
    private val base = ProgressReport.create("Resolving URLs", inputs.size)
    private val children = labels.map { label ->
        combiner?.addSubTask()?.also { it.report(ProgressReport.createIndeterminate(label)) }
    }

    init {
        combiner?.report(base)
    }

    fun tracker(index: Int): ProgressReport.Tracker? {
        val child = children[index] ?: return null
        val label = labels[index]
        return ProgressReport.Tracker { progress ->
            val detail = progress.message?.takeIf(String::isNotBlank)
            child.report(progress.withMessage(if (detail == null) label else "$label — $detail"))
        }
    }

    fun complete(index: Int) {
        val child = children[index] ?: return
        child.report(ProgressReport.create(labels[index], 1, 1))
        combiner!!.report(base.withCompleted(completed.incrementAndGet().toLong()))
    }
}

private fun progressLabel(input: String): String = input.take(80).let { if (it.length == input.length) it else "$it…" }

internal fun progressTracker(
    mode: String,
    stderr: PrintStream,
    environment: Map<String, String>,
    stderrInteractive: () -> Boolean
): ProgressReport.Tracker? {
    val smartTerm = environment["TERM"] != "dumb" && stderrInteractive()
    return when (mode) {
        "never" -> null
        "plain" -> ProgressPacer(ProgressPrinter(stderr), 4.0f)
        "json" -> ProgressPacer(ProgressJSONWriter(stderr.writer()), 30.0f)
        "term" -> if (smartTerm) OscProgressBarTracker(stderr::print) else null
        "bar" -> if (smartTerm) TerminalProgressTracker.forOutput(stderr, "NO_COLOR" !in environment) else null
        else -> throw IllegalArgumentException("--progress must be one of: never, plain, json, term or bar")
    }
}

internal fun isStderrInteractive(): Boolean {
    return runCatching {
        (ISATTY?.invokeWithArguments(STANDARD_ERROR_FILENO) as? Int ?: 0) != 0
    }.getOrDefault(false)
}

private const val STANDARD_ERROR_FILENO = 2

private val ISATTY: MethodHandle? by lazy {
    runCatching {
        val linker = Linker.nativeLinker()
        val lookup = linker.defaultLookup()
        sequenceOf("isatty", "_isatty").firstNotNullOfOrNull { lookup.find(it).orElse(null) }
            ?.let { symbol ->
                linker.downcallHandle(
                    symbol,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                )
            }
    }.getOrNull()
}

internal fun parseURL(url: String): URI = URI(if (URL_SCHEME.matchesAt(url, 0)) url else "https://$url")

private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://")

internal fun readURLsFromStdin(reader: java.io.BufferedReader = System.`in`.bufferedReader()): List<String> =
    generateSequence(reader::readLine)
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()

fun main(args: Array<String>) {
    val exitCode = commandLine(processInvocationName()).execute(*args)
    if (exitCode != 0)
        kotlin.system.exitProcess(exitCode)
}

internal fun commandLine(invocationName: String = "url"): CommandLine {
    val normalizedName = invocationName.substringBeforeLast('.').lowercase()
    val runner = normalizedName == "run"
    return CommandLine(if (runner) Run() else URL())
        .apply { if (runner) isStopAtPositional = true }
        .setExecutionExceptionHandler { exception, commandLine, _ ->
            commandLine.err.println("${commandLine.commandName}: ${exception.message ?: exception.javaClass.simpleName}")
            commandLine.commandSpec.exitCodeOnExecutionException()
        }
}

internal fun processInvocationName(): String = ProcessHandle.current().info().command()
    .map { Path.of(it).fileName.toString() }
    .orElse("url")
