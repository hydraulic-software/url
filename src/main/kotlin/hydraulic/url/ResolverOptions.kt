package hydraulic.url

import dev.progress4j.api.ProgressReport
import hydraulic.diskcache.DiskCache
import hydraulic.diskcache.LocalDiskCache
import hydraulic.utils.os.OperatingSystemPaths
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.nio.file.Path

/** Shared command-line options and lifetime of a resolver session. */
class ResolverOptions(private val environment: Map<String, String> = System.getenv()) {
    @Option(names = ["--cache-dir"], description = ["Shared cache directory."])
    var cacheDirectory: Path = OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache

    @Option(names = ["-r", "--refresh"], description = ["Ignore any cached HTTP response and download the resource again."])
    var refresh: Boolean = false

    @Option(names = ["--no-gatekeeper"], description = ["Do not quarantine downloaded and extracted files for macOS Gatekeeper, and remove quarantine from cached files."])
    var noGatekeeper: Boolean = false

    @Option(
        names = ["--progress"],
        paramLabel = "MODE",
        defaultValue = "term",
        description = ["Write progress indicators to stderr: bar (animated unicode progress bar), term (OSC escapes for terminal emulator rendered bars), never, plain, or json (default: ${'$'}{DEFAULT-VALUE}). If stderr isn't a terminal then bar/term have no effect."]
    )
    var progress: String = "term"

    @Option(names = ["--verbose"], description = ["Print detailed failure diagnostics, including stack traces."])
    var verbose: Boolean = verboseErrors(environment)

    @Mixin
    var downloadPolicy: DownloadPolicy = DownloadPolicy()

    internal fun <T> withResolver(action: (DiskCache, URLResolver, ProgressReport.Tracker?) -> T): T {
        val tracker = progressTracker(progress, System.err, environment, ::isStderrInteractive)
        try {
            val minimumBytes = downloadPolicy.minimumFreeSpaceBytes(environment)
            preparePrivateCacheDirectory(cacheDirectory)
            return LocalDiskCache(cacheDirectory, downloadPolicy.cacheConfiguration()).open().use { cache ->
                val transport = MinimumFreeSpaceHttpTransport(UserAgentHttpTransport(), DiskSpaceGuard(minimumBytes, cacheDirectory))
                val resolver = URLResolver(cache, tracker, gatekeeper = !noGatekeeper, refresh = refresh,
                    transport = transport, minimumFreeSpaceBytes = minimumBytes)
                action(cache, resolver, tracker)
            }
        } finally {
            (tracker as? AutoCloseable)?.close()
        }
    }
}
