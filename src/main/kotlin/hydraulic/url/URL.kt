package hydraulic.url

import hydraulic.diskcache.LocalDiskCache
import hydraulic.utils.os.OperatingSystemPaths
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Callable

/** Resolves an HTTP(S) URL to a local path, downloading and revalidating it through a shared disk cache. */
@Command(name = "url", description = ["Print the cached local path of an HTTP(S) resource."], mixinStandardHelpOptions = true)
class URL : Callable<Int> {
    @Parameters(index = "0", paramLabel = "URL", description = ["The HTTP(S) URL to resolve; https:// is optional."])
    lateinit var url: String

    @Option(names = ["--cache-dir"], description = ["Shared cache directory."])
    var cacheDirectory: Path = OperatingSystemPaths.current(null, "url-tool").localCache.parent

    @Option(
        names = ["--cache-key-url"],
        description = ["Use this URL as the cache identity while requesting URL exactly as supplied."]
    )
    var cacheKeyURL: String? = null

    @Option(names = ["--print0"], description = ["Terminate the returned path with a NUL byte instead of a newline."])
    var print0: Boolean = false

    @Mixin
    lateinit var cacheConfiguration: LocalDiskCache.Configuration

    override fun call(): Int {
        cacheConfiguration.directoryLockFileName = "LOCK"
        LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
            val uri = parseURL(url)
            URLResolver(cache).resolve(uri, cacheKeyURL?.let(::parseURL) ?: uri).use { resolved ->
                // Keep stdout machine-readable: diagnostics and progress must
                // use stderr, because callers commonly embed this command in command substitution.
                print(resolved.path.toAbsolutePath())
                print(if (print0) '\u0000' else '\n')
            }
        }
        return 0
    }
}

internal fun parseURL(url: String): URI = URI(if (URL_SCHEME.matchesAt(url, 0)) url else "https://$url")

private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://")

fun main(args: Array<String>) {
    val exitCode = commandLine().execute(*args)
    if (exitCode != 0)
        kotlin.system.exitProcess(exitCode)
}

internal fun commandLine(): CommandLine = CommandLine(URL()).setExecutionExceptionHandler { exception, commandLine, _ ->
    commandLine.err.println("url: ${exception.message ?: exception.javaClass.simpleName}")
    commandLine.commandSpec.exitCodeOnExecutionException()
}
