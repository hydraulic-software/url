package hydraulic.url

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.JsonPrimitive

internal val STANDARD_PERMISSION_KEYS = setOf("std.files.read", "std.files.write", "std.network.client",
    "std.network.server", "std.environment", "std.semantics")

internal data class RequestedPermissions(
    val read: List<String> = emptyList(),
    val write: List<String> = emptyList(),
    val networkClient: Boolean? = null,
    val networkServer: Boolean? = null,
    val environment: List<String> = emptyList(),
    val semantics: LocalDate? = null,
    val unrecognized: Set<String> = emptySet(),
    val declared: Set<String> = emptySet()
)

internal data class SemanticsRevision(val date: LocalDate)

// Placeholder revision date from SPEC-RUN.md; finalize before release.
internal val SEMANTICS_REVISIONS = listOf(SemanticsRevision(LocalDate.of(2026, 10, 10)))

internal fun selectRevision(
    timestamp: Instant?,
    declared: LocalDate?,
    today: LocalDate,
    revisions: List<SemanticsRevision> = SEMANTICS_REVISIONS
): SemanticsRevision {
    require(revisions.isNotEmpty())
    val packageDate = timestamp?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: today
    val selectedDate = maxOf(packageDate, declared ?: packageDate)
    return revisions.filter { it.date <= selectedDate }.maxByOrNull { it.date } ?: revisions.minBy { it.date }
}

internal data class GrantedPath(val requested: String, val path: Path, val missing: Boolean)

internal data class SandboxGrant(
    val requested: RequestedPermissions,
    val revision: SemanticsRevision,
    val read: List<GrantedPath>,
    val write: List<GrantedPath>,
    val baselineRead: List<Path>,
    val temporaryDirectory: Path,
    val workingDirectory: Path,
    val stdio: List<StdioPath>,
    val environment: Map<String, String>,
    val environmentMatches: Map<String, List<String>>
)

/** Resolve links before normalizing .., including links in the existing prefix of a missing path. */
internal fun permissionPath(path: Path): Path {
    val absolute = path.toAbsolutePath()
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return absolute.toRealPath()
    return permissionPath(requireNotNull(absolute.parent)).resolve(absolute.fileName).normalize()
}

internal fun environmentMatches(pattern: String, name: String, windows: Boolean): Boolean {
    val expression = pattern.split('*').joinToString(".*") { Regex.escape(it) }
    return Regex(expression, if (windows) setOf(RegexOption.IGNORE_CASE) else emptySet()).matches(name)
}

internal fun sandboxEnvironment(
    environment: Map<String, String>,
    requested: RequestedPermissions?,
    windows: Boolean
): Map<String, String> {
    if (requested == null) return environment
    val patterns = listOf("PATH", "HOME", "TERM", "LANG", "LC_*") + requested.environment
    return environment.filterKeys { name -> patterns.any { environmentMatches(it, name, windows) } }
}

internal fun buildSandboxGrant(
    requested: RequestedPermissions,
    revision: SemanticsRevision,
    context: RunContext,
    cacheEntries: List<Path>,
    temporaryDirectory: Path,
    environment: Map<String, String>,
    windows: Boolean
): SandboxGrant {
    // Each future semantics revision can provide its own grant builder here.
    require(revision in SEMANTICS_REVISIONS) { "Unsupported semantics revision: ${revision.date}" }
    fun paths(values: List<String>) = values.map { value ->
        val path = permissionPath(context.cwd.resolve(value))
        GrantedPath(value, path, !Files.exists(path))
    }
    val filtered = sandboxEnvironment(environment, requested, windows).toMutableMap()
    val tempNames = if (windows) listOf("TEMP", "TMP") else listOf("TMPDIR")
    filtered.keys.removeAll { key -> tempNames.any { key.equals(it, ignoreCase = windows) } }
    tempNames.forEach { filtered[it] = temporaryDirectory.toString() }
    return SandboxGrant(requested, revision, paths(requested.read), paths(requested.write),
        (listOf(context.packageDir) + cacheEntries).map(::permissionPath).distinct(), temporaryDirectory, permissionPath(context.cwd), inheritedMacOSStdio(),
        filtered, requested.environment.associateWith { pattern ->
            environment.keys.filter { environmentMatches(pattern, it, windows) }.sorted()
        })
}

