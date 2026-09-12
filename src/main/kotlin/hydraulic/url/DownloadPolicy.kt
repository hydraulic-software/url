package hydraulic.url

import hydraulic.diskcache.http.HttpTransport
import hydraulic.diskcache.LocalDiskCache
import picocli.CommandLine.Option
import java.net.URI

internal const val DEFAULT_MINIMUM_FREE_SPACE_MB = 100L
internal const val MINIMUM_FREE_SPACE_ENV = "URL_MIN_FREE_SPACE_MB"

class DownloadPolicy {
    @Option(
        names = ["--min-free-space"],
        paramLabel = "MB",
        description = ["Refuse new downloads below this many free megabytes; 0 disables (default: 100, env: URL_MIN_FREE_SPACE_MB)."]
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
        }
    }
}

/** Checks space only after a request needs a response body, so cache hits and HTTP 304s remain usable. */
internal class MinimumFreeSpaceHttpTransport(
    private val delegate: HttpTransport,
    private val minimumBytes: Long,
    private val usableSpace: () -> Long
) : HttpTransport {
    override fun get(uri: URI, headers: Map<String, String>): HttpTransport.Response {
        val response = delegate.get(uri, headers)
        if (response.statusCode in 200..299 && usableSpace() < minimumBytes) {
            response.body.close()
            throw IllegalStateException(
                "Refusing to download $uri: free disk space is below ${minimumBytes / 1_000_000} MB; " +
                    "override with --min-free-space=0 or $MINIMUM_FREE_SPACE_ENV=0"
            )
        }
        return response
    }
}
