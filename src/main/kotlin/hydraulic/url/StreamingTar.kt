package hydraulic.url

import io.airlift.compress.v3.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo

/** Extracts a tar stream without materializing the compressed archive on disk. */
internal fun extractStreamingTar(input: InputStream, destination: Path, skipSingleRoot: Boolean = false) {
    destination.createDirectories()
    val root = destination.toRealPath()
    val state = StreamingExtractionState(root)
    val source = BufferedInputStream(input)
    source.mark(1024 * 1024)
    val signature = source.readNBytes(4)
    source.reset()
    val decompressed = if (signature.contentEquals(ZSTD_MAGIC)) {
        ZstdInputStream(source)
    } else {
        try {
            CompressorStreamFactory().createCompressorInputStream(source)
        } catch (_: CompressorException) {
            source.reset()
            source
        }
    }
    TarArchiveInputStream(decompressed).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            require(tar.canReadEntryData(entry)) { "Unable to read archive entry ${entry.name}" }
            extractTarEntry(tar, entry, state)
        }
    }
    state.createSymlinks()
    if (skipSingleRoot)
        removeStreamingSingleRoot(root)
}

private val ZSTD_MAGIC = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())

private fun extractTarEntry(input: InputStream, entry: TarArchiveEntry, state: StreamingExtractionState) {
    val root = state.root
    val relative = Path.of(entry.name.removePrefix("./"))
    require(relative.root == null && relative.normalize() == relative && relative.toString().isNotBlank()) {
        "Archive entry has an unsafe path: ${entry.name}"
    }
    val logicalTarget = root.resolve(relative).normalize()
    require(logicalTarget.startsWith(root)) { "Archive entry escapes its extraction root: ${entry.name}" }
    if (entry.isDirectory) {
        val target = state.prepareDirectory(logicalTarget, entry.name)
        target.createDirectories()
        return
    }
    require(!entry.isLink && !entry.isSparse && !entry.isBlockDevice && !entry.isCharacterDevice && !entry.isFIFO) {
        "Archive entry has an unsupported special type: ${entry.name}"
    }
    if (entry.isSymbolicLink) {
        val linkTarget = Path.of(entry.linkName)
        state.deferSymlink(logicalTarget, linkTarget, entry.name)
        return
    }
    val target = state.prepareFile(logicalTarget, entry.name)
    Files.newOutputStream(
        target,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
    ).use(input::copyTo)
    applyTarMode(target, entry.mode)
}

private class StreamingExtractionState(val root: Path) {
    private val symlinks = linkedMapOf<Path, Path>()

    fun prepareDirectory(logicalTarget: Path, entryName: String): Path {
        val target = resolveParent(logicalTarget, entryName)
        symlinks.remove(target)
        requireSafeParents(target, entryName)
        require(!target.isSymbolicLink()) { "Archive directory replaces a symlink: $entryName" }
        return target
    }

    fun prepareFile(logicalTarget: Path, entryName: String): Path {
        val target = resolveParent(logicalTarget, entryName)
        symlinks.remove(target)
        requireSafeParents(target, entryName)
        require(!target.isSymbolicLink()) { "Archive file replaces a symlink: $entryName" }
        target.parent.createDirectories()
        return target
    }

    fun deferSymlink(logicalLocation: Path, target: Path, entryName: String) {
        require(target.root == null && target.toString().isNotBlank()) { "Archive symlink has an unsafe target: $entryName" }
        val location = resolveParent(logicalLocation, entryName)
        requireSafeParents(location, entryName)
        location.parent.createDirectories()
        symlinks[location] = target
    }

    fun createSymlinks() {
        for ((location, target) in symlinks) {
            requireSafeParents(location, location.toString())
            resolveVirtualTarget(location, target)
        }
        for ((location, target) in symlinks) {
            Files.deleteIfExists(location)
            Files.createSymbolicLink(location, target)
        }
    }

    private fun requireSafeParents(target: Path, entryName: String) {
        var parent = target.parent
        while (parent != root) {
            require(parent != null && parent.startsWith(root)) { "Archive entry escapes its extraction root: $entryName" }
            require(parent !in symlinks && !parent.isSymbolicLink()) {
                "Archive entry would write through a symlink: $entryName"
            }
            parent = parent.parent
        }
    }

    private fun resolveVirtualTarget(location: Path, target: Path) {
        resolveComponents(location.parent, target, location.toString())
    }

    private fun resolveParent(logicalTarget: Path, entryName: String): Path {
        val parent = resolveComponents(root, root.relativize(logicalTarget.parent), entryName)
        return parent.resolve(logicalTarget.fileName)
    }

    private fun resolveComponents(start: Path, components: Path, entryName: String): Path {
        var current = start
        val remaining = ArrayDeque<Path>().apply { components.forEach(::addLast) }
        var traversedLinks = 0
        while (remaining.isNotEmpty()) {
            val component = remaining.removeFirst()
            when (component.toString()) {
                "." -> continue
                ".." -> current = current.parent
                    ?: throw IllegalArgumentException("Archive entry escapes its extraction root: $entryName")
                else -> {
                    val next = current.resolve(component)
                    val deferred = symlinks[next]
                    if (deferred == null) {
                        require(!next.isSymbolicLink()) { "Archive entry traverses an existing symlink: $entryName" }
                        current = next
                    } else {
                        require(++traversedLinks <= 40) { "Archive contains too many chained symlinks: $entryName" }
                        current = next.parent
                        deferred.toList().asReversed().forEach(remaining::addFirst)
                    }
                }
            }
            require(current.startsWith(root)) { "Archive entry escapes its extraction root: $entryName" }
        }
        return current
    }
}

private fun applyTarMode(path: Path, mode: Int) {
    val flags = listOf(
        0b100_000_000 to PosixFilePermission.OWNER_READ,
        0b010_000_000 to PosixFilePermission.OWNER_WRITE,
        0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
        0b000_100_000 to PosixFilePermission.GROUP_READ,
        0b000_010_000 to PosixFilePermission.GROUP_WRITE,
        0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
        0b000_000_100 to PosixFilePermission.OTHERS_READ,
        0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
        0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE
    )
    runCatching { Files.setPosixFilePermissions(path, flags.filter { mode and it.first != 0 }.map { it.second }.toSet()) }
}

private fun removeStreamingSingleRoot(destination: Path) {
    val entries = destination.listDirectoryEntries()
    if (entries.size != 1 || !entries.single().isDirectory(LinkOption.NOFOLLOW_LINKS))
        return
    val root = entries.single()
    val temporary = Files.createTempDirectory(destination.parent, "${destination.fileName}.single-root-")
    temporary.deleteExisting()
    root.moveTo(temporary)
    temporary.listDirectoryEntries().forEach { it.moveTo(destination.resolve(it.fileName)) }
    temporary.deleteExisting()
}