internal fun deleteSandboxTemporaryDirectory(directory: Path) {
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

internal enum class SandboxBackend(val description: String, val confinesProcess: Boolean) {
    ENVIRONMENT_ONLY("environment filtering only", false),
    SEATBELT("macOS Seatbelt", true)
}

internal fun sandboxBackend(
    executable: Path,
    operatingSystem: String,
    environment: Map<String, String> = System.getenv()
): SandboxBackend {
    val actualExecutable = if (executable.parent == null)
        environment["PATH"].orEmpty().split(java.io.File.pathSeparator).asSequence()
            .map { Path.of(it).resolve(executable).toAbsolutePath() }
            .firstOrNull(Files::isExecutable) ?: executable
    else executable
    return if (operatingSystem == "macos" && !isAppBundleExecutable(actualExecutable) &&
        Files.isExecutable(Path.of("/usr/bin/sandbox-exec")))
        SandboxBackend.SEATBELT else SandboxBackend.ENVIRONMENT_ONLY
}

internal fun dryRunDescription(plan: LaunchPlan, grant: SandboxGrant?, backend: SandboxBackend): String = buildString {
    // JSON quoting makes whitespace, quotes and control characters in arguments unambiguous.
    fun quoted(value: String) = JsonPrimitive(value).toString()
    appendLine("Executable:  ${quoted(plan.executable.toString())}")
    appendLine("Arguments:   ${plan.arguments.joinToString(" ") { quoted(it) }}")
    if (grant == null) {
        appendLine("Sandbox:     unrestricted")
        return@buildString
    }
    appendLine("Sandbox:     ${grant.revision.date} rules (${backend.description})")
    appendLine("Permissions:")
    fun paths(key: String, paths: List<GrantedPath>) {
        if (key !in grant.requested.declared) return
        if (paths.isEmpty()) appendLine("  $key  none")
        paths.forEach { appendLine("  $key  ${quoted(it.path.toString())}${if (it.missing) " (does not exist yet)" else ""}") }
    }
    paths("std.files.read", grant.read)
    paths("std.files.write", grant.write)
    if ("std.semantics" in grant.requested.declared) appendLine("  std.semantics  ${grant.requested.semantics}")
    appendLine("  std.network.client  ${grant.requested.networkClient == true}")
    appendLine("  std.network.server  ${grant.requested.networkServer == true}")
    if ("std.environment" in grant.requested.declared && grant.requested.environment.isEmpty())
        appendLine("  std.environment  none")
    grant.environmentMatches.forEach { (pattern, matches) ->
        appendLine("  std.environment  ${quoted(pattern)} (matches ${matches.joinToString(" ").ifEmpty { "none" }})")
    }
    appendLine("  files, network  ${if (backend.confinesProcess) "enforced" else "not enforced"}")
    appendLine("  environment  enforced")
    appendLine("Baseline:")
    grant.baselineRead.forEach { appendLine("  read, execute  ${quoted(it.toString())}") }
    appendLine("  read, execute  system libraries, configuration and programs")
    if (backend == SandboxBackend.SEATBELT) {
        SEATBELT_SYSTEM_PATHS.forEach { appendLine("    ${quoted(it)}") }
        appendLine("  directory metadata and symbolic-link traversal")
        appendLine("  working-directory traversal  ${quoted(grant.workingDirectory.toString())}")
    }
    appendLine("  read, write  private temporary directory ${quoted(grant.temporaryDirectory.toString())}")
    appendLine("  stdio and controlling terminal")
    grant.stdio.forEach { appendLine("    fd ${it.descriptor}  ${quoted(it.path.toString())}") }
    appendLine("  environment  ${grant.environment.keys.sorted().joinToString(" ")}")
    if (grant.requested.unrecognized.isNotEmpty()) {
        appendLine("Unrecognized keys (not granted):")
        grant.requested.unrecognized.sorted().forEach { appendLine("  ${quoted(it)}") }
    }
}
