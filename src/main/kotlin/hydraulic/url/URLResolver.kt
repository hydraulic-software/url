package hydraulic.url

import dev.progress4j.api.ProgressReport
import hydraulic.diskcache.DiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpStatusException
import hydraulic.diskcache.http.HttpTransport
import hydraulic.diskcache.http.JdkHttpTransport
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Resolves ordinary HTTP resources and paths within remotely hosted archives.
 *
 * An input is first treated as an ordinary resource. A 404 is the signal to
 * interpret an archive-shaped path as an archive member instead. Archive
 * members are cached as extracted directories, while ordinary resources use
 * the shared HTTP resource cache.
 */
class URLResolver(
    cache: DiskCache,
    private val progressTracker: ProgressReport.Tracker? = null,
    private val gatekeeper: Boolean = true,
    private val transport: HttpTransport = UserAgentHttpTransport(),
    private val refresh: Boolean = false,
    private val minimumFreeSpaceBytes: Long = DEFAULT_MINIMUM_FREE_SPACE_BYTES
) {
    private val baseCache = cache

    internal fun withProgressTracker(tracker: ProgressReport.Tracker?): URLResolver =
        URLResolver(baseCache, tracker, gatekeeper, transport, refresh, minimumFreeSpaceBytes)

    fun resolve(uri: URI, cacheIdentity: URI = uri): ResolvedURL {
        // A cache entry can disappear between lookup and use. Retry the complete resolution once,
        // forcing replacement of every entry touched by the second attempt.
        try {
            return ResolutionAttempt(baseCache).resolveOnce(uri, cacheIdentity)
        } catch (_: DamagedCacheEntryException) {
            return ResolutionAttempt(ForceRebuildDiskCache(baseCache), rebuilding = true).resolveOnce(uri, cacheIdentity)
        }
    }

    /** Keeps the response stream available so a hash lock can consume its complete raw body. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private inner class ResolutionAttempt(cache: DiskCache, private val rebuilding: Boolean = false) {
        private val refresh = this@URLResolver.refresh && !rebuilding
        // The CompleteEntryDiskCache handles the case where the user runs something like:
        //    rm -r $(url example.com/dir/)
        // and thus ends up deleting the entry's content subdirectory but not the full entry directory.
        // If there's a cache hit but in reality it's gone, it's considered a miss.
        // TODO: This check should move into DiskCache itself.
        private val completeCache = CompleteEntryDiskCache(cache)
        private fun resolveHttpResource(uri: URI, cacheIdentity: URI, hashLocked: Boolean = false): DiskCache.OpenedEntry {
            val guardedTransport = HttpTransport { request, headers ->
                val response = transport.get(request, headers)
                try {
                    // The HTTP cache library copies an old body on 304. Reserve room for the complete
                    // known-size copy before allowing it, since that copy does not read the HTTP body.
                    if (response.statusCode == 304 && minimumFreeSpaceBytes > 0) {
                        completeCache.lookup(HttpResourceCache.cacheKey(cacheIdentity))?.use { previous ->
                            val bytes = Files.walk(previous.directory).use { paths ->
                                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                                    .mapToLong { Files.size(it) }.sum()
                            }
                            DiskSpaceGuard(minimumFreeSpaceBytes, previous.directory).check(bytes)
                        }
                    }
                    response
                } catch (failure: Exception) {
                    response.body.close()
                    throw failure
                }
            }
            return HttpResourceCache(
                ContentRevisionCache(if (refresh || hashLocked) MetadataFreeLookupCache(completeCache) else completeCache),
                transport = guardedTransport,
                progressTracker = progressTracker
            ).resolve(uri, cacheIdentity)
        }

        fun resolveOnce(uri: URI, cacheIdentity: URI): ResolvedURL {
            val expectedSha256Hash: String? = uri.hashLock
            val uriWithoutFragment = uri.withoutFragment()
            val cacheIdentityWithoutFragment = cacheIdentity.withoutFragment()
            val archiveHashApplies = uriWithoutFragment.hashLockAppliesToArchiveBytes()
            // A lock on an archive member authenticates the innermost archive
            // bytes before extraction. For an ordinary response, the final file is
            // hashed below after the ordinary-resource/archive decision is made.
            val resolution = resolveResourceOrArchive(
                uriWithoutFragment,
                cacheIdentityWithoutFragment,
                expectedHash = expectedSha256Hash.takeIf { archiveHashApplies }
            )
            val resolved = resolution.resource
            try {
                if (!resolved.path.exists())
                    throwDamagedCacheEntry(resolved, "resolved content disappeared")
                // Directory results come only from extracted archives, whose bytes
                // were verified against the lock before extraction.
                if (expectedSha256Hash != null && resolution.verifiedSha256 != expectedSha256Hash) {
                    require(resolved.path.isRegularFile()) { "SHA-256 locking requires a file or archive result" }
                    val actualHash = resolved.path.sha256()
                    requireSha256(expectedSha256Hash, actualHash, actualHash)
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
            expectedHash: String? = null
        ): Resolution {
            val archive = parseArchiveURL(uri)
            val identityArchive = parseArchiveURL(cacheIdentity)
            if (archive?.isRootRequest(uri) == true)
                return resolveArchive(archive, identityArchive ?: throwInvalidArchiveIdentity(), expectedHash)
            // A URL that looks like an archive member can still be an ordinary
            // resource, so preserve ordinary HTTP resolution as the first attempt.
            try {
                return resolveResource(uri, cacheIdentity, expectedHash)
            } catch (e: HttpStatusException) {
                if (e.statusCode != 404)
                    throw e
            }

            // A nested archive requested without a trailing slash is a file member of its outer archive.
            // Only a trailing slash asks us to extract that file as an archive root.
            if (archive?.member?.isEmpty() == true) {
                val outer = parseArchiveURL(uri, 1) ?: throw HttpStatusException(uri, 404)
                val identityOuter = parseArchiveURL(cacheIdentity, 1) ?: throwInvalidArchiveIdentity()
                return resolveArchive(outer, identityOuter)
            }

            // Expected... we didn't find /.../foo.zip/bar - that would be a weird URL to really serve.
            // Try again with just /.../foo.zip
            return resolveArchive(
                archive ?: throw HttpStatusException(uri, 404),
                identityArchive ?: throwInvalidArchiveIdentity(),
                expectedHash
            )
        }

        private fun resolveResource(
            uri: URI,
            cacheIdentity: URI,
            expectedHash: String? = null
        ): Resolution {
            // If this URI is hash locked, look for a matching disk cache entry first.
            val lockedEntry: DiskCache.OpenedEntry? = expectedHash?.let { findHashLockedEntry(cacheIdentity, it) }
            val entry: DiskCache.OpenedEntry = lockedEntry ?: run {
                // Otherwise it's not in our disk cache yet. Go fetch!
                //
                // A hash-locked miss must fetch a new body without conditional headers; the lock is the validator for this request.
                resolveHttpResource(uri, cacheIdentity, hashLocked = expectedHash != null)
            }
            val file = entry.singleFile()
            var metadata = entry.metadata
            if (expectedHash != null && metadata[HTTP_CONTENT_HASH_METADATA] != expectedHash) {
                try {
                    requireSha256(expectedHash, file.sha256(), uri.toString())
                } catch (e: Exception) {
                    entry.close()
                    throw e
                }
                metadata = metadata + (HTTP_CONTENT_HASH_METADATA to expectedHash)
            }
            val policy = file.hashbangCachePolicy()
            if (policy != null && metadata[HTTP_CACHE_CONTROL_METADATA] != policy)
                metadata = metadata + (HTTP_CACHE_CONTROL_METADATA to policy)
            // Entries cached before revisions were recorded get one now; it identifies the body this entry holds.
            if (CONTENT_REVISION_METADATA !in metadata)
                metadata = metadata + (CONTENT_REVISION_METADATA to newContentRevision())
            val finalEntry = if (metadata == entry.metadata) {
                entry
            } else {
                val oldDirectory = entry.directory
                entry.close()
                replaceCacheEntry(HttpResourceCache.cacheKey(cacheIdentity), oldDirectory, metadata)
            }
            return Resolution(finalEntry.asSingleFile(), expectedHash)
        }

            /** Reuses a body proven to match a hash lock without contacting the origin. */
        private fun findHashLockedEntry(cacheIdentity: URI, expectedHash: String): DiskCache.OpenedEntry? {
            val key = HttpResourceCache.cacheKey(cacheIdentity)
            val cached = completeCache.lookup(key) ?: return null
            if (cached.metadata[HTTP_CONTENT_HASH_METADATA] == expectedHash)
                return cached

            // Otherwise the cache entry is damaged. Try to fix it. TODO: Just get rid of this; we can do a one time wipe of the cache.
            val file = try {
                cached.directory.listDirectoryEntries().singleOrNull()
            } catch (e: IOException) {
                throwDamagedCacheEntry(cached, "content directory disappeared", e)
            }
            if (file?.isRegularFile() == true && file.sha256().equals(expectedHash, ignoreCase = true)) {
                val oldDirectory = cached.directory
                val metadata = cached.metadata + (HTTP_CONTENT_HASH_METADATA to expectedHash)
                cached.close()
                return replaceCacheEntry(key, oldDirectory, metadata)
            }
            cached.close()
            return null
        }

        // TODO: This is a lame way to update metadata. DiskCache should offer a more direct API.
        private fun replaceCacheEntry(
            key: String,
            oldDirectory: Path,
            metadata: Map<String, String>
        ): DiskCache.OpenedEntry = completeCache.getAndCustomizeEntry(key, rerun = true) { destination ->
            // The old entry is discarded once this one is published, so its files can be shared rather than copied.
            linkDirectory(oldDirectory, destination)
            DiskCache.EntryComputationResult(metadata = metadata)
        }

        private fun linkDirectory(source: Path, destination: Path) {
            preparePrivateCacheDirectory(destination)
            Files.walk(source).use { paths ->
                for (path in paths) {
                    val target = destination.resolve(source.relativize(path))
                    when {
                        path == source -> Unit
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(target)
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> try {
                            Files.createLink(target, path)
                        } catch (_: IOException) {
                            Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                        } catch (_: UnsupportedOperationException) {
                            Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                        else -> Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    }
                }
            }
        }

        private fun resolveArchive(
            archive: ArchiveURL,
            identityArchive: ArchiveURL,
            expectedArchiveHash: String? = null
        ): Resolution {
            // Optimization: A top-level remote tarball can be extracted as its response streams in.
            // This lets us overlap downloading and decompression.
            //
            // A hash lock is checked while reading that response, so it can use the same extracted
            // cache and HTTP metadata as an unlocked tarball.
            //
            // We don't try and do this for zips as zips can have files that don't appear in the
            // archive index at the end, which would be confusing to deal with.
            if (archive.archiveURI.isTopLevelRemoteTarball()) {
                if (expectedArchiveHash != null || !completeCache.has(HttpResourceCache.cacheKey(identityArchive.archiveURI)))
                    return Resolution(
                        resolveStreamedArchive(archive, identityArchive, expectedArchiveHash),
                        expectedArchiveHash
                    )
                val key = HttpResourceCache.cacheKey(identityArchive.archiveURI)
                val downloaded: ResolvedURL = resolveHttpResource(archive.archiveURI, identityArchive.archiveURI)
                    .withContentRevision(key).asSingleFile()
                downloaded.use { downloaded ->
                    return Resolution(resolveExtractedArchive(archive, downloaded.path, revision = downloaded.contentRevision))
                }
            }
            val downloaded = resolveArchiveSource(archive, identityArchive, expectedArchiveHash)
            try {
                if (expectedArchiveHash != null && downloaded.verifiedSha256 != expectedArchiveHash) {
                    val expected = expectedArchiveHash
                    val actual = downloaded.resource.path.sha256()
                    requireSha256(expected, actual, "archive has $actual")
                }
                return Resolution(
                    resolveExtractedArchive(archive, downloaded.resource.path, expectedArchiveHash, downloaded.resource.contentRevision),
                    expectedArchiveHash
                )
            } finally {
                downloaded.resource.close()
            }
        }

        private fun resolveExtractedArchive(
            archive: ArchiveURL,
            archiveFile: Path,
            verifiedArchiveHash: String? = null,
            revision: String? = null
        ): ResolvedURL {
            val extracted = completeCache.getAndCustomizeEntry(
                extractedArchiveCacheKey(archiveFile, quarantinesResults(gatekeeper), verifiedArchiveHash),
                rerun = false
            ) { destination ->
                extractSafeLocalArchive(archiveFile, destination, minimumFreeSpaceBytes)
                if (quarantinesResults(gatekeeper))
                    destination.quarantineTree()
                DiskCache.EntryComputationResult()
            }
            // Extractions are shared by content, so the revision comes from the archive that was resolved.
            return selectArchiveMember(archive, extracted, revision)
        }

        private fun resolveStreamedArchive(
            archive: ArchiveURL,
            identityArchive: ArchiveURL,
            expectedArchiveHash: String? = null
        ): ResolvedURL {
            // We're going to populate a cache entry with the contents of the archive
            // whilst simultaneously downloading it.
            val key = streamedArchiveCacheKey(identityArchive.archiveURI, quarantinesResults(gatekeeper))
            val existing: DiskCache.OpenedEntry? = completeCache.lookup(key)
            // This path bypasses HttpResourceCache, so refresh must bypass both the
            // extracted entry's freshness check and its conditional request headers.
            val cachedHashMatches = expectedArchiveHash != null && existing?.metadata[STREAM_ARCHIVE_HASH] == expectedArchiveHash
            val cachedRepresentationIsFresh = expectedArchiveHash == null &&
                existing != null && streamedArchiveIsFresh(existing.metadata)
            if (!refresh && existing != null && (cachedHashMatches || cachedRepresentationIsFresh))
                return selectArchiveMember(archive, existing, existing.metadata[CONTENT_REVISION_METADATA])
            val previousMetadata = existing?.metadata.orEmpty()
            val previousDirectory = existing?.directory
            existing?.close()
            val requestHeaders = buildMap {
                // A 304 cannot prove a hash that was not checked when the cached
                // extraction was created, so force a 200 in that case. This handles
                // the case where the user switches to and from a hash lock.
                if (!refresh && expectedArchiveHash == null) {
                    previousMetadata[STREAM_ETAG]?.let { put("If-None-Match", it) }
                    previousMetadata[STREAM_LAST_MODIFIED]?.let { put("If-Modified-Since", it) }
                }
            }
            val extracted: DiskCache.OpenedEntry = completeCache.getAndCustomizeEntry(key, rerun = previousDirectory != null) { destination ->
                val response = transport.get(archive.archiveURI, requestHeaders)
                val digest = expectedArchiveHash?.let { MessageDigest.getInstance("SHA-256") }
                response.body.use { body ->
                    val archiveBody: InputStream = digest?.let { NonClosingInputStream(DigestInputStream(body, it)) } ?: body
                    when (response.statusCode) {
                        304 -> {
                            checkNotNull(previousDirectory) { "Received HTTP 304 without cached extraction for ${archive.archiveURI}" }
                            copyDirectory(previousDirectory, destination)
                        }
                        in 200..299 -> extractStreamingTar(archiveBody, destination, minimumFreeSpaceBytes)
                        else -> throw HttpStatusException(archive.archiveURI, response.statusCode)
                    }
                    // If there's junk left over at the end of the tarball, make sure that gets hashed too.
                    if (digest != null)
                        archiveBody.copyTo(OutputStream.nullOutputStream())
                }
                digest?.let { actual ->
                    val actualHash = HexFormat.of().formatHex(actual.digest())
                    requireSha256(expectedArchiveHash, actualHash, "archive has $actualHash")
                }
                // copyDirectory does not carry quarantine over from a revalidated extraction.
                if (quarantinesResults(gatekeeper))
                    destination.quarantineTree()
                DiskCache.EntryComputationResult(
                    metadata = streamedArchiveMetadata(response, previousMetadata, expectedArchiveHash)
                )
            }
            return selectArchiveMember(archive, extracted, extracted.metadata[CONTENT_REVISION_METADATA])
        }

        private fun copyDirectory(source: Path, destination: Path) {
            preparePrivateCacheDirectory(destination)
            val space = DiskSpaceGuard(minimumFreeSpaceBytes, destination)
            Files.walk(source).use { paths ->
                for (path in paths) {
                    val target = destination.resolve(source.relativize(path))
                    val regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    space.check()
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else if (regular) {
                        Files.newInputStream(path).use { input ->
                            space.copyTo(input, target)
                        }
                        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null)
                            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(path) - setOf(
                                PosixFilePermission.GROUP_WRITE,
                                PosixFilePermission.OTHERS_WRITE
                            ))
                    } else {
                        Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    }
                }
            }
        }

        private fun selectArchiveMember(archive: ArchiveURL, extracted: DiskCache.OpenedEntry, revision: String?): ResolvedURL {
            val extractedRoot = try {
                extracted.directory.toRealPath()
            } catch (e: IOException) {
                throwDamagedCacheEntry(extracted, "extracted archive directory disappeared", e)
            }
            // Check the normalized path before resolving symlinks, then check the
            // real path as well so archive members cannot escape through either
            // spelling or filesystem links.
            val selected = archive.member.fold(extractedRoot) { path, component -> path.resolve(component) }.normalize()
            if (!selected.startsWith(extractedRoot) || !selected.exists()) {
                extracted.close()
                if (!rebuilding)
                    throw DamagedCacheEntryException("archive member disappeared from the cache")
                throw MissingArchiveMemberException("Archive member does not exist: ${archive.member.joinToString("/")}")
            }
            val resolvedMember = selected.toRealPath()
            if (!resolvedMember.startsWith(extractedRoot)) {
                extracted.close()
                throw IllegalArgumentException("Archive member escapes the archive root: ${archive.member.joinToString("/")}")
            }
            return ResolvedURL(resolvedMember, extracted, revision)
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
                val identityOuterArchive = parseArchiveURL(identityArchive.archiveURI, 1) ?: throwInvalidArchiveIdentity()
                return resolveArchive(outerArchive, identityOuterArchive)
            }
        }

        private fun throwInvalidArchiveIdentity(): Nothing =
            throw IllegalArgumentException("Archive member URLs require a matching archive-shaped --cache-key-url")

        private fun ArchiveURL.isRootRequest(uri: URI): Boolean = member.isEmpty() && uri.rawPath.endsWith('/')

        private fun DiskCache.OpenedEntry.asSingleFile() = ResolvedURL(singleFile(), this, metadata[CONTENT_REVISION_METADATA])

        /** Returns this HTTP entry, first recording a content revision if it was cached before revisions existed. */
        private fun DiskCache.OpenedEntry.withContentRevision(key: String): DiskCache.OpenedEntry {
            if (CONTENT_REVISION_METADATA in metadata)
                return this
            val oldDirectory = directory
            val updated = metadata + (CONTENT_REVISION_METADATA to newContentRevision())
            close()
            return replaceCacheEntry(key, oldDirectory, updated)
        }

        private fun DiskCache.OpenedEntry.singleFile(): Path {
            val files = try {
                directory.listDirectoryEntries()
            } catch (e: IOException) {
                throwDamagedCacheEntry(this, "content directory disappeared", e)
            }
            if (files.size != 1)
                throwDamagedCacheEntry(this, "expected one cached file but found ${files.size}")
            return files.single()
        }

        private fun throwDamagedCacheEntry(entry: AutoCloseable, detail: String, cause: Throwable? = null): Nothing {
            runCatching { entry.close() }
            if (!rebuilding)
                throw DamagedCacheEntryException(detail, cause)
            throw IOException("Cache entry is incomplete after rebuilding it: $detail", cause)
        }
    }
}

