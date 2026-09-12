package hydraulic.url

import io.airlift.compress.v3.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo

/** Extracts a tar stream without materializing the compressed archive on disk. */
internal fun extractStreamingTar(input: InputStream, destination: Path, skipSingleRoot: Boolean = false) {
    destination.createDirectories()
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
            extractTarEntry(tar, entry, destination)
        }
    }
    if (skipSingleRoot)
        removeStreamingSingleRoot(destination)
}

private val ZSTD_MAGIC = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())

private fun extractTarEntry(input: InputStream, entry: TarArchiveEntry, root: Path) {
    val relative = Path.of(entry.name.removePrefix("./"))
    require(!relative.isAbsolute && relative.normalize() == relative && relative.toString().isNotBlank()) {
        "Archive entry has an unsafe path: ${entry.name}"
    }
    val target = root.resolve(relative).normalize()
    require(target.startsWith(root)) { "Archive entry escapes its extraction root: ${entry.name}" }
    if (entry.isDirectory) {
        target.createDirectories()
        return
    }
    target.parent?.createDirectories()
    require(!entry.isLink && !entry.isBlockDevice && !entry.isCharacterDevice && !entry.isFIFO) {
        "Archive entry has an unsupported special type: ${entry.name}"
    }
    if (entry.isSymbolicLink) {
        val linkTarget = Path.of(entry.linkName)
        val resolved = if (linkTarget.isAbsolute) linkTarget else target.parent.resolve(linkTarget).normalize()
        require(!linkTarget.isAbsolute && resolved.startsWith(root)) {
            "Archive symlink escapes its extraction root: ${entry.name}"
        }
        target.deleteIfExists()
        Files.createSymbolicLink(target, linkTarget)
        return
    }
    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
    applyTarMode(target, entry.mode)
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
    if (entries.size != 1 || !entries.single().isDirectory())
        return
    val root = entries.single()
    val temporary = Files.createTempDirectory(destination.parent, "${destination.fileName}.single-root-")
    temporary.deleteExisting()
    root.moveTo(temporary)
    temporary.listDirectoryEntries().forEach { it.moveTo(destination.resolve(it.fileName)) }
    temporary.deleteExisting()
}
