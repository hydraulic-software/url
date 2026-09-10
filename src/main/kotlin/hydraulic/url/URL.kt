package hydraulic.url

import dev.progress4j.api.ProgressReport
import dev.progress4j.terminal.TerminalProgressTracker
import dev.progress4j.utils.ProgressJSONWriter
import dev.progress4j.utils.OscProgressBarTracker
import dev.progress4j.utils.ProgressPacer
import dev.progress4j.utils.ProgressPrinter
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
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.Callable

/** Resolves an HTTP(S) URL to a local path, downloading and revalidating it through a shared disk cache. */
@Command(name = "url", description = ["Print the cached local path of an HTTP(S) resource."], mixinStandardHelpOptions = true)
class URL : Callable<Int> {
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
    lateinit var cacheConfiguration: LocalDiskCache.Configuration

    override fun call(): Int {
        require(printSeparator == null || printSeparator!!.length == 1) { "--print-separator requires exactly one character" }
        require(!print0 || printSeparator == null) { "--print0 and --print-separator cannot be used together" }
        val inputs = urls.ifEmpty { readURLsFromStdin() }
        require(inputs.isNotEmpty()) { "No URLs were supplied as arguments or on stdin" }
        require(cacheKeyURL == null || inputs.size == 1) { "--cache-key-url requires exactly one input URL" }
        val progressTracker = progressTracker(progress, System.err, System.getenv(), ::isStderrInteractive)
        val separator = when {
            print0 -> '\u0000'
            printSeparator != null -> printSeparator!![0]
            else -> '\n'
        }
        cacheConfiguration.directoryLockFileName = "LOCK"
        try {
            LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
                for (input in inputs) {
                    val uri = parseURL(input)
                    URLResolver(cache, progressTracker, gatekeeper = !noGatekeeper).resolve(uri, cacheKeyURL?.let(::parseURL) ?: uri).use { resolved ->
                        // Keep stdout machine-readable: diagnostics and progress must
                        // use stderr, because callers commonly embed this command in command substitution.
                        print(resolved.path.toAbsolutePath())
                        print(separator)
                    }
                }
            }
        } finally {
            (progressTracker as? AutoCloseable)?.close()
        }
        return 0
    }
}

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