/** The resolved resource and the hash, if it was verified before this result was returned. */
private data class Resolution(val resource: ResolvedURL, val verifiedSha256: String? = null)

internal class MissingArchiveMemberException(message: String) : IllegalArgumentException(message)

private class DamagedCacheEntryException(message: String, cause: Throwable? = null) : IOException(message, cause)

private const val HTTP_CACHE_CONTROL_METADATA = "http.cache-control"
private const val HTTP_CONTENT_HASH_METADATA = "http.sha256"

internal const val USER_AGENT = "Hydraulic URL/1.0"

/** Identifies this client without making the shared HTTP cache library URL-tool-specific. */
internal class UserAgentHttpTransport(
    private val delegate: HttpTransport = JdkHttpTransport()
) : HttpTransport {
    override fun get(uri: URI, headers: Map<String, String>): HttpTransport.Response =
        delegate.get(uri, headers + ("User-Agent" to USER_AGENT))
}

private val URI.hashLock: String?
    get() {
        val fragment = this.rawFragment ?: return null
        if (!fragment.startsWith("sha256="))
            return null
        val hash = fragment.removePrefix("sha256=")
        require(hash.matches(SHA256_HEX)) { "Invalid SHA-256 lock: expected 64 hexadecimal characters" }
        return hash.lowercase()
    }

private val SHA256_HEX = Regex("[0-9A-Fa-f]{64}")

private fun URI.withoutFragment(): URI = rawFragment?.let { URI(toASCIIString().substringBefore('#')) } ?: this


/**
 * A resolved path and the lease on the cache entry containing it.
 *
 * [contentRevision] identifies the downloaded body that supplied the content: it changes whenever a new response body
 * is accepted, and is kept by fresh cache hits and `304 Not Modified` responses.
 */
class ResolvedURL(
    val path: Path,
    private val entry: DiskCache.OpenedEntry,
    internal val contentRevision: String? = null
) : AutoCloseable {
    /** The root of the leased cache entry, which contains [path]. */
    internal val entryDirectory: Path get() = entry.directory

    override fun close() = entry.close()
}
