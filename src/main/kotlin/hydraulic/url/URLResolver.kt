package hydraulic.url

import hydraulic.diskcache.DiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpStatusException
import hydraulic.archives.extractLocalArchive
import hydraulic.utils.hashing.fingerprint
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Resolves ordinary HTTP resources and paths within remotely hosted archives. */
class URLResolver(private val cache: DiskCache, private val resources: HttpResourceCache = HttpResourceCache(cache)) {
    fun resolve(uri: URI, cacheIdentity: URI = uri): ResolvedURL {
        val archive = parseArchiveURL(uri)
        if (archive != null && archive.member.isEmpty() && uri.rawPath.endsWith('/'))
            return resolveArchive(archive, cacheIdentity)
        try {
            return resources.resolve(uri, cacheIdentity).asSingleFile()
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
        }

        return resolveArchive(archive ?: throw HttpStatusException(uri, 404), cacheIdentity)
    }

    private fun resolveArchive(archive: ArchiveURL, cacheIdentity: URI): ResolvedURL {
        val identityArchive = parseArchiveURL(cacheIdentity)
            ?: throw IllegalArgumentException("Archive member URLs require a matching archive-shaped --cache-key-url")
        val downloaded = resources.resolve(archive.archiveURI, identityArchive.archiveURI)
        try {
            val archiveFile = downloaded.directory.listDirectoryEntries().single()
            val key = extractedArchiveCacheKey(archiveFile)
            val extracted = cache.get(key) { destination ->
                // A version directory is packaging detail, not part of the URL's logical archive root.
                extractLocalArchive(archiveFile, destination, skipSingleRoot = true)
            }
            val selected = archive.member.fold(extracted.directory) { path, component -> path.resolve(component) }.normalize()
            if (!selected.startsWith(extracted.directory) || !selected.exists()) {
                extracted.close()
                throw IllegalArgumentException("Archive member does not exist: ${archive.member.joinToString("/")}")
            }
            // Archives may contain symlinks. Resolve the selected member before returning it so a crafted archive cannot expose a path
            // outside its immutable extraction entry.
            val resolvedRoot = extracted.directory.toRealPath()
            val resolvedMember = selected.toRealPath()
            if (!resolvedMember.startsWith(resolvedRoot)) {
                extracted.close()
                throw IllegalArgumentException("Archive member escapes the archive root: ${archive.member.joinToString("/")}")
            }
            return ResolvedURL(resolvedMember, extracted)
        } finally {
            downloaded.close()
        }
    }

    private fun DiskCache.OpenedEntry.asSingleFile() = ResolvedURL(directory.listDirectoryEntries().single(), this)
}

internal fun extractedArchiveCacheKey(archive: Path): String = """
    Extracted archive
    File name: ${archive.name}
    SHA-256: ${archive.fingerprint()}
""".trimIndent()

class ResolvedURL(val path: Path, private val entry: DiskCache.OpenedEntry) : AutoCloseable {
    override fun close() = entry.close()
}

internal data class ArchiveURL(val archiveURI: URI, val member: List<String>)

internal fun parseArchiveURL(uri: URI): ArchiveURL? {
    val rawComponents = uri.rawPath.split('/')
    val components = rawComponents.map(::decodePathComponent)
    val archiveIndex = components.indexOfLast { component -> ARCHIVE_SUFFIXES.any { component.endsWith(it, ignoreCase = true) } }
    if (archiveIndex < 0)
        return null
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

private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.Z", ".zip", ".tar")

private fun decodePathComponent(rawComponent: String): String = URI("https://hydraulic.invalid/$rawComponent").path.removePrefix("/")
