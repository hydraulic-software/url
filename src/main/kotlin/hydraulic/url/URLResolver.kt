package hydraulic.url

import dev.progress4j.api.ProgressReport
import hydraulic.diskcache.CacheEntryComputation
import hydraulic.diskcache.DiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpStatusException
import hydraulic.diskcache.http.HttpTransport
import hydraulic.diskcache.http.JdkHttpTransport
import hydraulic.archives.extractLocalArchive
import hydraulic.utils.hashing.fingerprint
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Resolves ordinary HTTP resources and paths within remotely hosted archives.
 *
 * An input is first treated as an ordinary resource. A 404 is the signal to
 * interpret an archive-shaped path as an archive member instead. Archive
 * members are cached as extracted directories, while ordinary resources use
 * the shared HTTP resource cache.
 */
class URLResolver private constructor(
    cache: DiskCache,
    private val progressTracker: ProgressReport.Tracker?,
    private val gatekeeper: Boolean = true,
    private val transport: HttpTransport = UserAgentHttpTransport(),
    private val refresh: Boolean,
    private val retryDamagedCacheEntry: Boolean
) {
    constructor(
        cache: DiskCache,
        progressTracker: ProgressReport.Tracker? = null,
        gatekeeper: Boolean = true,
        transport: HttpTransport = UserAgentHttpTransport(),
        refresh: Boolean = false
    ) : this(cache, progressTracker, gatekeeper, transport, refresh, retryDamagedCacheEntry = true)

    private val sourceCache = cache
    private val cache = CompleteEntryDiskCache(cache)
    private val resources = HttpResourceCache(
        if (refresh) RefreshingDiskCache(this.cache) else this.cache,
        transport = transport,
        progressTracker = progressTracker
    )
    private val hashLockedResources = HttpResourceCache(
        RefreshingDiskCache(this.cache),
        transport = transport,
        progressTracker = progressTracker
    )

    fun resolve(uri: URI, cacheIdentity: URI = uri): ResolvedURL {
        try {
            return resolveOnce(uri, cacheIdentity)
        } catch (e: DamagedCacheEntryException) {
            if (!retryDamagedCacheEntry)
                throw IOException("Cache entry is still incomplete after rebuilding it", e)
            // A cache entry can disappear between lookup and use. Retry the
            // whole resolution with a cache view that forces replacement.
            return URLResolver(
                ForceRebuildDiskCache(sourceCache),
                progressTracker,
                gatekeeper,
                transport,
                refresh = false,
                retryDamagedCacheEntry = false
            ).resolve(uri, cacheIdentity)
        }
    }

    private fun resolveOnce(uri: URI, cacheIdentity: URI): ResolvedURL {
        val expectedHash = sha256Lock(uri)
        val uriWithoutFragment = uri.withoutFragment()
        val cacheIdentityWithoutFragment = cacheIdentity.withoutFragment()
        val archiveHashApplies = parseArchiveURL(uriWithoutFragment)?.let {
            it.member.isNotEmpty() || uriWithoutFragment.rawPath.endsWith('/')
        } == true
        // A lock on an archive member authenticates the innermost archive
        // bytes before extraction. For an ordinary response, the final file is
        // hashed below after the ordinary-resource/archive decision is made.
        val resolution = resolveResourceOrArchive(
            uriWithoutFragment,
            cacheIdentityWithoutFragment,
            expectedArchiveHash = expectedHash.takeIf { archiveHashApplies }
        )
        val resolved = resolution.resource
        try {
            if (!resolved.path.exists())
                damagedCacheEntry(resolved, "resolved content disappeared")
            if (expectedHash != null) {
                require(resolved.path.isRegularFile()) { "SHA-256 locking requires a file result" }
                if (!resolution.hashVerified) {
                    val actualHash = resolved.path.sha256()
                    require(actualHash.equals(expectedHash, ignoreCase = true)) {
                        "SHA-256 mismatch: expected $expectedHash but resolved $actualHash"
                    }
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

    private fun resolveResourceOrArchive(
        uri: URI,
        cacheIdentity: URI,
        expectedArchiveHash: String? = null
    ): Resolution {
        val archive = parseArchiveURL(uri)
        val identityArchive = parseArchiveURL(cacheIdentity)
        if (archive != null && archive.member.isEmpty() && uri.rawPath.endsWith('/'))
            return resolveArchive(archive, identityArchive ?: invalidArchiveIdentity(), expectedArchiveHash)
        // A URL that looks like an archive member can still be an ordinary
        // resource, so preserve ordinary HTTP resolution as the first attempt.
        try {
            return resolveResource(uri, cacheIdentity, expectedArchiveHash)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
        }

        return resolveArchive(
            archive ?: throw HttpStatusException(uri, 404),
            identityArchive ?: invalidArchiveIdentity(),
            expectedArchiveHash
        )
    }

    private fun resolveResource(
        uri: URI,
        cacheIdentity: URI,
        expectedHash: String? = null
    ): Resolution {
        val matchingCachedEntry = expectedHash?.let { hash ->
            // A hash lock makes a matching cached body immutable. Reuse it
            // directly instead of asking the origin whether it is still fresh.
            val cached = cache.lookup(HttpResourceCache.cacheKey(cacheIdentity))
            if (cached != null) {
                if (cached.metadata[HTTP_CONTENT_HASH_METADATA] == hash)
                    cached
                else {
                    val file = cached.directory.listDirectoryEntries().singleOrNull()
                    if (file?.isRegularFile() == true && file.sha256().equals(hash, ignoreCase = true)) {
                        val oldDirectory = cached.directory
                        val metadata = cached.metadata + (HTTP_CONTENT_HASH_METADATA to hash)
                        cached.close()
                        replaceCacheEntry(HttpResourceCache.cacheKey(cacheIdentity), oldDirectory, metadata)
                    } else {
                        cached.close()
                        null
                    }
                }
            } else {
                null
            }
        }
        val entry = matchingCachedEntry ?: run {
            // A hash-locked miss must fetch a new body without conditional
            // headers; the lock is the validator for this request.
            (if (expectedHash == null) resources else hashLockedResources).resolve(uri, cacheIdentity)
        }
        val file = entry.singleFile()
        var metadata = entry.metadata
        val hashVerified = expectedHash != null && metadata[HTTP_CONTENT_HASH_METADATA] == expectedHash
        if (expectedHash != null && !hashVerified) {
            try {
                require(file.sha256().equals(expectedHash, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expectedHash but resolved $uri"
                }
            } catch (e: Exception) {
                entry.close()
                throw e
            }
            metadata = metadata + (HTTP_CONTENT_HASH_METADATA to expectedHash)
        }
        val policy = file.hashbangCachePolicy()
        if (policy != null && metadata[HTTP_CACHE_CONTROL_METADATA] != policy)
            metadata = metadata + (HTTP_CACHE_CONTROL_METADATA to policy)
        val finalEntry = if (metadata == entry.metadata) {
            entry
        } else {
            val oldDirectory = entry.directory
            entry.close()
            replaceCacheEntry(HttpResourceCache.cacheKey(cacheIdentity), oldDirectory, metadata)
        }
        return Resolution(finalEntry.asSingleFile(), hashVerified || expectedHash != null)
    }

    private fun replaceCacheEntry(
        key: String,
        oldDirectory: Path,
        metadata: Map<String, String>
    ): DiskCache.OpenedEntry = cache.getAndCustomizeEntry(key, rerun = true) { destination ->
        copyDirectory(oldDirectory, destination)
        DiskCache.EntryComputationResult(metadata = metadata)
    }

    private fun resolveArchive(
        archive: ArchiveURL,
        identityArchive: ArchiveURL,
        expectedArchiveHash: String? = null
    ): Resolution {
        // A top-level remote tarball can be extracted as its response streams
        // in. A hash lock is checked while reading that response, so it can
        // use the same extracted cache and HTTP metadata as an unlocked tarball.
        if (archive.archiveURI.isRemoteTarball() && parseArchiveURL(archive.archiveURI, 1) == null) {
            if (expectedArchiveHash != null || !cache.has(HttpResourceCache.cacheKey(identityArchive.archiveURI)))
                return Resolution(
                    resolveStreamedArchive(archive, identityArchive, expectedArchiveHash),
                    expectedArchiveHash != null
                )
            val downloaded = resources.resolve(archive.archiveURI, identityArchive.archiveURI).asSingleFile()
            try {
                return Resolution(resolveExtractedArchive(archive, downloaded.path))
            } finally {
                downloaded.close()
            }
        }
        val downloaded = resolveArchiveSource(archive, identityArchive, expectedArchiveHash)
        try {
            if (expectedArchiveHash != null && !downloaded.hashVerified) {
                val expected = expectedArchiveHash
                val actual = downloaded.resource.path.sha256()
                require(actual.equals(expected, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expected but resolved archive has $actual"
                }
            }
            return Resolution(
                resolveExtractedArchive(archive, downloaded.resource.path, expectedArchiveHash),
                expectedArchiveHash != null
            )
        } finally {
            downloaded.resource.close()
        }
    }

    private fun resolveExtractedArchive(
        archive: ArchiveURL,
        archiveFile: Path,
        verifiedArchiveHash: String? = null
    ): ResolvedURL {
        val extracted = cache.getAndCustomizeEntry(
            extractedArchiveCacheKey(archiveFile, verifiedArchiveHash),
            rerun = false
        ) { destination ->
            extractLocalArchive(archiveFile, destination)
            DiskCache.EntryComputationResult()
        }
        return selectArchiveMember(archive, extracted)
    }

    private fun resolveStreamedArchive(
        archive: ArchiveURL,
        identityArchive: ArchiveURL,
        expectedArchiveHash: String? = null
    ): ResolvedURL {
        val key = streamedArchiveCacheKey(identityArchive.archiveURI)
        val existing = cache.lookup(key)
        // This path bypasses HttpResourceCache, so refresh must bypass both the
        // extracted entry's freshness check and its conditional request headers.
        val cachedHashMatches = expectedArchiveHash != null && existing?.metadata[STREAM_ARCHIVE_HASH] == expectedArchiveHash
        val cachedRepresentationIsFresh = expectedArchiveHash == null &&
            existing != null && streamedArchiveIsFresh(existing.metadata)
        if (!refresh && existing != null && (cachedHashMatches || cachedRepresentationIsFresh))
            return selectArchiveMember(archive, existing)
        val previousMetadata = existing?.metadata.orEmpty()
        val previousDirectory = existing?.directory
        existing?.close()
        val requestHeaders = buildMap {
            // A 304 cannot prove a hash that was not checked when the cached
            // extraction was created, so force a 200 in that case.
            if (!refresh && expectedArchiveHash == null) {
                previousMetadata[STREAM_ETAG]?.let { put("If-None-Match", it) }
                previousMetadata[STREAM_LAST_MODIFIED]?.let { put("If-Modified-Since", it) }
            }
        }
        val extracted = cache.getAndCustomizeEntry(key, rerun = previousDirectory != null) { destination ->
            val response = transport.get(archive.archiveURI, requestHeaders)
            val digest = expectedArchiveHash?.let { MessageDigest.getInstance("SHA-256") }
            response.body.use { body ->
                val archiveBody = digest?.let { NonClosingInputStream(DigestInputStream(body, it)) } ?: body
                when (response.statusCode) {
                    304 -> {
                        checkNotNull(previousDirectory) { "Received HTTP 304 without cached extraction for ${archive.archiveURI}" }
                        copyDirectory(previousDirectory, destination)
                    }
                    in 200..299 -> extractStreamingTar(archiveBody, destination)
                    else -> throw HttpStatusException(archive.archiveURI, response.statusCode)
                }
                if (digest != null)
                    archiveBody.copyTo(OutputStream.nullOutputStream())
            }
            digest?.let { actual ->
                val actualHash = HexFormat.of().formatHex(actual.digest())
                require(actualHash.equals(expectedArchiveHash, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expectedArchiveHash but resolved archive has $actualHash"
                }
            }
            DiskCache.EntryComputationResult(
                metadata = streamedArchiveMetadata(response, previousMetadata, expectedArchiveHash)
            )
        }
        return selectArchiveMember(archive, extracted)
    }

    /** Keeps the response stream available so a hash lock can consume its complete raw body. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun copyDirectory(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            for (path in paths) {
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectories(target)
                else
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    private fun selectArchiveMember(archive: ArchiveURL, extracted: DiskCache.OpenedEntry): ResolvedURL {
        val extractedRoot = try {
            extracted.directory.toRealPath()
        } catch (e: IOException) {
            damagedCacheEntry(extracted, "extracted archive directory disappeared", e)
        }
        // Check the normalized path before resolving symlinks, then check the
        // real path as well so archive members cannot escape through either
        // spelling or filesystem links.
        val selected = archive.member.fold(extractedRoot) { path, component -> path.resolve(component) }.normalize()
        if (!selected.startsWith(extractedRoot) || !selected.exists()) {
            extracted.close()
            if (retryDamagedCacheEntry)
                throw DamagedCacheEntryException("archive member disappeared from the cache")
            throw MissingArchiveMemberException("Archive member does not exist: ${archive.member.joinToString("/")}")
        }
        val resolvedMember = selected.toRealPath()
        if (!resolvedMember.startsWith(extractedRoot)) {
            extracted.close()
            throw IllegalArgumentException("Archive member escapes the archive root: ${archive.member.joinToString("/")}")
        }
        return ResolvedURL(resolvedMember, extracted)
    }

    private fun resolveArchiveSource(
        archive: ArchiveURL,
        identityArchive: ArchiveURL,
        expectedArchiveHash: String? = null
    ): Resolution {
        try {
            return resolveResource(archive.archiveURI, identityArchive.archiveURI, expectedArchiveHash)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 404)
                throw e
            // A missing inner archive may be a member of an outer archive. The
            // recursive call then resolves and extracts that outer source.
            val outerArchive = parseArchiveURL(archive.archiveURI, 1) ?: throw e
            val identityOuterArchive = parseArchiveURL(identityArchive.archiveURI, 1) ?: invalidArchiveIdentity()
            return resolveArchive(outerArchive, identityOuterArchive)
        }
    }

    private fun invalidArchiveIdentity(): Nothing =
        throw IllegalArgumentException("Archive member URLs require a matching archive-shaped --cache-key-url")

    private fun DiskCache.OpenedEntry.asSingleFile() = ResolvedURL(singleFile(), this)

    private fun DiskCache.OpenedEntry.singleFile(): Path {
        val files = try {
            directory.listDirectoryEntries()
        } catch (e: IOException) {
            damagedCacheEntry(this, "content directory disappeared", e)
        }
        if (files.size != 1)
            damagedCacheEntry(this, "expected one cached file but found ${files.size}")
        return files.single()
    }

    private fun damagedCacheEntry(entry: AutoCloseable, detail: String, cause: Throwable? = null): Nothing {
        runCatching { entry.close() }
        if (retryDamagedCacheEntry)
            throw DamagedCacheEntryException(detail, cause)
        throw IOException("Cache entry is incomplete after rebuilding it: $detail", cause)
    }
}

/** The resolved resource and whether its hash lock was checked upstream. */
private data class Resolution(val resource: ResolvedURL, val hashVerified: Boolean = false)

internal class MissingArchiveMemberException(message: String) : IllegalArgumentException(message)

private class DamagedCacheEntryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Makes the HTTP cache observe an existing entry as a metadata-free miss while preserving its lease and atomic replacement. */
private class RefreshingDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun lookup(key: String): DiskCache.OpenedEntry? = delegate.lookup(key)?.let(::UncachedEntry)

    private class UncachedEntry(private val delegate: DiskCache.OpenedEntry) : DiskCache.OpenedEntry by delegate {
        override val metadata: Map<String, String> = emptyMap()
    }
}

/** Treats cache entries whose mandatory content directory vanished as misses and rebuilds them. */
private class CompleteEntryDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun lookup(key: String): DiskCache.OpenedEntry? = delegate.lookup(key)?.ifComplete()

    override fun has(key: String): Boolean = lookup(key)?.use { true } ?: false

    override fun getAndCustomizeEntry(
        key: String,
        rerun: Boolean,
        block: CacheEntryComputation
    ): DiskCache.OpenedEntry {
        val entry = delegate.getAndCustomizeEntry(key, rerun, block)
        if (entry.directory.isDirectory())
            return entry
        entry.close()
        return delegate.getAndCustomizeEntry(key, rerun = true, block)
    }

    private fun DiskCache.OpenedEntry.ifComplete(): DiskCache.OpenedEntry? {
        if (directory.isDirectory())
            return this
        close()
        return null
    }
}

/** Makes every cache lookup in a retried resolution rebuild and atomically replace its existing entry. */
private class ForceRebuildDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun lookup(key: String): DiskCache.OpenedEntry? = null

    override fun has(key: String): Boolean = false

    override fun getAndCustomizeEntry(
        key: String,
        rerun: Boolean,
        block: CacheEntryComputation
    ): DiskCache.OpenedEntry = delegate.getAndCustomizeEntry(key, rerun = true, block)
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
    if (!IS_MAC_OS || !isRegularFile())
        return
    val prefix = Files.newInputStream(this).use { it.readNBytes(8) }
    if (!isMachOContent(prefix))
        return
    if (enabled)
        MacOSQuarantine.apply(this)
    else
        MacOSQuarantine.remove(this)
}

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
private val IS_MAC_OS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
private const val HTTP_CACHE_CONTROL_METADATA = "http.cache-control"
private const val HTTP_CONTENT_HASH_METADATA = "http.sha256"
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

internal fun extractedArchiveCacheKey(archive: Path, contentHash: String? = null): String = """
    Extracted archive
    Layout version: 2
    File name: ${archive.name}
    SHA-256: ${contentHash ?: archive.fingerprint()}
""".trimIndent()

internal fun streamedArchiveCacheKey(uri: URI): String = "Streamed extracted archive\nLayout version: 2\nURI: $uri"

private const val STREAM_RESPONSE_TIME = "stream.response-time"
private const val STREAM_CACHE_CONTROL = "stream.cache-control"
private const val STREAM_ETAG = "stream.etag"
private const val STREAM_LAST_MODIFIED = "stream.last-modified"
private const val STREAM_AGE = "stream.age"
private const val STREAM_ARCHIVE_HASH = "stream.archive-hash"

private fun streamedArchiveMetadata(
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

private fun streamedArchiveIsFresh(metadata: Map<String, String>): Boolean {
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
