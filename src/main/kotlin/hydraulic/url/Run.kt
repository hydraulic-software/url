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
        val runScript = RunScriptContext(operatingSystem, architecture, target.version, nestedURLArguments())
        val runEnvironment = runEnvironment(environment)
        localRunScript(target.locator, windows)?.let { script ->
            return runResolvedPath(script, arguments, windows, runEnvironment, runScript)
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
                    return runResolvedPath(resolved.path.toAbsolutePath(), arguments, windows, runEnvironment, runScript)
                }
            }
        } finally {
            (tracker as? AutoCloseable)?.close()
        }
    }

    private fun nestedURLArguments(): List<String> = buildList {
        add("--cache-dir=$cacheDirectory")
        add("--progress=$progress")
        add("--cache-limit=${downloadPolicy.cacheLimitGB}")
        if (refresh)
            add("--refresh")
        if (noGatekeeper)
            add("--no-gatekeeper")
        downloadPolicy.minimumFreeSpaceMB?.let { add("--min-free-space=$it") }
        downloadPolicy.legacyMinimumFreeSpaceGB?.let { add("--cache-free-space-limit=$it") }
        if (downloadPolicy.skipCacheLock)
            add("--skip-cache-lock")
    }
}

internal data class RunTarget(val locator: String, val version: String?)

internal data class RunScriptContext(
    val operatingSystem: String,
    val architecture: String,
    val version: String?,
    val urlArguments: List<String> = emptyList()
)

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

internal fun runEnvironment(inherited: Map<String, String>): Map<String, String> = inherited.toMutableMap().apply {
    remove("OS")
    remove("ARCH")
    remove("VER")
}

internal fun localRunScript(target: String, windows: Boolean): Path? {
    val directory = runCatching { Path.of(target) }.getOrNull()?.takeIf(Files::isDirectory) ?: return null
    val script = directory.resolve(if (windows) "run.ps1" else "run.sh").toAbsolutePath()
    require(Files.isRegularFile(script)) { "Local run directory does not contain ${script.fileName}: $directory" }
    return script
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
    environment: Map<String, String> = System.getenv(),
    runScript: RunScriptContext? = null
): Int {
    val command = when {
        runScript != null && windows && path.fileName.toString().endsWith(".ps1", ignoreCase = true) ->
            listOf("powershell.exe", "-NoProfile", "-Command", powerShellRunWrapper(path, arguments, runScript))
        runScript != null && !windows && path.fileName.toString() == "run.sh" ->
            listOf("sh", "-c", posixRunWrapper(path, runScript), path.toString()) + arguments
        windows && path.fileName.toString().endsWith(".ps1", ignoreCase = true) ->
            listOf("powershell.exe", "-NoProfile", "-File", path.toString()) + arguments
        else -> listOf(path.toString()) + arguments
    }
    return runProcess(command, environment)
}

internal fun posixRunWrapper(path: Path, context: RunScriptContext): String = buildString {
    appendLine("set -e")
    appendLine(posixShellAssignment("OS", context.operatingSystem))
    appendLine(posixShellAssignment("ARCH", context.architecture))
    context.version?.let { appendLine(posixShellAssignment("VER", it)) }
    appendLine("url() {")
    append("  command url")
    context.urlArguments.forEach {
        append(' ')
        append(posixShellQuote(it))
    }
    appendLine(" \"${'$'}@\"")
    appendLine("}")
    append(". ")
    append(posixShellQuote(path.toString()))
}

internal fun powerShellRunWrapper(path: Path, arguments: List<String>, context: RunScriptContext): String = buildString {
    appendLine("${'$'}ErrorActionPreference = 'Stop'")
    appendLine("${'$'}OS = ${powerShellQuote(context.operatingSystem)}")
    appendLine("${'$'}ARCH = ${powerShellQuote(context.architecture)}")
    context.version?.let { appendLine("${'$'}VER = ${powerShellQuote(it)}") }
    appendLine("${'$'}runUrlCommand = (Get-Command url -CommandType Application).Source")
    append("function url { & ${'$'}runUrlCommand")
    context.urlArguments.forEach {
        append(' ')
        append(powerShellQuote(it))
    }
    appendLine(" @args }")
    append(". ")
    append(powerShellQuote(path.toString()))
    arguments.forEach {
        append(' ')
        append(powerShellQuote(it))
    }
}

internal fun powerShellQuote(value: String): String = "'${value.replace("'", "''")}'"

private fun runProcess(command: List<String>, environment: Map<String, String>): Int =
    ProcessBuilder(command).apply {
        environment().clear()
        environment().putAll(environment)
        inheritIO()
    }.start().waitFor()

private fun currentExecutablePath(): Path = ProcessHandle.current().info().command()
    .map(Path::of)
    .orElseThrow { IllegalStateException("Cannot locate the running executable") }

fun main(args: Array<String>) {
    val exitCode = commandLine("run").execute(*args)
    if (exitCode != 0)
        kotlin.system.exitProcess(exitCode)
}
