package hydraulic.url

import dev.progress4j.api.ProgressReport
import hydraulic.diskcache.DiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpStatusException
import hydraulic.diskcache.http.HttpTransport
import hydraulic.diskcache.http.JdkHttpTransport
import hydraulic.archives.extractLocalArchive
import hydraulic.utils.hashing.fingerprint
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Resolves ordinary HTTP resources and paths within remotely hosted archives. */
class URLResolver(
    private val cache: DiskCache,
    progressTracker: ProgressReport.Tracker? = null,
    private val gatekeeper: Boolean = true,
    private val transport: HttpTransport = UserAgentHttpTransport(),
    private val resources: HttpResourceCache = HttpResourceCache(
        cache,
        transport = transport,
        progressTracker = progressTracker
    )
) {
    fun resolve(uri: URI, cacheIdentity: URI = uri): ResolvedURL {
        val expectedHash = sha256Lock(uri)
        val resolved = resolveWithoutHashLock(uri.withoutFragment(), cacheIdentity.withoutFragment())
        try {
            if (expectedHash != null) {
                require(resolved.path.isRegularFile()) { "SHA-256 locking requires a file result" }
                val actualHash = resolved.path.sha256()
                require(actualHash.equals(expectedHash, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expectedHash but resolved $actualHash"
                }
            }
            resolved.path.makeExecutableIfRecognized()
            resolved.path.applyGatekeeperQuarantine(gatekeeper)
            return resolved
        } catch (e: Exception) {
            resolved.close()
            throw e
        }
    }

    private fun resolveWithoutHashLock(uri: URI, cacheIdentity: URI): ResolvedURL {
        val archive = parseArchiveURL(uri)
        val identityArchive = parseArchiveURL(cacheIdentity)
        if (archive != null && archive.member.isEmpty() && uri.rawPath.endsWith('/'))
            return resolveArchive(archive, identityArchive ?: invalidArchiveIdentity())
        try {
            return resolveResource(uri, cacheIdentity).asSingleFile()
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
        }

        return resolveArchive(
            archive ?: throw HttpStatusException(uri, 404),
            identityArchive ?: invalidArchiveIdentity()
        )
    }

    private fun resolveResource(uri: URI, cacheIdentity: URI): DiskCache.OpenedEntry {
        val entry = resources.resolve(uri, cacheIdentity)
        val file = entry.directory.listDirectoryEntries().single()
        val policy = file.hashbangCachePolicy() ?: return entry
        if (entry.metadata[HTTP_CACHE_CONTROL_METADATA] == policy)
            return entry
        val oldDirectory = entry.directory
        val metadata = entry.metadata + (HTTP_CACHE_CONTROL_METADATA to policy)
        entry.close()
        return cache.getAndCustomizeEntry(HttpResourceCache.cacheKey(cacheIdentity), rerun = true) { destination ->
            Files.walk(oldDirectory).use { paths ->
                for (path in paths) {
                    val relative = oldDirectory.relativize(path)
                    val target = destination.resolve(relative)
                    if (Files.isDirectory(path))
                        Files.createDirectories(target)
                    else
                        Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
            DiskCache.EntryComputationResult(metadata = metadata)
        }
    }

    private fun resolveArchive(archive: ArchiveURL, identityArchive: ArchiveURL): ResolvedURL {
        if (archive.archiveURI.isRemoteTarball() && parseArchiveURL(archive.archiveURI, 1) == null) {
            if (!cache.has(HttpResourceCache.cacheKey(identityArchive.archiveURI)))
                return resolveStreamedArchive(archive, identityArchive)
            val downloaded = resources.resolve(archive.archiveURI, identityArchive.archiveURI).asSingleFile()
            try {
                return resolveExtractedArchive(archive, downloaded.path)
            } finally {
                downloaded.close()
            }
        }
        val downloaded = resolveArchiveSource(archive, identityArchive)
        try {
            return resolveExtractedArchive(archive, downloaded.path)
        } finally {
            downloaded.close()
        }
    }

    private fun resolveExtractedArchive(archive: ArchiveURL, archiveFile: Path): ResolvedURL {
        val extracted = cache.get(extractedArchiveCacheKey(archiveFile)) { destination ->
            extractLocalArchive(archiveFile, destination, skipSingleRoot = true)
        }
        return selectArchiveMember(archive, extracted)
    }

    private fun resolveStreamedArchive(archive: ArchiveURL, identityArchive: ArchiveURL): ResolvedURL {
        val key = streamedArchiveCacheKey(identityArchive.archiveURI)
        val existing = cache.lookup(key)
        if (existing != null && streamedArchiveIsFresh(existing.metadata))
            return selectArchiveMember(archive, existing)
        val previousMetadata = existing?.metadata.orEmpty()
        val previousDirectory = existing?.directory
        existing?.close()
        val requestHeaders = buildMap {
            previousMetadata[STREAM_ETAG]?.let { put("If-None-Match", it) }
            previousMetadata[STREAM_LAST_MODIFIED]?.let { put("If-Modified-Since", it) }
        }
        val extracted = cache.getAndCustomizeEntry(key, rerun = previousDirectory != null) { destination ->
            val response = transport.get(archive.archiveURI, requestHeaders)
            response.body.use { body ->
                when (response.statusCode) {
                    304 -> {
                        checkNotNull(previousDirectory) { "Received HTTP 304 without cached extraction for ${archive.archiveURI}" }
                        copyDirectory(previousDirectory, destination)
                    }
                    in 200..299 -> extractStreamingTar(body, destination, skipSingleRoot = true)
                    else -> throw HttpStatusException(archive.archiveURI, response.statusCode)
                }
            }
            DiskCache.EntryComputationResult(metadata = streamedArchiveMetadata(response, previousMetadata))
        }
        return selectArchiveMember(archive, extracted)
    }

    private fun copyDirectory(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            for (path in paths) {
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path))
                    Files.createDirectories(target)
                else
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun selectArchiveMember(archive: ArchiveURL, extracted: DiskCache.OpenedEntry): ResolvedURL {
        val extractedRoot = extracted.directory.toRealPath()
        val selected = archive.member.fold(extractedRoot) { path, component -> path.resolve(component) }.normalize()
        if (!selected.startsWith(extractedRoot) || !selected.exists()) {
            extracted.close()
            throw IllegalArgumentException("Archive member does not exist: ${archive.member.joinToString("/")}")
        }
        val resolvedMember = selected.toRealPath()
        if (!resolvedMember.startsWith(extractedRoot)) {
            extracted.close()
            throw IllegalArgumentException("Archive member escapes the archive root: ${archive.member.joinToString("/")}")
        }
        return ResolvedURL(resolvedMember, extracted)
    }

    private fun resolveArchiveSource(archive: ArchiveURL, identityArchive: ArchiveURL): ResolvedURL {
        try {
            return resources.resolve(archive.archiveURI, identityArchive.archiveURI).asSingleFile()
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
            val outerArchive = parseArchiveURL(archive.archiveURI, 1) ?: throw e
            val identityOuterArchive = parseArchiveURL(identityArchive.archiveURI, 1) ?: invalidArchiveIdentity()
            return resolveArchive(outerArchive, identityOuterArchive)
        }
    }

    private fun invalidArchiveIdentity(): Nothing =
        throw IllegalArgumentException("Archive member URLs require a matching archive-shaped --cache-key-url")

    private fun DiskCache.OpenedEntry.asSingleFile() = ResolvedURL(directory.listDirectoryEntries().single(), this)
}

internal fun Path.hashbangCachePolicy(): String? {
    if (!isRegularFile())
        return null
    val prefix = Files.newInputStream(this).use { it.readNBytes(MAX_HASHBANG_HEADER_BYTES) }
    if (prefix.size < 2 || prefix[0] != '#'.code.toByte() || prefix[1] != '!'.code.toByte())
        return null
    val text = prefix.toString(StandardCharsets.UTF_8)
    val firstNewline = text.indexOf('\n').takeIf { it >= 0 } ?: return null
    val secondNewline = text.indexOf('\n', firstNewline + 1)
    if (secondNewline < 0 && Files.size(this) > prefix.size)
        return null
    val secondLine = text.substring(firstNewline + 1, secondNewline.takeIf { it >= 0 } ?: text.length).trimEnd('\r')
    val directive = CACHE_CONTROL_COMMENT.matchEntire(secondLine)?.groupValues?.get(1)?.trim() ?: return null
    require(directive.isNotEmpty() && directive.all { it.code in 0x20..0x7e }) {
        "Invalid hashbang Cache-Control directive"
    }
    return directive
}

internal fun Path.makeExecutableIfRecognized() {
    if (!isRegularFile() || Files.getFileAttributeView(this, PosixFileAttributeView::class.java) == null)
        return
    val prefix = Files.newInputStream(this).use { it.readNBytes(8) }
    if (!isExecutableContent(prefix))
        return
    val permissions = Files.getPosixFilePermissions(this)
    Files.setPosixFilePermissions(this, permissions + EXECUTE_PERMISSIONS)
}

private fun isExecutableContent(prefix: ByteArray): Boolean {
    if (prefix.size >= 2 && prefix[0] == '#'.code.toByte() && prefix[1] == '!'.code.toByte())
        return true
    if (prefix.size < 4)
        return false
    val magic = prefix.uint32(0, littleEndian = false)
    if (magic == ELF_MAGIC || isMachOContent(prefix))
        return true
    return false
}

private fun isMachOContent(prefix: ByteArray): Boolean {
    if (prefix.size < 4)
        return false
    val magic = prefix.uint32(0, littleEndian = false)
    if (magic in THIN_MACH_O_MAGICS)
        return true
    if (prefix.size < 8)
        return false
    val architectureCount = when (magic) {
        in FAT_BIG_ENDIAN_MAGICS -> prefix.uint32(4, littleEndian = false)
        in FAT_LITTLE_ENDIAN_MAGICS -> prefix.uint32(4, littleEndian = true)
        else -> return false
    }
    // A Java class also starts with CAFEBABE, but its minor/major version pair
    // does not form a plausible fat Mach-O architecture count.
    return architectureCount in 1u..32u
}

internal fun Path.applyGatekeeperQuarantine(enabled: Boolean) {
    if (!enabled || !IS_MAC_OS || !isRegularFile())
        return
    val prefix = Files.newInputStream(this).use { it.readNBytes(8) }
    if (!isMachOContent(prefix))
        return
    val attributes = Files.getFileAttributeView(this, UserDefinedFileAttributeView::class.java) ?: return
    if (QUARANTINE_ATTRIBUTE in attributes.list())
        return
    val value = gatekeeperQuarantineValue().toByteArray(StandardCharsets.UTF_8)
    attributes.write(QUARANTINE_ATTRIBUTE, ByteBuffer.wrap(value))
}

internal fun gatekeeperQuarantineValue(
    instant: Instant = Instant.now(),
    id: UUID = UUID.randomUUID()
): String = "0081;${instant.epochSecond.toString(16)};Hydraulic URL;$id"

private fun ByteArray.uint32(offset: Int, littleEndian: Boolean): UInt {
    val bytes = if (littleEndian) (offset + 3 downTo offset) else (offset..offset + 3)
    return bytes.fold(0u) { value, index -> (value shl 8) or this[index].toUByte().toUInt() }
}

private val EXECUTE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_EXECUTE
)

private const val ELF_MAGIC = 0x7f454c46u

private val THIN_MACH_O_MAGICS = setOf(
    0xfeedfaceu, 0xcefaedfeu, // 32-bit Mach-O
    0xfeedfacfu, 0xcffaedfeu  // 64-bit Mach-O
)

private val FAT_BIG_ENDIAN_MAGICS = setOf(0xcafebabeu, 0xcafebabfu)
private val FAT_LITTLE_ENDIAN_MAGICS = setOf(0xbebafecau, 0xbfbafecau)
private const val QUARANTINE_ATTRIBUTE = "com.apple.quarantine"
private val IS_MAC_OS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
private const val HTTP_CACHE_CONTROL_METADATA = "http.cache-control"
private val CACHE_CONTROL_COMMENT = Regex("(?:#|//)\\s*Cache-Control:\\s*(.*)", RegexOption.IGNORE_CASE)
private const val MAX_HASHBANG_HEADER_BYTES = 8192

internal const val USER_AGENT = "Hydraulic URL/1.0"

/** Identifies this client without making the shared HTTP cache library URL-tool-specific. */
internal class UserAgentHttpTransport(
    private val delegate: HttpTransport = JdkHttpTransport()
) : HttpTransport {
    override fun get(uri: URI, headers: Map<String, String>): HttpTransport.Response =
        delegate.get(uri, headers + ("User-Agent" to USER_AGENT))
}

private fun sha256Lock(uri: URI): String? {
    val fragment = uri.rawFragment ?: return null
    if (!fragment.startsWith("sha256="))
        return null
    return fragment.removePrefix("sha256=").also {
        require(it.matches(Regex("[0-9A-Fa-f]{64}"))) { "Invalid SHA-256 lock: expected 64 hexadecimal characters" }
    }
}

private fun URI.withoutFragment(): URI = rawFragment?.let { URI(toASCIIString().substringBefore('#')) } ?: this

internal fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0)
                break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

internal fun extractedArchiveCacheKey(archive: Path): String = """
    Extracted archive
    File name: ${archive.name}
    SHA-256: ${archive.fingerprint()}
""".trimIndent()

internal fun streamedArchiveCacheKey(uri: URI): String = "Streamed extracted archive\nURI: $uri"

private const val STREAM_RESPONSE_TIME = "stream.response-time"
private const val STREAM_CACHE_CONTROL = "stream.cache-control"
private const val STREAM_ETAG = "stream.etag"
private const val STREAM_LAST_MODIFIED = "stream.last-modified"

private fun streamedArchiveMetadata(
    response: HttpTransport.Response,
    previous: Map<String, String>
): Map<String, String> = buildMap {
    putAll(previous)
    put(STREAM_RESPONSE_TIME, Instant.now().toEpochMilli().toString())
    fun replaceFromHeader(name: String, key: String) {
        response.header(name)?.let { put(key, it) }
    }
    replaceFromHeader("cache-control", STREAM_CACHE_CONTROL)
    replaceFromHeader("etag", STREAM_ETAG)
    replaceFromHeader("last-modified", STREAM_LAST_MODIFIED)
}

private fun streamedArchiveIsFresh(metadata: Map<String, String>): Boolean {
    val responseTime = metadata[STREAM_RESPONSE_TIME]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return false
    val directives = metadata[STREAM_CACHE_CONTROL].orEmpty().split(',').map(String::trim)
    if (directives.any { it.equals("no-cache", true) || it.equals("no-store", true) })
        return false
    val maxAge = directives.firstNotNullOfOrNull { directive ->
        val (name, value) = directive.split('=', limit = 2).let { it.first() to it.getOrElse(1) { "" } }
        value.trim('"').toLongOrNull().takeIf { name.equals("max-age", ignoreCase = true) }
    } ?: return false
    return Duration.between(responseTime, Instant.now()) < Duration.ofSeconds(maxAge)
}

class ResolvedURL(val path: Path, private val entry: DiskCache.OpenedEntry) : AutoCloseable {
    override fun close() = entry.close()
}

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

private val ARCHIVE_SUFFIXES = listOf(
    ".tar.zstd", ".tar.zst", ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.Z", ".zip", ".tar"
)

private fun URI.isRemoteTarball(): Boolean = ARCHIVE_SUFFIXES.filterNot { it == ".zip" }
    .any { path.endsWith(it, ignoreCase = true) }

private fun decodePathComponent(rawComponent: String): String = URI("https://hydraulic.invalid/$rawComponent").path.removePrefix("/")
