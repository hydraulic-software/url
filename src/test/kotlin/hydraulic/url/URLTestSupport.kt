package hydraulic.url

import kotlin.io.path.div
import com.sun.net.httpserver.HttpExchange
import io.airlift.compress.v3.zstd.ZstdOutputStream
import hydraulic.diskcache.LocalDiskCache
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.HexFormat
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals

abstract class URLTestSupport {
    @TempDir
    lateinit var tempDir: Path

    protected fun makeCache(): LocalDiskCache = makeCache(tempDir / "cache")

    protected fun unixZipOf(name: String, contents: String, mode: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipArchiveOutputStream(bytes).use { zip ->
            val entry = ZipArchiveEntry(name).apply { unixMode = mode }
            zip.putArchiveEntry(entry)
            zip.write(contents.toByteArray())
            zip.closeArchiveEntry()
        }
        bytes.toByteArray()
    }

    protected fun maliciousSymlinkTarGz(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        GzipCompressorOutputStream(bytes).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                for ((name, target) in listOf("a" to ".", "a/link" to "..")) {
                    tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
                    tar.closeArchiveEntry()
                }
                val contents = "escaped".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("a/link/payload").apply { size = contents.size.toLong() })
                tar.write(contents)
                tar.closeArchiveEntry()
                for ((name, data) in files) {
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        bytes.toByteArray()
    }

    protected fun tarGzWithLinkedChild(): ByteArray = ByteArrayOutputStream().use { bytes ->
        GzipCompressorOutputStream(bytes).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("a/b", TarConstants.LF_SYMLINK).apply { linkName = "../d" })
                tar.closeArchiveEntry()
                val contents = "payload".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("a/b/c").apply { size = contents.size.toLong() })
                tar.write(contents)
                tar.closeArchiveEntry()
            }
        }
        bytes.toByteArray()
    }

    protected fun tarZstdOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZstdOutputStream(bytes).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                for ((name, contents) in files) {
                    val data = contents.toByteArray()
                    val entry = TarArchiveEntry(name).apply { size = data.size.toLong() }
                    tar.putArchiveEntry(entry)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        bytes.toByteArray()
    }

    protected fun HttpExchange.respond(bytes: ByteArray) {
        responseHeaders.add("Cache-Control", "max-age=3600")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    protected fun HttpExchange.respond404() {
        sendResponseHeaders(404, -1)
        close()
    }

    protected fun ByteArray.sha256(): String {
        val file = tempDir / "hash-input-${hashCode()}"
        file.writeBytes(this)
        return file.sha256()
    }

    protected fun bytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    protected fun Path.macExtendedAttributes(): Set<String> =
        xattr(toString()).lineSequence().filter(String::isNotEmpty).toSet()

    protected fun Path.macExtendedAttribute(name: String): String {
        val hex = xattr("-px", name, toString()).filterNot(Char::isWhitespace)
        return HexFormat.of().parseHex(hex).toString(StandardCharsets.UTF_8)
    }

    protected fun xattr(vararg arguments: String): String {
        val process = ProcessBuilder("/usr/bin/xattr", *arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
