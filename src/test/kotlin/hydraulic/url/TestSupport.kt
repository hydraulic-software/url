package hydraulic.url

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hydraulic.diskcache.LocalDiskCache
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories

internal fun makeCache(directory: Path): LocalDiskCache {
    val config = LocalDiskCache.Configuration().apply {
        minFreeDiskSpace = 0
        maxSize = 100 * 1024 * 1024
    }
    return LocalDiskCache(directory.createDirectories(), config).open()
}

internal fun withServer(block: (TestServer) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.start()
    try {
        block(TestServer(server))
    } finally {
        server.stop(0)
    }
}

internal class TestServer(private val server: HttpServer) {
    fun createContext(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path, handler)
    fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}$path")
}

internal fun zipOf(vararg files: Pair<String, String>): ByteArray =
    zipOfBytes(*files.map { (name, contents) -> name to contents.toByteArray() }.toTypedArray())

internal fun zipOfBytes(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
    ZipOutputStream(bytes).use { zip ->
        for ((name, contents) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(contents)
            zip.closeEntry()
        }
    }
    bytes.toByteArray()
}

internal fun tarGzOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
    GzipCompressorOutputStream(bytes).use { gzip ->
        TarArchiveOutputStream(gzip).use { tar ->
            for ((name, contents) in files) {
                val data = contents.toByteArray()
                val entry = TarArchiveEntry(name).apply { size = data.size.toLong(); mode = 0b110_100_100 }
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
    }
    bytes.toByteArray()
}

