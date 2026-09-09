package hydraulic.url

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hydraulic.diskcache.LocalDiskCache
import hydraulic.archives.extractLocalArchive
import hydraulic.utils.os.OperatingSystemPaths
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class URLResolverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `default cache directory has no redundant cache component`() {
        assertEquals(OperatingSystemPaths.current(null, "url-tool").localCache.parent, URL().cacheDirectory)
    }

    @Test
    fun `missing URL scheme infers HTTPS`() {
        assertEquals(URI("https://example.com:8443/path?q=1"), parseURL("example.com:8443/path?q=1"))
        assertEquals(URI("http://example.com/path"), parseURL("http://example.com/path"))
        assertEquals(URI("file:///tmp/local"), parseURL("file:///tmp/local"))
        assertEquals(URI("https://example.com/?next=http://other.example"), parseURL("example.com/?next=http://other.example"))
    }

    @Test
    fun `stdin URL lists ignore comments and blank lines`() {
        val input = StringReader("""
            # Download inputs
              example.com/one

            https://example.com/two
              # another comment
        """.trimIndent()).buffered()

        assertEquals(listOf("example.com/one", "https://example.com/two"), readURLsFromStdin(input))
    }

    @Test
    fun `archive URL parsing prefers compound suffixes`() {
        val parsed = parseArchiveURL(URI("https://example.com/releases/tool.tar.gz/bin/tool"))!!
        assertEquals(URI("https://example.com/releases/tool.tar.gz"), parsed.archiveURI)
        assertEquals(listOf("bin", "tool"), parsed.member)
    }

    @Test
    fun `extracted archive cache keys are human readable documents`() {
        val archive = tempDir / "tool.zip"
        archive.writeBytes("contents".toByteArray())
        assertTrue(extractedArchiveCacheKey(archive).matches(Regex("Extracted archive\\nFile name: tool\\.zip\\nSHA-256: [a-z0-9]+")))
    }

    @Test
    fun `archive URL parsing preserves encoded download path and query`() {
        val parsed = parseArchiveURL(URI("https://example.com/releases/%74ool.zip/a%20file?signature=a%2Fb"))!!
        assertEquals("https://example.com/releases/%74ool.zip?signature=a%2Fb", parsed.archiveURI.toASCIIString())
        assertEquals(listOf("a file"), parsed.member)
    }

    @Test
    fun `encoded path separators cannot create archive member components`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseArchiveURL(URI("https://example.com/tool.zip/a%2Fb"))
        }
    }

    @Test
    fun `404 composite URL falls back to a member of the archive`() = withServer { server ->
        val requests = AtomicInteger()
        val zip = zipOf("tool-1.0/bin/tool" to "archive member")
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            if (exchange.requestURI.path == "/tool.zip")
                exchange.respond(zip)
            else
                exchange.respond404()
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/tool.zip/bin/tool")).use { resolved ->
                assertEquals("archive member", resolved.path.readText())
            }
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun `trailing slash resolves to archive root with a single wrapper removed`() = withServer { server ->
        val requestedPaths = mutableListOf<String>()
        val zip = zipOf("tool-1.0/bin/tool" to "archive member")
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.path
            if (exchange.requestURI.path == "/tool.zip")
                exchange.respond(zip)
            else
                exchange.respond("ordinary response".toByteArray())
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/tool.zip/")).use { resolved ->
                assertTrue(resolved.path.isDirectory())
                assertEquals("archive member", (resolved.path / "bin/tool").readText())
            }
        }
        assertEquals(listOf("/tool.zip"), requestedPaths)
    }

    @Test
    fun `existing composite-looking URL is used without archive fallback`() = withServer { server ->
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.respond("ordinary response".toByteArray())
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/tool.zip/bin/tool")).use { resolved ->
                assertEquals("ordinary response", resolved.path.readText())
            }
        }
        assertEquals(1, requests.get())
    }

    @Test
    fun `nested archive members resolve recursively`() = withServer { server ->
        val requests = mutableListOf<String>()
        val innerZip = zipOf("inner/file.txt" to "nested member")
        val outerZip = zipOfBytes("outer/dir/inner.zip" to innerZip)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            if (exchange.requestURI.path == "/outer.zip")
                exchange.respond(outerZip)
            else
                exchange.respond404()
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/outer.zip/dir/inner.zip/file.txt")).use { resolved ->
                assertEquals("nested member", resolved.path.readText())
            }
        }
        assertEquals(
            listOf("/outer.zip/dir/inner.zip/file.txt", "/outer.zip/dir/inner.zip", "/outer.zip"),
            requests
        )
    }

    @Test
    fun `SHA-256 fragment locks the final resolved file`() = withServer { server ->
        val contents = "locked contents".toByteArray()
        val requestTargets = mutableListOf<String>()
        server.createContext("/") { exchange ->
            requestTargets += exchange.requestURI.toASCIIString()
            exchange.respond(contents)
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/locked#sha256=${contents.sha256()}")).use { resolved ->
                assertEquals("locked contents", resolved.path.readText())
            }
            val exception = assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/locked#sha256=${"0".repeat(64)}"))
            }
            assertTrue(exception.message!!.startsWith("SHA-256 mismatch"))
        }
        assertEquals(listOf("/locked"), requestTargets)
    }

    @Test
    fun `SHA-256 lock requires a complete hexadecimal digest`() {
        makeCache().use { cache ->
            val exception = assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(URI("https://example.com/file#sha256=1234"))
            }
            assertEquals("Invalid SHA-256 lock: expected 64 hexadecimal characters", exception.message)
        }
    }

    @Test
    fun `local extraction does not write traversal entries outside its destination`() {
        val archive = tempDir / "traversal.zip"
        archive.writeBytes(zipOf("../escaped" to "bad"))

        assertFailsWith<IllegalArgumentException> {
            extractLocalArchive(archive, (tempDir / "extracted").createDirectories(), skipSingleRoot = false)
        }

        assertFalse((tempDir / "escaped").toFile().exists())
    }

    @Test
    fun `resolver rejects a selected archive symlink that escapes the cache entry`() = withServer { server ->
        val zip = unixZipOf("root/link", "../../escaped", UnixStat.LINK_FLAG or 0b111_101_101)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/symlink.zip")
                exchange.respond(zip)
            else
                exchange.respond404()
        }

        makeCache().use { cache ->
            val exception = assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/symlink.zip/link"))
            }
            assertTrue(exception.message!!.contains("escapes its extraction root"))
        }
    }

    @Test
    fun `local extraction creates an archive symlink without a pre-existing target`() {
        val archive = tempDir / "symlink.zip"
        archive.writeBytes(unixZipOf("root/bin/java", "../lib/jvm", UnixStat.LINK_FLAG or 0b111_101_101))
        val destination = (tempDir / "extracted").createDirectories()

        extractLocalArchive(archive, destination, skipSingleRoot = true)

        val link = destination / "bin/java"
        assertTrue(link.isSymbolicLink())
        assertEquals(Path.of("../lib/jvm"), Files.readSymbolicLink(link))
    }

    @Test
    fun `command line reports execution failures without a stack trace`() {
        val stderr = StringWriter()
        val exitCode = commandLine().apply { err = PrintWriter(stderr) }.execute(
            "--cache-dir", (tempDir / "cli-cache").toString(), "file:///tmp/not-http"
        )

        assertEquals(1, exitCode)
        assertEquals("url: Not an HTTP(S) URI: file:///tmp/not-http\n", stderr.toString())
    }

    @Test
    fun `local extraction preserves executable permissions`() {
        val archive = tempDir / "executable.zip"
        archive.writeBytes(unixZipOf("root/bin/tool", "contents", UnixStat.FILE_FLAG or 0b111_101_101))
        val destination = (tempDir / "extracted").createDirectories()

        extractLocalArchive(archive, destination, skipSingleRoot = true)

        assertEquals("contents", (destination / "bin/tool").readText())
        assertTrue(Files.isExecutable(destination / "bin/tool"))
    }

    private fun makeCache(): LocalDiskCache {
        val config = LocalDiskCache.Configuration().apply {
            minFreeDiskSpace = 0
            maxSize = 100 * 1024 * 1024
        }
        return LocalDiskCache((tempDir / "cache").createDirectories(), config).open()
    }

    private fun withServer(block: (TestServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(TestServer(server))
        } finally {
            server.stop(0)
        }
    }

    private class TestServer(private val server: HttpServer) {
        fun createContext(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path, handler)
        fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}$path")
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray =
        zipOfBytes(*files.map { (name, contents) -> name to contents.toByteArray() }.toTypedArray())

    private fun zipOfBytes(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, contents) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun unixZipOf(name: String, contents: String, mode: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipArchiveOutputStream(bytes).use { zip ->
            val entry = ZipArchiveEntry(name).apply { unixMode = mode }
            zip.putArchiveEntry(entry)
            zip.write(contents.toByteArray())
            zip.closeArchiveEntry()
        }
        bytes.toByteArray()
    }

    private fun HttpExchange.respond(bytes: ByteArray) {
        responseHeaders.add("Cache-Control", "max-age=3600")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respond404() {
        sendResponseHeaders(404, -1)
        close()
    }

    private fun ByteArray.sha256(): String {
        val file = tempDir / "hash-input-${hashCode()}"
        file.writeBytes(this)
        return file.sha256()
    }
}
