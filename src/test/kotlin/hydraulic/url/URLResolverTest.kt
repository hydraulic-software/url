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
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class URLResolverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `default cache directory uses the application namespace`() {
        assertEquals(OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache, URL().cacheDirectory)
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
    fun `HTTP requests identify the URL tool`() = withServer { server ->
        var userAgent: String? = null
        server.createContext("/") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.respond("contents".toByteArray())
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/user-agent")).close()
        }

        assertEquals(USER_AGENT, userAgent)
    }

    @Test
    fun `recognized executable content gains execute bits without losing permissions`() {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView::class.java) == null)
            return
        val prefixes = listOf(
            byteArrayOf(0x23, 0x21),
            bytes(0x7f454c46),
            bytes(0xfeedface.toInt()), bytes(0xcefaedfe.toInt()),
            bytes(0xfeedfacf.toInt()), bytes(0xcffaedfe.toInt()),
            bytes(0xcafebabe.toInt()) + bytes(2),
            bytes(0xbebafeca.toInt()) + bytes(Integer.reverseBytes(2)),
            bytes(0xcafebabf.toInt()) + bytes(2),
            bytes(0xbfbafeca.toInt()) + bytes(Integer.reverseBytes(2))
        )
        val initial = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ
        )
        val expected = initial + setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE
        )

        prefixes.forEachIndexed { index, prefix ->
            val file = tempDir / "executable-$index"
            file.writeBytes(prefix + " payload".toByteArray())
            Files.setPosixFilePermissions(file, initial)

            file.makeExecutableIfRecognized()

            assertEquals(expected, Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun `ordinary data does not gain execute permissions`() {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView::class.java) == null)
            return
        val file = tempDir / "data.txt"
        file.writeBytes("ordinary data".toByteArray())
        val initial = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(file, initial)

        file.makeExecutableIfRecognized()

        assertEquals(initial, Files.getPosixFilePermissions(file))
    }

    @Test
    fun `Java class magic is not mistaken for fat Mach-O`() {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView::class.java) == null)
            return
        val file = tempDir / "Example.class"
        file.writeBytes(bytes(0xcafebabe.toInt()) + byteArrayOf(0, 0, 0, 65))
        val initial = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(file, initial)

        file.makeExecutableIfRecognized()

        assertEquals(initial, Files.getPosixFilePermissions(file))
    }

    @Test
    fun `quarantine provenance has the macOS download format`() {
        assertEquals(
            "0081;5f5e100;Hydraulic URL;12345678-1234-1234-1234-123456789abc",
            gatekeeperQuarantineValue(
                Instant.ofEpochSecond(100_000_000),
                UUID.fromString("12345678-1234-1234-1234-123456789abc")
            )
        )
    }

    @Test
    fun `macOS Mach-O results receive quarantine unless opted out`() = withServer { server ->
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return@withServer
        server.createContext("/") { it.respond(bytes(0xfeedfacf.toInt()) + " payload".toByteArray()) }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/enabled")).use { resolved ->
                assertContains(resolved.path.userAttribute("com.apple.quarantine"), ";Hydraulic URL;")
            }
            URLResolver(cache, gatekeeper = false).resolve(server.uri("/disabled")).use { resolved ->
                val attributes = Files.getFileAttributeView(resolved.path, UserDefinedFileAttributeView::class.java)
                assertFalse("com.apple.quarantine" in attributes.list())
            }
        }
    }

    @Test
    fun `resolved hashbang script is executable on POSIX systems`() = withServer { server ->
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView::class.java) == null)
            return@withServer
        server.createContext("/") { it.respond("#!/bin/sh\necho hello\n".toByteArray()) }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/script")).use { resolved ->
                assertTrue(Files.isExecutable(resolved.path))
            }
        }
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
    fun `command line distinguishes usage errors from execution failures`() {
        assertEquals(2, commandLine().execute("--does-not-exist"))
        assertEquals(1, commandLine().execute("--print0", "--print-separator=:"))
        assertEquals(0, commandLine().execute("--help"))
    }

    @Test
    fun `progress modes write only to their selected stderr stream`() {
        val plainBytes = ByteArrayOutputStream()
        val plain = progressTracker("plain", PrintStream(plainBytes), emptyMap()) { false }!!
        plain.report(dev.progress4j.api.ProgressReport.create("Downloading", 10, 5, dev.progress4j.api.ProgressReport.Units.BYTES))
        (plain as AutoCloseable).close()
        assertTrue(plainBytes.toString().contains("Downloading"))

        val jsonBytes = ByteArrayOutputStream()
        val json = progressTracker("json", PrintStream(jsonBytes), emptyMap()) { false }!!
        json.report(dev.progress4j.api.ProgressReport.create("Downloading", 10, 5, dev.progress4j.api.ProgressReport.Units.BYTES))
        (json as AutoCloseable).close()
        assertTrue(jsonBytes.toString().contains("\"type\": \"progress\""))
        assertEquals(null, progressTracker("never", System.err, emptyMap()) { true })
    }

    @Test
    fun `automatic progress is quiet for redirected and dumb terminals`() {
        assertEquals(null, progressTracker("term", System.err, emptyMap()) { false })
        var terminalWasInspected = false
        assertEquals(null, progressTracker("term", System.err, mapOf("TERM" to "dumb")) {
            terminalWasInspected = true
            true
        })
        assertFalse(terminalWasInspected)
    }


    @Test
    fun `invalid progress mode is a concise command line error`() {
        val stderr = StringWriter()
        val exitCode = commandLine().apply { err = PrintWriter(stderr) }.execute(
            "--progress", "loud", "https://example.com/file"
        )

        assertEquals(1, exitCode)
        assertContains(stderr.toString(), "--progress must be one of")
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

    private fun bytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun Path.userAttribute(name: String): String {
        val attributes = Files.getFileAttributeView(this, UserDefinedFileAttributeView::class.java)
        val buffer = ByteBuffer.allocate(attributes.size(name))
        attributes.read(name, buffer)
        buffer.flip()
        return StandardCharsets.UTF_8.decode(buffer).toString()
    }
}
