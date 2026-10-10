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
