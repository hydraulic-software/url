package hydraulic.url

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

/**
 * Writes the `com.apple.quarantine` extended attribute directly, in the format browsers produce.
 *
 * Launch Services' quarantine API is not used because it behaves differently for ad-hoc signed
 * processes such as a locally built native image: it drops the agent name and leaves a stub
 * attribute behind on removal instead of deleting it.
 */
internal object MacOSQuarantine {
    /** Quarantines [path] unless it already carries quarantine metadata, which is preserved. */
    fun apply(path: Path) {
        Arena.ofConfined().use { arena ->
            val nativePath = arena.nativePath(path)
            val name = arena.allocateFrom(QUARANTINE_ATTRIBUTE)
            if (attributeSize(nativePath, name) < 0)
                setAttribute(path, nativePath, name, arena.allocateFrom(newValue()))
        }
    }

    fun remove(path: Path) {
        Arena.ofConfined().use { arena ->
            val nativePath = arena.nativePath(path)
            val name = arena.allocateFrom(QUARANTINE_ATTRIBUTE)
            val result = withOwnerWrite(path) { REMOVEXATTR.invokeWithArguments(nativePath, name, XATTR_NOFOLLOW) as Int }
            // Failure because the attribute is absent is success; anything else leaves it in place.
            if (result != 0 && attributeSize(nativePath, name) >= 0)
                throw IOException("Could not remove quarantine metadata from $path")
        }
    }

    /** Quarantines [root] and everything beneath it with one shared attribute value, as Archive Utility does. */
    fun applyTree(root: Path) {
        Arena.ofConfined().use { arena ->
            val name = arena.allocateFrom(QUARANTINE_ATTRIBUTE)
            val value = arena.allocateFrom(newValue())
            Files.walk(root).use { paths ->
                for (path in paths) {
                    if (!Files.isSymbolicLink(path))
                        setAttribute(path, arena.nativePath(path), name, value)
                }
            }
        }
    }

    /** Flags, hexadecimal download time, agent name and an (absent) quarantine event identifier. */
    private fun newValue(): String = "%04x;%08x;%s;".format(QUARANTINE_FLAGS, Instant.now().epochSecond, AGENT_NAME)

    private fun attributeSize(nativePath: MemorySegment, name: MemorySegment): Long =
        GETXATTR.invokeWithArguments(nativePath, name, MemorySegment.NULL, 0L, 0, XATTR_NOFOLLOW) as Long

    private fun setAttribute(path: Path, nativePath: MemorySegment, name: MemorySegment, value: MemorySegment) {
        // The value is written without its NUL terminator, matching other quarantine writers.
        val result = withOwnerWrite(path) {
            SETXATTR.invokeWithArguments(nativePath, name, value, value.byteSize() - 1, 0, XATTR_NOFOLLOW) as Int
        }
        if (result != 0)
            throw IOException("Could not quarantine $path")
    }

    /**
     * Changing a file's extended attributes requires write permission, but archives often contain read-only files.
     * If [operation] fails on such a file, retry it with owner write permission temporarily granted.
     */
    private fun withOwnerWrite(path: Path, operation: () -> Int): Int {
        val result = operation()
        if (result == 0 || Files.isSymbolicLink(path))
            return result
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        if (PosixFilePermission.OWNER_WRITE in permissions)
            return result
        Files.setPosixFilePermissions(path, permissions + PosixFilePermission.OWNER_WRITE)
        try {
            return operation()
        } finally {
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private fun Arena.nativePath(path: Path): MemorySegment = allocateFrom(path.toAbsolutePath().toString())

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor)

    private val LINKER = Linker.nativeLinker()
    private val C_POINTER = LINKER.canonicalLayouts().getValue("void*")
    private val C_LONG = LINKER.canonicalLayouts().getValue("long")
    private val C_INT = LINKER.canonicalLayouts().getValue("int")

    private val GETXATTR = function(
        "getxattr",
        FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_POINTER, C_LONG, C_INT, C_INT)
    )
    private val SETXATTR = function(
        "setxattr",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_LONG, C_INT, C_INT)
    )
    private val REMOVEXATTR = function(
        "removexattr",
        FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT)
    )
}

private const val QUARANTINE_ATTRIBUTE = "com.apple.quarantine"
// Escaped as Launch Services writes it, since the attribute's fields are delimited by semicolons.
private const val AGENT_NAME = "Hydraulic\\x20URL"
// The flags Launch Services records for a web download.
private const val QUARANTINE_FLAGS = 0x0081
private const val XATTR_NOFOLLOW = 0x0001
