package hydraulic.url

import hydraulic.diskcache.CacheEntryComputation
import hydraulic.diskcache.DiskCache
import kotlin.io.path.isDirectory

/** Makes HTTP cache lookups behave as misses while preserving entry leases. */
internal class MetadataFreeLookupCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun lookup(key: String): DiskCache.OpenedEntry? = delegate.lookup(key)?.let(::MetadataFreeEntry)

    private class MetadataFreeEntry(private val delegate: DiskCache.OpenedEntry) : DiskCache.OpenedEntry by delegate {
        override val metadata: Map<String, String> = emptyMap()
    }
}

/** Treats cache entries whose mandatory content directory vanished as misses and rebuilds them. */
internal class CompleteEntryDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
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
internal class ForceRebuildDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun lookup(key: String): DiskCache.OpenedEntry? = null

    override fun has(key: String): Boolean = false

    override fun getAndCustomizeEntry(
        key: String,
        rerun: Boolean,
        block: CacheEntryComputation
    ): DiskCache.OpenedEntry = delegate.getAndCustomizeEntry(key, rerun = true, block)
}
