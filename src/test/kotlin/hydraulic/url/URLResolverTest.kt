package hydraulic.url

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.airlift.compress.v3.zstd.ZstdOutputStream
import hydraulic.diskcache.LocalDiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpTransport
import hydraulic.archives.extractLocalArchive
import hydraulic.utils.os.OperatingSystemPaths
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.io.ByteArrayInputStream
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
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
    fun `download policy defaults to 100 MB and accepts flag and environment overrides`() {
        assertEquals(100_000_000, DownloadPolicy().minimumFreeSpaceBytes(emptyMap()))
        assertEquals(25_000_000, DownloadPolicy().minimumFreeSpaceBytes(mapOf(MINIMUM_FREE_SPACE_ENV to "25")))
        val policy = DownloadPolicy().apply { minimumFreeSpaceMB = 0 }
        assertEquals(0, policy.minimumFreeSpaceBytes(mapOf(MINIMUM_FREE_SPACE_ENV to "25")))
        assertEquals(250_000_000, DownloadPolicy().apply { legacyMinimumFreeSpaceGB = 0.25 }.minimumFreeSpaceBytes(emptyMap()))
    }

    @Test
    fun `low space refuses a new response body but permits a not-modified response`() {
        val closed = AtomicBoolean()
        val body = object : ByteArrayInputStream("payload".toByteArray()) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }
        val lowSpace = MinimumFreeSpaceHttpTransport(
            HttpTransport { _, _ -> HttpTransport.Response(200, emptyMap(), body) },
            minimumBytes = 100,
            usableSpace = { 99 }
        )
        val failure = assertFailsWith<IllegalStateException> { lowSpace.get(URI("https://example.com/file"), emptyMap()) }
        assertContains(failure.message!!, "--min-free-space=0")
        assertTrue(closed.get())

        val notModified = MinimumFreeSpaceHttpTransport(
            HttpTransport { _, _ -> HttpTransport.Response(304, emptyMap(), ByteArrayInputStream(byteArrayOf())) },
            minimumBytes = 100,
            usableSpace = { 0 }
        ).get(URI("https://example.com/file"), emptyMap())
        assertEquals(304, notModified.statusCode)
        notModified.body.close()
    }

    @Test
    fun `multiple CLI URLs resolve concurrently but print in input order`() {
        val bothStarted = CountDownLatch(2)
        val overlapped = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        Executors.newFixedThreadPool(2).use { serverExecutor ->
            server.executor = serverExecutor
            server.createContext("/") { exchange ->
                bothStarted.countDown()
                if (bothStarted.await(5, TimeUnit.SECONDS))
                    overlapped.set(true)
                val slow = exchange.requestURI.path == "/slow"
                if (slow)
                    Thread.sleep(150)
                exchange.respond((if (slow) "slow" else "fast").toByteArray())
            }
            server.start()
            try {
                val stdout = ByteArrayOutputStream()
                val command = CommandLine(URL(PrintStream(stdout)))
                val exitCode = command.execute(
                    "--cache-dir", (tempDir / "parallel-cache").toString(),
                    "--progress=never",
                    "http://127.0.0.1:${server.address.port}/slow",
                    "http://127.0.0.1:${server.address.port}/fast"
                )

                assertEquals(0, exitCode)
                assertTrue(overlapped.get(), "Both HTTP requests should be active together")
                assertEquals(listOf("slow", "fast"), stdout.toString().lineSequence().filter(String::isNotBlank).map {
                    Path.of(it).readText()
                }.toList())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `stdin URLs share parallel resolution and ordered output`() {
        val bothStarted = CountDownLatch(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        Executors.newFixedThreadPool(2).use { serverExecutor ->
            server.executor = serverExecutor
            server.createContext("/") { exchange ->
                bothStarted.countDown()
                check(bothStarted.await(5, TimeUnit.SECONDS)) { "Requests did not overlap" }
                val value = exchange.requestURI.path.removePrefix("/")
                exchange.respond(value.toByteArray())
            }
            server.start()
            try {
                val stdout = ByteArrayOutputStream()
                val input = """
                    # Resolve together
                    http://127.0.0.1:${server.address.port}/first
                    http://127.0.0.1:${server.address.port}/second
                """.trimIndent().reader().buffered()
                val exitCode = CommandLine(URL(PrintStream(stdout), input)).execute(
                    "--cache-dir", (tempDir / "parallel-stdin-cache").toString(),
                    "--progress=never"
                )

                assertEquals(0, exitCode)
                assertEquals(listOf("first", "second"), stdout.toString().lineSequence().filter(String::isNotBlank).map {
                    Path.of(it).readText()
                }.toList())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `parallel progress uses stable progress4j subreports`() {
        val reports = mutableListOf<dev.progress4j.api.ProgressReport>()
        val progress = ParallelURLProgress(listOf("first", "second")) { reports += it }

        progress.tracker(1)!!.report(dev.progress4j.api.ProgressReport.create("Downloading", 20, 5))
        progress.tracker(0)!!.report(dev.progress4j.api.ProgressReport.create("Downloading", 10, 10))
        progress.complete(0)
        progress.complete(1)

        val final = reports.last()
        assertEquals("Resolving URLs", final.message)
        assertEquals(2, final.expectedTotal)
        assertEquals(2, final.completed)
        assertEquals(listOf("first", "second"), final.subReports.map { it!!.message })
    }

    @Test
    fun `parallel mapping is bounded and retains input order`() {
        val firstWave = CountDownLatch(3)
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        val results = parallelMapOrdered((0 until 12).toList(), maxParallelism = 3) { _, value ->
            val nowActive = active.incrementAndGet()
            maximum.accumulateAndGet(nowActive, ::maxOf)
            firstWave.countDown()
            assertTrue(firstWave.await(5, TimeUnit.SECONDS), "Three workers should start together")
            Thread.sleep(10)
            active.decrementAndGet()
            value * 2
        }

        assertEquals((0 until 24 step 2).toList(), results)
        assertEquals(3, maximum.get())
    }

    @Test
    fun `parallel mapping cancels sibling work after a failure`() {
        val blockerStarted = CountDownLatch(1)
        val blockerInterrupted = AtomicBoolean()

        val failure = assertFailsWith<IllegalStateException> {
            parallelMapOrdered(listOf("block", "fail"), maxParallelism = 2) { _, value ->
                if (value == "fail") {
                    assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))
                    error("expected failure")
                }
                blockerStarted.countDown()
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30))
                } catch (_: InterruptedException) {
                    blockerInterrupted.set(true)
                }
                value
            }
        }

        assertEquals("expected failure", failure.message)
        assertTrue(blockerInterrupted.get(), "The outstanding worker should be interrupted before returning")
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
    fun `remote tarball streams directly into extracted cache`() {
        val tarball = tarGzOf("tool-1.0/bin/tool" to "streamed member")
        val requests = mutableListOf<URI>()
        val transport = HttpTransport { uri, _ ->
            requests += uri
            if (uri.path.endsWith("tool.tar.gz"))
                HttpTransport.Response(
                    200,
                    mapOf("Cache-Control" to listOf("max-age=3600")),
                    ByteArrayInputStream(tarball)
                )
            else
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }

        makeCache().use { cache ->
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.gz/bin/tool")).use {
                assertEquals("streamed member", it.path.readText())
            }
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.gz/bin/tool")).close()
            assertFalse(cache.has(HttpResourceCache.cacheKey(URI("https://example.com/tool.tar.gz"))))
        }
        assertEquals(listOf("/tool.tar.gz/bin/tool", "/tool.tar.gz", "/tool.tar.gz/bin/tool"), requests.map { it.path })
    }

    @Test
    fun `cached tarball is extracted locally instead of fetched again`() {
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val tarball = tarGzOf("tool-1.0/bin/tool" to "cached member")
        makeCache().use { cache ->
            HttpResourceCache(cache, HttpTransport { _, _ ->
                HttpTransport.Response(200, mapOf("Cache-Control" to listOf("max-age=3600")), ByteArrayInputStream(tarball))
            }).resolve(archiveURI).close()
            val transport = HttpTransport { uri, _ ->
                check(uri.path != "/tool.tar.gz") { "Cached archive must not be fetched" }
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }

            URLResolver(cache, transport = transport).resolve(URI("$archiveURI/bin/tool")).use {
                assertEquals("cached member", it.path.readText())
            }
        }
    }

    @Test
    fun `zstandard tarball streams into extracted cache`() {
        val tarball = tarZstdOf("tool/member" to "zstandard member")
        val transport = HttpTransport { uri, _ ->
            if (uri.path.endsWith("tool.tar.zst"))
                HttpTransport.Response(200, emptyMap(), ByteArrayInputStream(tarball))
            else
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }

        makeCache().use { cache ->
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.zst/member")).use {
                assertEquals("zstandard member", it.path.readText())
            }
            assertFalse(cache.has(HttpResourceCache.cacheKey(URI("https://example.com/tool.tar.zst"))))
        }
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
    fun `hashbang cache policy overrides origin freshness and survives revalidation`() = withServer { server ->
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            val request = requests.incrementAndGet()
            exchange.responseHeaders.add("Cache-Control", "max-age=3600")
            exchange.responseHeaders.add("ETag", "\"version-1\"")
            if (request == 1) {
                val body = "#!/bin/sh\n# Cache-Control: no-cache\necho hello\n".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                assertEquals("\"version-1\"", exchange.requestHeaders.getFirst("If-None-Match"))
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
            }
        }

        makeCache().use { cache ->
            val resolver = URLResolver(cache)
            repeat(3) { resolver.resolve(server.uri("/script")).close() }
        }

        assertEquals(3, requests.get())
    }

    @Test
    fun `refresh forces a cache miss and replaces the cached response`() = withServer { server ->
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            val request = requests.incrementAndGet()
            assertEquals(null, exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.responseHeaders.add("ETag", "\"response-$request\"")
            exchange.respond("response $request".toByteArray())
        }

        makeCache().use { cache ->
            val uri = server.uri("/refresh")
            URLResolver(cache).resolve(uri).use { assertEquals("response 1", it.path.readText()) }
            URLResolver(cache).resolve(uri).use { assertEquals("response 1", it.path.readText()) }
            URLResolver(cache, refresh = true).resolve(uri).use { assertEquals("response 2", it.path.readText()) }
            URLResolver(cache).resolve(uri).use { assertEquals("response 2", it.path.readText()) }
        }

        assertEquals(2, requests.get())
    }

    @Test
    fun `cache policy comment must immediately follow the hashbang`() {
        val accepted = tempDir / "accepted"
        accepted.writeText("#!/usr/bin/env kotlin\n# cache-control: max-age=60\nprintln(1)\n")
        assertEquals("max-age=60", accepted.hashbangCachePolicy())

        val slashComment = tempDir / "slash-comment"
        slashComment.writeText("#!/usr/bin/env kotlin\n// Cache-Control: no-cache\nprintln(1)\n")
        assertEquals("no-cache", slashComment.hashbangCachePolicy())

        val tooLate = tempDir / "too-late"
        tooLate.writeText("#!/bin/sh\n# description\n# Cache-Control: no-cache\n")
        assertEquals(null, tooLate.hashbangCachePolicy())

        val binary = tempDir / "binary"
        binary.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0xff.toByte()))
        assertEquals(null, binary.hashbangCachePolicy())
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
    fun `zsh setup is bounded idempotent and preserves user configuration`() {
        val zshrc = tempDir / ".zshrc"
        zshrc.writeText("export USER_SETTING=kept\n")

        assertTrue(installZshIntegration(zshrc))
        val installed = zshrc.readText()
        assertContains(installed, "export USER_SETTING=kept")
        assertEquals(1, installed.split(ZSH_SETUP_BEGIN).size - 1)
        assertFalse(installZshIntegration(zshrc))
        assertEquals(installed, zshrc.readText())
    }

    @Test
    fun `installed zsh integration resolves and executes URL commands`() {
        val zsh = listOf(Path.of("/bin/zsh"), Path.of("/usr/bin/zsh")).firstOrNull(Files::isExecutable) ?: return
        val zshrc = tempDir / ".zshrc"
        installZshIntegration(zshrc)
        val bin = (tempDir / "bin").createDirectories()
        val fakeRun = bin / "run"
        fakeRun.writeText("#!/bin/sh\nprintf 'executed:%s:%s:%s\\n' \"${'$'}1\" \"${'$'}2\" \"${'$'}3\"\n")
        fakeRun.toFile().setExecutable(true)
        val command = """
            source ${(zshrc.toString())}
            function zle() { :; }
            BUFFER='https://example.test/tool alpha beta'
            _hydraulic_url_accept_line
            eval "${'$'}BUFFER"
        """.trimIndent()
        val process = ProcessBuilder(zsh.toString(), "-dfc", command)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin}:${System.getenv("PATH")}" }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertContains(output, "executed:https://example.test/tool:alpha:beta")
    }

    @Test
    fun `run directory convention preserves URL suffixes and selects platform script`() {
        assertEquals(URI("https://example.com/run.zip/run.sh"), runTargetURI(URI("https://example.com"), false))
        assertEquals(
            URI("https://example.com/tools/run.zip/run.sh?channel=beta#sha256=abc"),
            runTargetURI(URI("https://example.com/tools/?channel=beta#sha256=abc"), false)
        )
        assertEquals(URI("https://example.com/tools/run.zip/run.ps1"), runTargetURI(URI("https://example.com/tools/"), true))
        assertEquals(URI("https://example.com/tool.sh"), runTargetURI(URI("https://example.com/tool.sh"), false))
    }

    @Test
    fun `run installation creates an idempotent sibling hard link`() {
        val run = tempDir / "run"
        val url = tempDir / "url"
        run.writeText("binary")

        assertTrue(ensureHardLink(run, url))
        assertTrue(Files.isSameFile(run, url))
        assertFalse(ensureHardLink(run, url))

        Files.delete(url)
        url.writeText("binary")
        assertTrue(ensureHardLink(run, url))
        assertTrue(Files.isSameFile(run, url))
    }

    @Test
    fun `run mode keeps options after URL as child arguments`() {
        val parsed = Run(executablePath = { tempDir / "run" }, windows = false)
        CommandLine(parsed).setStopAtPositional(true).parseArgs("https://example.com", "--help", "value")

        assertEquals("https://example.com", parsed.url)
        assertEquals(listOf("--help", "value"), parsed.arguments)
    }

    @Test
    fun `refresh has short and long command line forms`() {
        val url = URL()
        CommandLine(url).parseArgs("-r", "https://example.com")
        assertTrue(url.refresh)

        val run = Run(executablePath = { tempDir / "run" }, windows = false)
        CommandLine(run).setStopAtPositional(true).parseArgs("--refresh", "https://example.com")
        assertTrue(run.refresh)
    }

    @Test
    fun `resolved Unix script receives all arguments`() {
        val output = tempDir / "arguments"
        val script = tempDir / "tool.sh"
        script.writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\" > '${output}'\n")
        script.toFile().setExecutable(true)

        assertEquals(0, runResolvedPath(script, listOf("--help", "two words"), false))
        assertEquals("--help\ntwo words\n", output.readText())
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
    fun `stderr interactivity probe is safe when native terminal detection is unavailable`() {
        assertFalse(runCatching { isStderrInteractive() }.isFailure)
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

    private fun tarGzOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
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

    private fun tarZstdOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
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
