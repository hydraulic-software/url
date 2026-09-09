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
    var cacheDirectory: Path = OperatingSystemPaths.current(null, "url-tool").localCache.parent

    @Option(
        names = ["--cache-key-url"],
        description = ["Use this URL as the cache identity while requesting URL exactly as supplied."]
    )
    var cacheKeyURL: String? = null

    @Option(names = ["--print0"], description = ["Terminate the returned path with a NUL byte instead of a newline."])
    var print0: Boolean = false

    @Option(names = ["--print-separator"], paramLabel = "CHAR", description = ["Terminate each returned path with this character."])
    var printSeparator: String? = null

    @Option(
        names = ["--progress"],
        paramLabel = "MODE",
        defaultValue = "auto",
        description = ["Progress on stderr: auto, osc, never, plain, or json (default: ${'$'}{DEFAULT-VALUE})."]
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
                    URLResolver(cache, progressTracker).resolve(uri, cacheKeyURL?.let(::parseURL) ?: uri).use { resolved ->
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
): ProgressReport.Tracker? = when (mode) {
    "never" -> null
    "plain" -> ProgressPacer(ProgressPrinter(stderr), 4.0f)
    "json" -> ProgressPacer(ProgressJSONWriter(stderr.writer()), 30.0f)
    "osc" -> OscProgressBarTracker(stderr::print)
    "auto" -> if (environment["TERM"] != "dumb" && stderrInteractive()) {
        TerminalProgressTracker.forOutput(stderr, "NO_COLOR" !in environment)
    } else {
        null
    }
    else -> throw IllegalArgumentException("--progress must be one of: auto, osc, never, plain, json")
}

internal fun isStderrInteractive(): Boolean {
    if (System.getProperty("os.name").startsWith("Windows"))
        return System.console() != null
    return runCatching {
        ProcessBuilder("/usr/bin/test", "-t", "2").inheritIO().start().waitFor() == 0
    }.getOrDefault(false)
}

internal fun parseURL(url: String): URI = URI(if (URL_SCHEME.matchesAt(url, 0)) url else "https://$url")

private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://")

internal fun readURLsFromStdin(reader: java.io.BufferedReader = System.`in`.bufferedReader()): List<String> =
    generateSequence(reader::readLine)
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()

fun main(args: Array<String>) {
    val exitCode = commandLine().execute(*args)
    if (exitCode != 0)
        kotlin.system.exitProcess(exitCode)
}

internal fun commandLine(): CommandLine = CommandLine(URL()).setExecutionExceptionHandler { exception, commandLine, _ ->
    commandLine.err.println("url: ${exception.message ?: exception.javaClass.simpleName}")
    commandLine.commandSpec.exitCodeOnExecutionException()
}
