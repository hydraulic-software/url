package hydraulic.url

import java.time.LocalDate

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
    timestamp: java.time.Instant?,
    declared: LocalDate?,
    today: LocalDate,
    revisions: List<SemanticsRevision> = SEMANTICS_REVISIONS
): SemanticsRevision {
    require(revisions.isNotEmpty())
    val packageDate = timestamp?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate() ?: today
    val selectedDate = maxOf(packageDate, declared ?: packageDate)
    return revisions.filter { it.date <= selectedDate }.maxByOrNull { it.date } ?: revisions.minBy { it.date }
}
