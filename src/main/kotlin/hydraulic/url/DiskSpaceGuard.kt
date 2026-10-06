package hydraulic.url

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Checks the destination's current space before each entry and each chunk written. */
internal class DiskSpaceGuard(
    private val minimumBytes: Long,
    private val usableSpace: () -> Long
) {
    constructor(minimumBytes: Long, destination: Path) : this(minimumBytes, { Files.getFileStore(destination).usableSpace })

    init {
        require(minimumBytes >= 0) { "Minimum free space must not be negative" }
    }

    fun check(pendingWrite: Long = 0) {
        if (minimumBytes > 0)
            checkFreeSpace(minimumBytes, usableSpace(), pendingWrite)
    }

    fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(count.toLong())
            output.write(buffer, 0, count)
        }
    }
}

/** A space guard refusal has no useful partial output to retain for debugging. */
internal class InsufficientDiskSpaceException(message: String) : IllegalStateException(message)
