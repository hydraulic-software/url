package hydraulic.url

import hydraulic.diskcache.LocalDiskCache
import hydraulic.utils.os.OperatingSystemPaths
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.exists

/** Resolves and executes a URL, using a conventional startup script for directory URLs. */
@Command(
    name = "run",
    description = ["Resolve and execute a URL."],
    mixinStandardHelpOptions = true
)
class Run(
    private val executablePath: () -> Path = ::currentExecutablePath,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    private val environment: Map<String, String> = System.getenv()
) : Callable<Int> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "URL")
    var url: String? = null

    @Parameters(index = "1..*", paramLabel = "ARG")
    var arguments: List<String> = emptyList()

    @Option(names = ["--install"], description = ["Install the sibling url hard link and zsh integration."])
    var install: Boolean = false

    @Option(names = ["--cache-dir"], description = ["Shared cache directory."])
    var cacheDirectory: Path = OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache

    @Option(names = ["--no-gatekeeper"], description = ["Do not attach macOS Gatekeeper quarantine metadata to Mach-O results."])
    var noGatekeeper: Boolean = false

    @Option(
        names = ["--progress"],
        paramLabel = "MODE",
        defaultValue = "term",
        description = ["Write progress indicators to stderr: bar, term, never, plain, or json."]
    )
    lateinit var progress: String

    @Mixin
    lateinit var downloadPolicy: DownloadPolicy

    override fun call(): Int {
        if (install) {
            require(url == null) { "--install does not accept a URL" }
            val executable = executablePath().toAbsolutePath()
            val urlName = if (windows) "url.exe" else "url"
            val linked = ensureHardLink(executable, executable.resolveSibling(urlName))
            val zshrc = (System.getenv("ZDOTDIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"))).resolve(".zshrc")
            val configured = installZshIntegration(zshrc)
            System.err.println(if (linked || configured) "Installed run/url integration" else "run/url integration is already installed")
            return 0
        }

        val target = requireNotNull(url) { "A URL is required" }
        val uri = runTargetURI(parseURL(target), windows)
        val tracker = progressTracker(progress, System.err, environment, ::isStderrInteractive)
        val minimumFreeSpace = downloadPolicy.minimumFreeSpaceBytes(environment)
        val cacheConfiguration = downloadPolicy.cacheConfiguration()
        try {
            LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
                val transport = MinimumFreeSpaceHttpTransport(
                    UserAgentHttpTransport(),
                    minimumFreeSpace
                ) { Files.getFileStore(cacheDirectory).usableSpace }
                URLResolver(cache, tracker, gatekeeper = !noGatekeeper, transport = transport).resolve(uri).use { resolved ->
                    return runResolvedPath(resolved.path.toAbsolutePath(), arguments, windows)
                }
            }
        } finally {
            (tracker as? AutoCloseable)?.close()
        }
    }
}

internal fun runTargetURI(uri: URI, windows: Boolean): URI {
    val path = uri.rawPath.orEmpty()
    if (path.isNotEmpty() && !path.endsWith('/'))
        return uri
    val suffix = "run.zip/" + if (windows) "run.ps1" else "run.sh"
    val text = uri.toASCIIString()
    val delimiter = listOf(text.indexOf('?'), text.indexOf('#')).filter { it >= 0 }.minOrNull() ?: text.length
    val base = text.substring(0, delimiter)
    val tail = text.substring(delimiter)
    return URI(base + (if (base.endsWith('/')) "" else "/") + suffix + tail)
}

internal fun ensureHardLink(source: Path, target: Path): Boolean {
    require(Files.isRegularFile(source)) { "Cannot locate the running executable: $source" }
    if (target.exists()) {
        if (Files.isSameFile(source, target))
            return false
        require(Files.mismatch(source, target) == -1L) { "$target exists and differs from $source" }
        Files.delete(target)
    }
    Files.createLink(target, source)
    return true
}

internal fun runResolvedPath(path: Path, arguments: List<String>, windows: Boolean): Int {
    val command = if (windows && path.fileName.toString().endsWith(".ps1", ignoreCase = true))
        listOf("powershell.exe", "-NoProfile", "-File", path.toString()) + arguments
    else
        listOf(path.toString()) + arguments
    return ProcessBuilder(command).inheritIO().start().waitFor()
}

private fun currentExecutablePath(): Path = ProcessHandle.current().info().command()
    .map(Path::of)
    .orElseThrow { IllegalStateException("Cannot locate the running executable") }
