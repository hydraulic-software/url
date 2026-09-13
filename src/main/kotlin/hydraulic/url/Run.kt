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
import java.util.Locale
import java.util.concurrent.Callable
import kotlin.io.path.exists

/** Resolves and executes a URL, or executes the startup script in a local directory. */
@Command(
    name = "run",
    description = ["Resolve and execute a URL or local run.zip.d directory."],
    mixinStandardHelpOptions = true
)
class Run(
    private val executablePath: () -> Path = ::currentExecutablePath,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    private val environment: Map<String, String> = System.getenv(),
    private val operatingSystem: String = runOperatingSystem(),
    private val architecture: String = runArchitecture()
) : Callable<Int> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "URL_OR_DIRECTORY")
    var url: String? = null

    @Parameters(index = "1..*", paramLabel = "ARG")
    var arguments: List<String> = emptyList()

    @Option(names = ["--install"], description = ["Install the sibling url hard link and zsh integration."])
    var install: Boolean = false

    @Option(names = ["--cache-dir"], description = ["Shared cache directory."])
    var cacheDirectory: Path = OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache

    @Option(names = ["-r", "--refresh"], description = ["Ignore any cached HTTP response and download the resource again."])
    var refresh: Boolean = false

    @Option(names = ["--no-gatekeeper"], description = ["Remove macOS Gatekeeper quarantine metadata from Mach-O results."])
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

        val target = parseRunTarget(requireNotNull(url) { "A URL or local directory is required" })
        val runEnvironment = runEnvironment(environment, operatingSystem, architecture, target.version)
        localRunScript(target.locator, windows)?.let { script ->
            return runLocalScript(script, arguments, windows, runEnvironment)
        }
        val uri = runTargetURI(parseURL(target.locator), windows)
        val tracker = progressTracker(progress, System.err, environment, ::isStderrInteractive)
        val minimumFreeSpace = downloadPolicy.minimumFreeSpaceBytes(environment)
        val cacheConfiguration = downloadPolicy.cacheConfiguration()
        try {
            LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
                val transport = MinimumFreeSpaceHttpTransport(
                    UserAgentHttpTransport(),
                    minimumFreeSpace
                ) { Files.getFileStore(cacheDirectory).usableSpace }
                URLResolver(cache, tracker, gatekeeper = !noGatekeeper, refresh = refresh, transport = transport).resolve(uri).use { resolved ->
                    return runResolvedPath(resolved.path.toAbsolutePath(), arguments, windows, runEnvironment)
                }
            }
        } finally {
            (tracker as? AutoCloseable)?.close()
        }
    }
}

internal data class RunTarget(val locator: String, val version: String?)

internal fun parseRunTarget(target: String): RunTarget {
    val suffixStart = listOf(target.indexOf('?'), target.indexOf('#')).filter { it >= 0 }.minOrNull() ?: target.length
    val locator = target.substring(0, suffixStart)
    val versionMarker = locator.lastIndexOf('@')
    if (versionMarker < 0 || versionMarker == locator.lastIndex)
        return RunTarget(target, null)
    val version = locator.substring(versionMarker + 1)
    if ('/' in version)
        return RunTarget(target, null)
    return RunTarget(locator.substring(0, versionMarker) + target.substring(suffixStart), version)
}

internal fun runOperatingSystem(
    osName: String = System.getProperty("os.name"),
    runtimeName: String = System.getProperty("java.runtime.name", "")
): String = when {
    runtimeName.contains("android", ignoreCase = true) -> "android"
    osName.startsWith("Linux", ignoreCase = true) -> "linux"
    osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> "macos"
    osName.startsWith("FreeBSD", ignoreCase = true) -> "freebsd"
    osName.startsWith("Windows", ignoreCase = true) -> "windows"
    else -> osName.lowercase(Locale.ROOT)
}

internal fun runArchitecture(architecture: String = System.getProperty("os.arch")): String =
    when (architecture.lowercase(Locale.ROOT)) {
        "amd64", "x64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> architecture.lowercase(Locale.ROOT)
    }

internal fun runEnvironment(
    inherited: Map<String, String>,
    operatingSystem: String,
    architecture: String,
    version: String?
): Map<String, String> = inherited.toMutableMap().apply {
    this["OS"] = operatingSystem
    this["ARCH"] = architecture
    if (version == null)
        remove("VER")
    else
        this["VER"] = version
}

internal fun localRunScript(target: String, windows: Boolean): Path? {
    val directory = runCatching { Path.of(target) }.getOrNull()?.takeIf(Files::isDirectory) ?: return null
    val script = directory.resolve(if (windows) "run.ps1" else "run.sh").toAbsolutePath()
    require(Files.isRegularFile(script)) { "Local run directory does not contain ${script.fileName}: $directory" }
    return script
}

private fun runLocalScript(path: Path, arguments: List<String>, windows: Boolean, environment: Map<String, String>): Int {
    if (windows)
        return runResolvedPath(path, arguments, windows = true, environment = environment)
    return runProcess(listOf("sh", path.toString()) + arguments, environment)
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

internal fun runResolvedPath(
    path: Path,
    arguments: List<String>,
    windows: Boolean,
    environment: Map<String, String> = System.getenv()
): Int {
    val command = if (windows && path.fileName.toString().endsWith(".ps1", ignoreCase = true))
        listOf("powershell.exe", "-NoProfile", "-File", path.toString()) + arguments
    else
        listOf(path.toString()) + arguments
    return runProcess(command, environment)
}

private fun runProcess(command: List<String>, environment: Map<String, String>): Int =
    ProcessBuilder(command).apply {
        environment().clear()
        environment().putAll(environment)
        inheritIO()
    }.start().waitFor()

private fun currentExecutablePath(): Path = ProcessHandle.current().info().command()
    .map(Path::of)
    .orElseThrow { IllegalStateException("Cannot locate the running executable") }
