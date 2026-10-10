package hydraulic.url

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

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


internal fun requireSha256(expected: String, actual: String, what: String) {
    require(actual.equals(expected, ignoreCase = true)) {
        "SHA-256 mismatch: expected $expected but resolved $what"
    }
}
