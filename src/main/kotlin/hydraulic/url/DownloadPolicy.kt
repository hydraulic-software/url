package hydraulic.url

import hydraulic.diskcache.http.HttpTransport
import hydraulic.diskcache.LocalDiskCache
import picocli.CommandLine.Option
import java.net.URI
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

internal const val DEFAULT_MINIMUM_FREE_SPACE_MB = 100L
internal const val DEFAULT_MINIMUM_FREE_SPACE_BYTES = DEFAULT_MINIMUM_FREE_SPACE_MB * 1_000_000
internal const val MINIMUM_FREE_SPACE_ENV = "URL_MIN_FREE_SPACE_MB"

class DownloadPolicy {
    @Option(
        names = ["--min-free-space"],
        paramLabel = "MB",
        description = ["Stop downloads and extraction below this many free megabytes; 0 disables (default: 100, env: URL_MIN_FREE_SPACE_MB)."]
    )
    var minimumFreeSpaceMB: Long? = null

    @Option(
        names = ["--cache-free-space-limit"],
        paramLabel = "GB",
        description = ["Set the minimum free-space threshold in gigabytes."]
    )
    var legacyMinimumFreeSpaceGB: Double? = null

    @Option(
        names = ["--cache-limit"],
        paramLabel = "GB",
        defaultValue = "10.0",
        showDefaultValue = picocli.CommandLine.Help.Visibility.ALWAYS,
        description = ["Clean entries when the cache grows beyond this many gigabytes."]
    )
    var cacheLimitGB: Double = 10.0

    @Option(names = ["--skip-cache-lock"], hidden = true)
    var skipCacheLock: Boolean = false

    fun minimumFreeSpaceBytes(environment: Map<String, String>): Long {
        val environmentValue = environment[MINIMUM_FREE_SPACE_ENV]?.let { value ->
            value.toLongOrNull() ?: throw IllegalArgumentException("$MINIMUM_FREE_SPACE_ENV must be an integer number of megabytes")
        }
        legacyMinimumFreeSpaceGB?.let { require(it.isFinite() && it >= 0) { "--cache-free-space-limit must not be negative" } }
        val megabytes = minimumFreeSpaceMB ?: environmentValue
            ?: legacyMinimumFreeSpaceGB?.let { Math.round(it * 1000) }
            ?: DEFAULT_MINIMUM_FREE_SPACE_MB
        require(megabytes >= 0) { "--min-free-space and $MINIMUM_FREE_SPACE_ENV must not be negative" }
        return Math.multiplyExact(megabytes, 1_000_000L)
    }

    fun cacheConfiguration(): LocalDiskCache.Configuration {
        require(cacheLimitGB.isFinite() && cacheLimitGB >= 0) { "--cache-limit must not be negative" }
        return LocalDiskCache.Configuration().apply {
            maxSize = Math.round(cacheLimitGB * 1_000_000_000)
            minFreeDiskSpace = 0
            skipCacheLock = this@DownloadPolicy.skipCacheLock
            directoryLockFileName = "LOCK"
            discardFailedBuilds = { it is InsufficientDiskSpaceException }
        }
    }
}

/** Checks space as bodies are consumed; cached responses and HTTP 304s remain usable. */
internal class MinimumFreeSpaceHttpTransport(
    private val delegate: HttpTransport,
    private val space: DiskSpaceGuard
) : HttpTransport {
    constructor(delegate: HttpTransport, minimumBytes: Long, usableSpace: () -> Long) :
        this(delegate, DiskSpaceGuard(minimumBytes, usableSpace))
    override fun get(uri: URI, headers: Map<String, String>): HttpTransport.Response {
        val response = delegate.get(uri, headers)
        if (response.statusCode !in 200..299)
            return response
        try {
            space.check()
            return response.copy(body = SpaceCheckedBody(response.body, space))
        } catch (e: Exception) {
            response.body.close()
            throw e
        }
    }
}

private class SpaceCheckedBody(
    input: InputStream,
    private val space: DiskSpaceGuard
) : FilterInputStream(input) {
    override fun read(): Int {
        val bytes = ByteArray(1)
        return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        if (length == 0) return 0
        space.check()
        val count = `in`.read(bytes, offset, minOf(length, 64 * 1024))
        if (count > 0) {
            space.check(count.toLong())
        }
        return count
    }

    override fun skip(count: Long): Long {
        val buffer = ByteArray(8192)
        var skipped = 0L
        while (skipped < count) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    override fun markSupported() = false
    override fun mark(readlimit: Int) = Unit
    override fun reset(): Unit = throw java.io.IOException("Response body cannot be rewound")
}

internal fun checkFreeSpace(minimumBytes: Long, available: Long, pendingWrite: Long) {
    if (minimumBytes > 0 && (available < minimumBytes || pendingWrite > available - minimumBytes))
        throw InsufficientDiskSpaceException(
            "Free disk space would fall below ${minimumBytes / 1_000_000} MB; " +
                "override with --min-free-space=0 or $MINIMUM_FREE_SPACE_ENV=0"
        )
}

/** Creates and hardens the cache before it can contain downloaded or extracted files. */
internal fun preparePrivateCacheDirectory(directory: Path) {
    val permissions = PosixFilePermissions.fromString("rwx------")
    if (Files.getFileStore(directory.toAbsolutePath().let { path ->
            generateSequence(path) { it.parent }.first { Files.exists(it) }
        }).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        require(!Files.isSymbolicLink(directory)) { "Cache directory must not be a symbolic link" }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(permissions))
        require(!Files.isSymbolicLink(directory)) { "Cache directory must not be a symbolic link" }
        Files.setPosixFilePermissions(directory, permissions)
    } else {
        Files.createDirectories(directory)
    }
}
