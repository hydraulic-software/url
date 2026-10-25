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
    private val architecture: String = runArchitecture(),
    private val clock: java.time.Clock = java.time.Clock.systemUTC()
) : Callable<Int> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "URL_OR_PACKAGE")
    var url: String? = null

    @Parameters(index = "1..*", paramLabel = "ARG")
    var arguments: List<String> = emptyList()

    @Option(names = ["--install"], description = ["Install the sibling url hard link and zsh integration."])
    var install: Boolean = false

    @Option(names = ["--dry", "-d"], description = ["Print the evaluated launch plan without starting the process."])
    var dry: Boolean = false

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

        val target = parseRunTarget(requireNotNull(url) { "A URL, local directory, or run.zip file is required" })
        return resolverOptions.withResolver { cache, resolver, _ ->
            val minimumFreeSpace = resolverOptions.downloadPolicy.minimumFreeSpaceBytes(environment)
            val opened = ConcurrentLinkedQueue<ResolvedURL>()
            var sandboxTemporaryDirectory: Path? = null
            var localPackage: LocalRunPackage? = null
            try {
                localPackage = localRunPackage(target.locator)
                val packageResource = if (localPackage == null)
                    resolver.resolve(runTargetURI(parseURL(target.locator)))
                else
                    null
                packageResource?.let { opened += it }
                val packagePath = localPackage?.scriptPath ?: packageResource!!.path.toAbsolutePath()
                val context = RunContext(
                    os = operatingSystem,
                    arch = architecture,
                    ver = target.version,
                    args = arguments,
                    cwd = Path.of("").toAbsolutePath(),
                    cacheDir = userCacheDirectory(operatingSystem, environment = environment),
                    packageDir = packagePath.toRealPath().parent ?: error("run.js must have a parent directory")
                )
                val timestamp = verifyRunPackage(context.packageDir)
                val sources = ComposeSources()
                val composer = Composer(cache, quarantine = quarantinesResults(!resolverOptions.noGatekeeper), minimumFreeSpaceBytes = minimumFreeSpace, windows = windows)
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
                val grant = plan.permissions?.let { requested ->
                    val revision = selectRevision(timestamp, requested.semantics, java.time.LocalDate.now(clock))
                    val temporary = Files.createTempDirectory("hydraulic-run-").toRealPath()
                    sandboxTemporaryDirectory = temporary
                    buildSandboxGrant(requested, revision, context, opened.map { it.entryDirectory },
                        temporary, environment, windows)
                }
                val executable = launchExecutable(plan.executable, windows)
                val backend = sandboxBackend(executable, operatingSystem, grant?.environment ?: environment)
                if (dry) {
                    println(dryRunDescription(plan.copy(executable = executable), grant, backend))
                    0
                } else {
                    val processEnvironment = grant?.environment ?: environment
                    val inspectionPath = resolveExecutablePath(
                        executable, processEnvironment["PATH"] ?: System.getenv("PATH")
                    )

                    // Currently the concept of being "approved" only applies on macOS.
                    val isMacOS = operatingSystem == "macos"
                    val approved = if (!isMacOS) true else {
                        // The policy Apple users have implicitly agreed to is that the OS will check binaries before
                        // before run, and unsigned binaries will be rejected. Users can override this but the default
                        // should follow Apple's lead.
                        //
                        // Bundles (.app directories) are treated slightly differently to standalone Mach-O binaries.
                        // If the binary is inside an app bundle we should just mark it as downloaded from the internet
                        // by setting the quarantine extended attributes and then let the OS check it.
                        val appBundle: Path? = inspectionPath?.enclosingMacOSAppBundle()
                        if (appBundle != null) {
                            if (resolverOptions.noGatekeeper)
                                appBundle.removeQuarantineTree()
                            else
                                appBundle.quarantineTree()
                        }
                        // We can also quarantine standalone Mach-O binaries but this is "wrong" because Gatekeeper
                        // doesn't understand them. It's just a signal for us to later do our own similar checks.
                        val standaloneMachO = appBundle == null && inspectionPath?.isMachOBinary() == true
                        if (standaloneMachO && !resolverOptions.noGatekeeper)
                            inspectionPath.applyGatekeeperQuarantine(enabled = true)
                        // Shell scripts etc are auto-approved, or if the user opted out, otherwise we do our own checks.
                        val ok = !standaloneMachO || resolverOptions.noGatekeeper || maybeDoGatekeeperLikeChecks(inspectionPath, executable)
                        // Verified or explicitly approved standalone binaries must not retain quarantine, as otherwise
                        // Gatekeeper will trigger and be confused by the lack of bundle structure.
                        if (ok && standaloneMachO)
                            inspectionPath.applyGatekeeperQuarantine(enabled = false)
                        ok
                    }

                    if (!approved) {
                        1
                    } else {
                        if (isMacOS && grant != null && backend == SandboxBackend.SEATBELT)
                            runProcess(seatbeltCommand(executable, plan.arguments, grant), grant.environment)
                        else
                            runResolvedPath(executable, plan.arguments, processEnvironment)
                    }
                }
            } finally {
                try {
                    try {
                        sandboxTemporaryDirectory?.let(::deleteSandboxTemporaryDirectory)
                    } finally {
                        localPackage?.temporaryDirectory?.let(::deleteSandboxTemporaryDirectory)
                    }
                } finally {
                    opened.forEach(ResolvedURL::close)
                }
            }
        }
    }

    private fun maybeDoGatekeeperLikeChecks(inspectionPath: Path, executable: Path): Boolean {
        return when (assessMacCodeSignature(inspectionPath)) {
            MacCodeSignatureStatus.SIGNED_AND_NOTARIZED -> true
            MacCodeSignatureStatus.SIGNED_BUT_UNNOTARIZED -> {
                // TODO: Submit signed-but-unnotarized binaries to a third-party notarization service.
                //       Apple doesn't care who submits, it doesn't have to be the original developer and this is supported.
                System.err.println("The executable $executable is signed but not notarized; refusing to run. Pass --no-gatekeeper to bypass this check.")
                false
            }

            MacCodeSignatureStatus.UNSIGNED -> confirmUnsignedExecutable(executable)

            MacCodeSignatureStatus.INVALID_SIGNATURE -> {
                System.err.println("The executable $executable has an invalid code signature; refusing to run.")
                false
            }

            MacCodeSignatureStatus.CHECK_UNAVAILABLE -> {
                System.err.println("Could not check the code signature for $executable; refusing to run.")
                false
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

internal data class LocalRunPackage(val scriptPath: Path, val temporaryDirectory: Path? = null)

internal fun localRunPackage(target: String): LocalRunPackage? {
    val path = runCatching { Path.of(target) }.getOrNull()
    if (path == null) {
        require(!looksLikeLocalPath(target)) { "Invalid local run path: $target" }
        return null
    }
    val directory = path.takeIf(Files::isDirectory)
    if (directory != null) {
        val packagePath = directory.resolve("run.js").toAbsolutePath()
        require(Files.isRegularFile(packagePath)) { "Local run directory does not contain run.js: $directory" }
        return LocalRunPackage(packagePath)
    }

    if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".zip", ignoreCase = true)) {
        val extractionDirectory = Files.createTempDirectory("hydraulic-run-package-").toRealPath()
        try {
            extractSafeLocalArchive(path, extractionDirectory)
            val packagePath = extractionDirectory.resolve("run.js")
            require(Files.isRegularFile(packagePath)) { "Local run archive does not contain run.js: $path" }
            return LocalRunPackage(packagePath, extractionDirectory)
        } catch (e: Exception) {
            deleteSandboxTemporaryDirectory(extractionDirectory)
            throw e
        }
    }

    require(!looksLikeLocalPath(target)) { "Local run directory does not exist: $path" }
    return null
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
    return URI("$base.exe$tail")
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

internal enum class MacCodeSignatureStatus {
    SIGNED_AND_NOTARIZED,
    SIGNED_BUT_UNNOTARIZED,
    UNSIGNED,
    INVALID_SIGNATURE,
    CHECK_UNAVAILABLE
}

internal fun assessMacCodeSignature(executable: Path): MacCodeSignatureStatus {
    // codesign comes with the OS, it doesn't need Xcode to be installed.
    val notarized = runCodesignCheck(
        "--verify", "--strict", "-R=notarized", "--check-notarization", executable.toString()
    ) ?: return MacCodeSignatureStatus.CHECK_UNAVAILABLE
    if (notarized)
        return MacCodeSignatureStatus.SIGNED_AND_NOTARIZED

    val validSignature = runCodesignCheck("--verify", "--strict", executable.toString())
        ?: return MacCodeSignatureStatus.CHECK_UNAVAILABLE
    if (validSignature)
        return MacCodeSignatureStatus.SIGNED_BUT_UNNOTARIZED

    val hasSignature = runCodesignCheck("--display", "--verbose=2", executable.toString())
        ?: return MacCodeSignatureStatus.CHECK_UNAVAILABLE
    return if (hasSignature) MacCodeSignatureStatus.INVALID_SIGNATURE else MacCodeSignatureStatus.UNSIGNED
}

internal fun isNotarized(executable: Path): Boolean =
    assessMacCodeSignature(executable) == MacCodeSignatureStatus.SIGNED_AND_NOTARIZED

private fun confirmUnsignedExecutable(executable: Path): Boolean {
    val console = System.console()
    if (console == null) {
        System.err.println("The executable $executable is unsigned; refusing to run without an interactive console. Pass --no-gatekeeper to bypass this check.")
        return false
    }
    val answer = console.readLine("The executable $executable is unsigned. Run it anyway? [y/N] ")
    if (answer.equals("y", ignoreCase = true) || answer.equals("yes", ignoreCase = true))
        return true
    System.err.println("Not running unsigned executable $executable.")
    return false
}

private fun runCodesignCheck(vararg arguments: String): Boolean? = runCatching {
    val process = ProcessBuilder(listOf("codesign") + arguments).redirectErrorStream(true).start()
    process.inputStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
    process.waitFor() == 0
}.getOrNull()

private fun runProcess(
    command: List<String>,
    environment: Map<String, String>
): Int = ProcessBuilder(command).apply {
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
