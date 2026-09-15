package hydraulic.url

import hydraulic.diskcache.LocalDiskCache
import hydraulic.diskcache.http.HttpStatusException
import hydraulic.utils.os.OperatingSystemPaths
import org.graalvm.nativeimage.ImageInfo
import org.graalvm.nativeimage.ProcessProperties
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.exists

/** Resolves and executes a URL, or evaluates and executes a local run.js package. */
@Command(
    name = "run",
    description = ["Resolve and execute a URL or local run.js package."],
    version = [VERSION],
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

    @Option(names = ["--verbose"], description = ["Print detailed failure diagnostics, including stack traces."])
    var verbose: Boolean = verboseErrors(environment)

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
        val localPackage = localRunPackage(target.locator)
        val tracker = progressTracker(progress, System.err, environment, ::isStderrInteractive)
        val minimumFreeSpace = downloadPolicy.minimumFreeSpaceBytes(environment)
        val cacheConfiguration = downloadPolicy.cacheConfiguration()
        try {
            LocalDiskCache(cacheDirectory, cacheConfiguration).open().use { cache ->
                val transport = MinimumFreeSpaceHttpTransport(
                    UserAgentHttpTransport(),
                    minimumFreeSpace
                ) { Files.getFileStore(cacheDirectory).usableSpace }
                val resolver = URLResolver(cache, tracker, gatekeeper = !noGatekeeper, refresh = refresh, transport = transport)
                val packageResource = if (localPackage == null)
                    resolver.resolve(runTargetURI(parseURL(target.locator)))
                else
                    null
                try {
                    val packagePath = localPackage ?: packageResource!!.path.toAbsolutePath()
                    val context = RunContext(
                        os = operatingSystem,
                        arch = architecture,
                        ver = target.version,
                        args = arguments,
                        packageDir = packagePath.toRealPath().parent ?: error("run.js must have a parent directory")
                    )
                    verifyRunPackage(context.packageDir)
                    val opened = ConcurrentLinkedQueue<ResolvedURL>()
                    try {
                        val plan = evaluateRunJavaScript(packagePath, context) { urls ->
                            parallelMapOrdered(urls.entries.toList()) { _, (name, value) ->
                                resolveRunURL(resolver, parseURL(value), windows).also { opened += it }
                                    .let { name to it.path.toAbsolutePath() }
                            }.toMap()
                        }
                        return runResolvedPath(plan.executable, plan.arguments, environment)
                    } finally {
                        opened.forEach(ResolvedURL::close)
                    }
                } finally {
                    packageResource?.close()
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

internal fun localRunPackage(target: String): Path? {
    val path = runCatching { Path.of(target) }.getOrNull()
    if (path == null) {
        require(!looksLikeLocalPath(target)) { "Invalid local run path: $target" }
        return null
    }
    val directory = path.takeIf(Files::isDirectory)
    if (directory == null) {
        require(!looksLikeLocalPath(target)) { "Local run directory does not exist: $path" }
        return null
    }
    val packagePath = directory.resolve("run.js").toAbsolutePath()
    require(Files.isRegularFile(packagePath)) { "Local run directory does not contain run.js: $directory" }
    return packagePath
}

private fun looksLikeLocalPath(target: String): Boolean =
    target == "." || target == ".." ||
        target.startsWith("./") || target.startsWith("../") ||
        target.startsWith(".\\") || target.startsWith("..\\") ||
        target.startsWith("/") || target.startsWith("\\") ||
        runCatching { Path.of(target).isAbsolute }.getOrDefault(false)

internal fun runTargetURI(uri: URI): URI {
    val path = uri.rawPath.orEmpty()
    val finalComponent = path.substringAfterLast('/')
    val hasFilenameSuffix = finalComponent.lastIndexOf('.') > 0
    if (path.isNotEmpty() && !path.endsWith('/') && hasFilenameSuffix)
        return uri
    val suffix = "run.zip/run.js"
    val text = uri.toASCIIString()
    val delimiter = listOf(text.indexOf('?'), text.indexOf('#')).filter { it >= 0 }.minOrNull() ?: text.length
    val base = text.substring(0, delimiter)
    val tail = text.substring(delimiter)
    return URI(base + (if (base.endsWith('/')) "" else "/") + suffix + tail)
}

/** On Windows, executable URL paths may omit the conventional .exe suffix. */
internal fun resolveRunURL(resolver: URLResolver, uri: URI, windows: Boolean): ResolvedURL {
    try {
        return resolver.resolve(uri)
    } catch (e: Exception) {
        if (!windows || uri.rawPath.endsWith(".exe", ignoreCase = true) ||
            e !is MissingArchiveMemberException && (e !is HttpStatusException || e.statusCode != 404))
            throw e
    }
    return resolver.resolve(uri.withExecutableSuffix())
}

private fun URI.withExecutableSuffix(): URI {
    val text = toASCIIString()
    val delimiter = listOf(text.indexOf('?'), text.indexOf('#')).filter { it >= 0 }.minOrNull() ?: text.length
    return URI(text.substring(0, delimiter) + ".exe" + text.substring(delimiter))
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
    environment: Map<String, String> = System.getenv()
): Int = runProcess(listOf(path.toString()) + arguments, environment)

private fun runProcess(command: List<String>, environment: Map<String, String>): Int =
    ProcessBuilder(command).apply {
        environment().clear()
        environment().putAll(environment)
        inheritIO()
    }.start().waitFor()

private fun currentExecutablePath(): Path = Path.of(currentExecutableName())

internal fun currentExecutableName(): String = if (ImageInfo.inImageRuntimeCode())
    ProcessProperties.getExecutableName()
else
    System.getProperty("sun.java.command")?.substringBefore(' ')
        ?: throw IllegalStateException("Cannot locate the running executable")

fun main(args: Array<String>) {
    val exitCode = commandLine("run").execute(*args)
    kotlin.system.exitProcess(exitCode)
}
