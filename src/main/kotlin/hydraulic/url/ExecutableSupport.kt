package hydraulic.url

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isRegularFile

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
    return magic == ELF_MAGIC || isMachOContent(prefix)
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
private val CACHE_CONTROL_COMMENT = Regex("(?:#|//)\\s*Cache-Control:\\s*(.*)", RegexOption.IGNORE_CASE)
private const val MAX_HASHBANG_HEADER_BYTES = 8192
