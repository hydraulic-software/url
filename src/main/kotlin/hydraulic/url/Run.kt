package hydraulic.url

import hydraulic.diskcache.http.HttpStatusException
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
    private val windows: Boolean = Platform.isWindows,
    private val environment: Map<String, String> = System.getenv(),
    private val operatingSystem: String = Platform.os,
    private val architecture: String = runArchitecture()
) : Callable<Int> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "URL_OR_DIRECTORY")
    var url: String? = null

    @Parameters(index = "1..*", paramLabel = "ARG")
    var arguments: List<String> = emptyList()

    @Option(names = ["--install"], description = ["Install the sibling url hard link and zsh integration."])
    var install: Boolean = false

    @Mixin
    var resolverOptions: ResolverOptions = ResolverOptions(environment)

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
        return resolverOptions.withResolver { cache, resolver, _ ->
            val minimumFreeSpace = resolverOptions.downloadPolicy.minimumFreeSpaceBytes(environment)
            val opened = ConcurrentLinkedQueue<ResolvedURL>()
            try {
                val packageResource = if (localPackage == null)
                    resolver.resolve(runTargetURI(parseURL(target.locator)))
                else
                    null
                packageResource?.let { opened += it }
                val packagePath = localPackage ?: packageResource!!.path.toAbsolutePath()
                val context = RunContext(
                    os = operatingSystem,
                    arch = architecture,
                    ver = target.version,
                    args = arguments,
                    packageDir = packagePath.toRealPath().parent ?: error("run.js must have a parent directory")
                )
                verifyRunPackage(context.packageDir)
                val sources = ComposeSources()
                val composer = Composer(cache, quarantinesResults(!resolverOptions.noGatekeeper), minimumFreeSpace, windows)
                val compose = { operations: List<ComposeOperation> ->
                    val (key, composed) = composer.compose(operations, sources::get)
                    opened += composed
                    sources.add(composed.path, ComposeSource(composer.identityOf(key), composed.path, composed.path))
                    composed.path
                }
                val plan = evaluateRunJavaScript(packagePath, context, compose) { url ->
                    val (resolved, effectiveURI) = resolveRunURLWithEffectiveURI(resolver, parseURL(url), windows)
                    opened += resolved
                    val path = resolved.path.toAbsolutePath()
                    sources.add(path, ComposeSource(sourceIdentity(effectiveURI, resolved), path, resolved.entryDirectory))
                    path
                }
                runResolvedPath(launchExecutable(plan.executable, windows), plan.arguments, environment)
            } finally {
                opened.forEach(ResolvedURL::close)
            }
        }
    }
}

internal data class RunTarget(val locator: String, val version: String?)

internal fun parseRunTarget(target: String): RunTarget {
    val (locator, tail) = target.splitURLTail()
    val versionMarker = locator.lastIndexOf('@')
    if (versionMarker < 0 || versionMarker == locator.lastIndex)
        return RunTarget(target, null)
    val version = locator.substring(versionMarker + 1)
    if ('/' in version)
        return RunTarget(target, null)
    return RunTarget(locator.substring(0, versionMarker) + tail, version)
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
    val (base, tail) = text.splitURLTail()
    return URI(base + (if (base.endsWith('/')) "" else "/") + suffix + tail)
}

/** On Windows, tries an `.exe` suffix after a missing executable URL and returns the effective URL. */
internal fun resolveRunURLWithEffectiveURI(resolver: URLResolver, uri: URI, windows: Boolean): Pair<ResolvedURL, URI> {
    try {
        return resolver.resolve(uri) to uri
    } catch (e: Exception) {
        if (!windows || uri.rawPath.endsWith(".exe", ignoreCase = true) ||
            e !is MissingArchiveMemberException && (e !is HttpStatusException || e.statusCode != 404))
            throw e
    }
    val fallback = uri.withExecutableSuffix()
    return resolver.resolve(fallback) to fallback
}

/**
 * A resolved URL's composition identity: a hash-locked URL identifies its content by itself, while an unlocked one also
 * needs the revision of the body that was resolved. Returns null if an unlocked result has no revision.
 */
internal fun sourceIdentity(effectiveURI: URI, resolved: ResolvedURL): String? {
    val url = effectiveURI.toASCIIString()
    if (effectiveURI.rawFragment?.startsWith("sha256=") == true)
        return "URL: $url"
    return resolved.contentRevision?.let { "URL: $url\nRevision: $it" }
}

/**
 * On Windows, a launch plan's executable path may omit its `.exe` suffix, as executable URLs may. Windows would append it
 * anyway for names without a dot, but not for names such as `tool-1.2`. Bare names are left to the command search path.
 */
internal fun launchExecutable(executable: Path, windows: Boolean): Path {
    if (!windows || executable.parent == null || executable.exists())
        return executable
    val withSuffix = executable.resolveSibling("${executable.fileName}.exe")
    return if (withSuffix.exists()) withSuffix else executable
}

private fun URI.withExecutableSuffix(): URI {
    val text = toASCIIString()
    val (base, tail) = text.splitURLTail()
    return URI(base + ".exe" + tail)
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

/** Separates a URL's path spelling from its query and fragment without decoding either. */
private fun String.splitURLTail(): Pair<String, String> {
    val delimiter = indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: length
    return substring(0, delimiter) to substring(delimiter)
}
