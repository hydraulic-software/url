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
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.time.Instant
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
    private val resources: HttpResourceCache = HttpResourceCache(
        cache,
        transport = UserAgentHttpTransport(),
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
            return resources.resolve(uri, cacheIdentity).asSingleFile()
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
        }

        return resolveArchive(
            archive ?: throw HttpStatusException(uri, 404),
            identityArchive ?: invalidArchiveIdentity()
        )
    }

    private fun resolveArchive(archive: ArchiveURL, identityArchive: ArchiveURL): ResolvedURL {
        val downloaded = resolveArchiveSource(archive, identityArchive)
        try {
            val archiveFile = downloaded.path
            val key = extractedArchiveCacheKey(archiveFile)
            val extracted = cache.get(key) { destination ->
                // A version directory is packaging detail, not part of the URL's logical archive root.
                extractLocalArchive(archiveFile, destination, skipSingleRoot = true)
            }
            val extractedRoot = extracted.directory.toRealPath()
            val selected = archive.member.fold(extractedRoot) { path, component -> path.resolve(component) }.normalize()
            if (!selected.startsWith(extractedRoot) || !selected.exists()) {
                extracted.close()
                throw IllegalArgumentException("Archive member does not exist: ${archive.member.joinToString("/")}")
            }
            // Archives may contain symlinks. Resolve the selected member before returning it so a crafted archive cannot expose a path
            // outside its immutable extraction entry.
            val resolvedMember = selected.toRealPath()
            if (!resolvedMember.startsWith(extractedRoot)) {
                extracted.close()
                throw IllegalArgumentException("Archive member escapes the archive root: ${archive.member.joinToString("/")}")
            }
            return ResolvedURL(resolvedMember, extracted)
        } finally {
            downloaded.close()
        }
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

private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.Z", ".zip", ".tar")

private fun decodePathComponent(rawComponent: String): String = URI("https://hydraulic.invalid/$rawComponent").path.removePrefix("/")
