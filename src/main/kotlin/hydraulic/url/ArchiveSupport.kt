package hydraulic.url

import hydraulic.diskcache.http.HttpTransport
import hydraulic.utils.hashing.fingerprint
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name

internal data class ArchiveURL(val archiveURI: URI, val member: List<String>)

internal fun parseArchiveURL(uri: URI, depth: Int = 0): ArchiveURL? {
    val rawComponents = uri.rawPath.split('/')
    val components = rawComponents.map(::decodePathComponent)
    val archiveIndices = components.indices.filter { index ->
        ARCHIVE_SUFFIXES.any { components[index].endsWith(it, ignoreCase = true) }
    }
    val archiveIndex = archiveIndices.getOrNull(archiveIndices.lastIndex - depth) ?: return null
    val member = components.drop(archiveIndex + 1).filter { it.isNotEmpty() }
    require(member.none { it == "." || it == ".." || '/' in it || '\\' in it }) {
        "Archive URL contains an unsafe member path component"
    }
    val archiveRawPath = rawComponents.take(archiveIndex + 1).joinToString("/")
    val archiveURI = buildString {
        append(uri.scheme)
        append("://")
        append(uri.rawAuthority)
        append(archiveRawPath)
        uri.rawQuery?.let { append('?').append(it) }
    }.let(::URI)
    return ArchiveURL(archiveURI, member)
}

internal fun extractedArchiveCacheKey(archive: Path, contentHash: String? = null): String = """
    Extracted archive
    Layout version: 2
    File name: ${archive.name}
    SHA-256: ${contentHash ?: archive.fingerprint()}
""".trimIndent()

internal fun streamedArchiveCacheKey(uri: URI): String = "Streamed extracted archive\nLayout version: 2\nURI: $uri"

private fun URI.isRemoteTarball(): Boolean = ARCHIVE_SUFFIXES.filterNot { it == ".zip" }
    .any { path.endsWith(it, ignoreCase = true) }

internal fun URI.isTopLevelRemoteTarball(): Boolean = isRemoteTarball() && parseArchiveURL(this, depth = 1) == null

internal fun URI.hashLockAppliesToArchiveBytes(): Boolean = parseArchiveURL(this)?.let {
    it.member.isNotEmpty() || rawPath.endsWith('/')
} == true

private fun decodePathComponent(rawComponent: String): String = URI("https://hydraulic.invalid/$rawComponent").path.removePrefix("/")

internal fun streamedArchiveMetadata(
    response: HttpTransport.Response,
    previous: Map<String, String>,
    verifiedArchiveHash: String? = null
): Map<String, String> = buildMap {
    // A 200 describes a replacement representation; only 304 may inherit
    // metadata that the response did not repeat.
    if (response.statusCode == 304)
        putAll(previous)
    put(STREAM_RESPONSE_TIME, Instant.now().toEpochMilli().toString())
    fun replaceFromHeader(name: String, key: String) {
        response.header(name)?.let { put(key, it) }
    }
    replaceFromHeader("cache-control", STREAM_CACHE_CONTROL)
    replaceFromHeader("etag", STREAM_ETAG)
    replaceFromHeader("last-modified", STREAM_LAST_MODIFIED)
    replaceFromHeader("age", STREAM_AGE)
    verifiedArchiveHash?.let { put(STREAM_ARCHIVE_HASH, it) }
}

internal fun streamedArchiveIsFresh(metadata: Map<String, String>): Boolean {
    val responseTime = metadata[STREAM_RESPONSE_TIME]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return false
    val directives = metadata[STREAM_CACHE_CONTROL].orEmpty().split(',').map(String::trim)
    if (directives.any { it.equals("no-cache", true) || it.equals("no-store", true) })
        return false
    val maxAge = directives.firstNotNullOfOrNull { directive ->
        val (name, value) = directive.split('=', limit = 2).let { it.first() to it.getOrElse(1) { "" } }
        value.trim('"').toLongOrNull().takeIf { name.equals("max-age", ignoreCase = true) }
    } ?: return false
    // Age is time already spent in upstream caches, so it consumes the
    // freshness lifetime before this response reaches the local cache.
    val age = metadata[STREAM_AGE]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    if (age >= maxAge)
        return false
    return Duration.between(responseTime, Instant.now()) < Duration.ofSeconds(maxAge - age)
}

private val ARCHIVE_SUFFIXES = listOf(
    ".tar.zstd", ".tar.zst", ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.Z", ".zip", ".tar"
)

internal const val STREAM_RESPONSE_TIME = "stream.response-time"
internal const val STREAM_CACHE_CONTROL = "stream.cache-control"
internal const val STREAM_ETAG = "stream.etag"
internal const val STREAM_LAST_MODIFIED = "stream.last-modified"
internal const val STREAM_AGE = "stream.age"
internal const val STREAM_ARCHIVE_HASH = "stream.archive-hash"
