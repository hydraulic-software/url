package hydraulic.url

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

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

// Placeholder revision date from specs/run.md; finalize before release.
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
    val actualExecutable = resolveExecutablePath(executable, environment["PATH"].orEmpty()) ?: executable
    return if (operatingSystem == "macos" && !isAppBundleExecutable(actualExecutable) &&
        Files.isExecutable(Path.of("/usr/bin/sandbox-exec")))
        SandboxBackend.SEATBELT else SandboxBackend.ENVIRONMENT_ONLY
}

internal fun dryRunDescription(
    plan: LaunchPlan,
    grant: SandboxGrant?,
    backend: SandboxBackend,
    json: Boolean = false
): String {
    fun str(value: String) = JsonPrimitive(value)
    fun strings(values: Iterable<String>) = JsonArray(values.map(::str))
    fun pathRows(values: List<GrantedPath>) = JsonArray(values.map { path -> JsonObject(mapOf(
        "requested" to str(path.requested), "path" to str(path.path.toString()), "missing" to JsonPrimitive(path.missing)
    )) })
    val sandbox = if (grant == null) JsonNull else {
        val permissions = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        if ("std.files.read" in grant.requested.declared) permissions["std.files.read"] = pathRows(grant.read)
        if ("std.files.write" in grant.requested.declared) permissions["std.files.write"] = pathRows(grant.write)
        if ("std.semantics" in grant.requested.declared)
            permissions["std.semantics"] = grant.requested.semantics?.let { str(it.toString()) } ?: JsonNull
        permissions["std.network.client"] = JsonPrimitive(grant.requested.networkClient == true)
        permissions["std.network.server"] = JsonPrimitive(grant.requested.networkServer == true)
        if ("std.environment" in grant.requested.declared) permissions["std.environment"] = JsonArray(
            grant.environmentMatches.map { (pattern, matches) -> JsonObject(mapOf(
                "pattern" to str(pattern), "matches" to strings(matches)
            )) }
        )
        JsonObject(mapOf(
            "revision" to str(grant.revision.date.toString()),
            "backend" to str(backend.description),
            "enforcement" to JsonObject(mapOf("filesAndNetwork" to JsonPrimitive(backend.confinesProcess), "environment" to JsonPrimitive(true))),
            "permissions" to JsonObject(permissions),
            "baseline" to JsonObject(mapOf(
                "readExecute" to strings(grant.baselineRead.map(Path::toString) + "system libraries, configuration and programs"),
                "workingDirectoryTraversal" to str(grant.workingDirectory.toString()),
                "temporaryDirectory" to str(grant.temporaryDirectory.toString()),
                "environment" to strings(grant.environment.keys.sorted())
            )),
            "unrecognizedKeys" to strings(grant.requested.unrecognized.sorted())
        ))
    }
    if (json) return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), JsonObject(mapOf(
        "executable" to str(plan.executable.toString()),
        "arguments" to strings(plan.arguments),
        "sandbox" to sandbox
    )))

    val color = System.console() != null && System.getenv("TERM") != "dumb"
    fun styled(value: String, code: String) = if (color) "\u001B[${code}m$value\u001B[0m" else value
    fun section(title: String) = styled(title, "1;36")
    fun permissionState(label: String, enabled: Boolean) = when {
        enabled -> styled(label, "32")
        color -> styled(label, "2;9")
        else -> "$label (off)"
    }
    fun humanValue(value: String) = if (value.isEmpty() || value.any { it.isWhitespace() }) JsonPrimitive(value).toString() else value
    return buildString {
        fun numbered(values: Iterable<String>) {
            val items = values.toList()
            if (items.isEmpty()) {
                appendLine("    ${styled("(none)", "2")}")
            } else {
                items.forEachIndexed { index, item ->
                    appendLine("    ${styled("%2d".format(index + 1), "2")}  $item")
                }
            }
        }
        appendLine(styled("DRY RUN", "1;33") + styled("  Launch plan", "2"))
        appendLine()
        appendLine(section("Command"))
        appendLine("  ${styled("Executable", "2")}  ${humanValue(plan.executable.toString())}")
        appendLine("  ${styled("Arguments", "2")}")
        numbered(plan.arguments.map(::humanValue))
        if (grant == null) {
            appendLine()
            appendLine(section("Sandbox"))
            appendLine("  ${styled("unrestricted", "32")}")
            return@buildString
        }
        appendLine()
        appendLine(section("Sandbox"))
        appendLine("  ${styled("Rules revision", "2")}  ${grant.revision.date}")
        appendLine("  ${styled("Backend", "2")}         ${backend.description}")
        appendLine()
        appendLine(section("Permissions"))
        appendLine("  ${styled("Requested", "2")}")
        fun paths(label: String, values: List<GrantedPath>) {
            if ("std.files.$label" !in grant.requested.declared) return
            appendLine("  ${styled(label, "2")}")
            numbered(values.map { "${humanValue(it.path.toString())}${if (it.missing) styled("  ·  not created yet", "33") else ""}" })
        }
        paths("read", grant.read); paths("write", grant.write)
        if ("std.semantics" in grant.requested.declared)
            appendLine("  ${styled("semantics", "2")}  ${grant.requested.semantics}")
        appendLine("  ${styled("network", "2")}  ${permissionState("client", grant.requested.networkClient == true)} · ${permissionState("server", grant.requested.networkServer == true)}")
        if ("std.environment" in grant.requested.declared && grant.requested.environment.isEmpty())
            appendLine("  ${styled("environment", "2")}  ${styled("none", "2")}")
        if (grant.environmentMatches.isNotEmpty()) {
            appendLine("  ${styled("environment patterns", "2")}")
            numbered(grant.environmentMatches.map { (pattern, matches) ->
                "${humanValue(pattern)}  ${styled("→", "2")}  ${matches.joinToString(", ").ifEmpty { "none" }}"
            })
        }
        appendLine("  ${styled("enforcement", "2")}  files and network ${if (backend.confinesProcess) "enforced" else "not enforced"} · environment enforced")
        appendLine(); appendLine("  ${styled("Baseline", "2")}")
        appendLine("  ${styled("read + execute", "2")}")
        numbered(grant.baselineRead.map { humanValue(it.toString()) } + "system libraries, configuration and programs")
        if (backend == SandboxBackend.SEATBELT) {
            appendLine("  ${styled("working directory traversal", "2")}  ${humanValue(grant.workingDirectory.toString())}")
        }
        appendLine("  ${styled("private temporary directory", "2")}  ${humanValue(grant.temporaryDirectory.toString())}")
        appendLine("  ${styled("environment", "2")}")
        numbered(grant.environment.keys.sorted())
        if (grant.requested.unrecognized.isNotEmpty()) {
            appendLine(); appendLine(styled("Unrecognized keys (not granted)", "1;33"))
            numbered(grant.requested.unrecognized.sorted().map(::humanValue))
        }
    }
}
