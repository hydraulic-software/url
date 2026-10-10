package hydraulic.url

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.airlift.compress.v3.zstd.ZstdOutputStream
import hydraulic.diskcache.LocalDiskCache
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpTransport
import hydraulic.utils.os.OperatingSystemPaths
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Collections
import java.util.HexFormat
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
import kotlin.test.assertNotNull
import kotlin.test.assertSame
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
    fun `URL inputs recognize portable shell variable assignments`() {
        assertEquals(URLInput("https://example.com/jdk", "jdk"), parseURLInput("jdk=https://example.com/jdk"))
        assertEquals(URLInput("example.com/file"), parseURLInput("example.com/file"))
        assertEquals(URLInput("https://example.com/?name=value"), parseURLInput("https://example.com/?name=value"))
        assertEquals(URLInput("example.com/file=name"), parseURLInput("example.com/file=name"))
        assertEquals(URLInput("bad-name=example.com/file"), parseURLInput("bad-name=example.com/file"))
        assertFailsWith<IllegalArgumentException> { parseURLInput("jdk=") }
    }

    @Test
    fun `shell assignments quote paths for safe eval`() {
        assertEquals("jdk='/tmp/has spaces/it'\"'\"'s here'", posixShellAssignment("jdk", "/tmp/has spaces/it's here"))
        assertFailsWith<IllegalArgumentException> { posixShellAssignment("bad-name", "/tmp/file") }
    }

    @Test
    fun `named URL inputs emit ordered assignments after parallel resolution`() = withServer { server ->
        server.createContext("/jdk") { it.respond("jdk".toByteArray()) }
        server.createContext("/jar") { it.respond("jar".toByteArray()) }
        val output = ByteArrayOutputStream()
        val command = URL(stdout = PrintStream(output), environment = emptyMap()).apply {
            urls = listOf("jdk=${server.uri("/jdk")}", "jar=${server.uri("/jar")}")
            cacheDirectory = (tempDir / "assignment-cache").createDirectories()
            progress = "never"
            downloadPolicy = DownloadPolicy().apply { minimumFreeSpaceMB = 0 }
        }

        assertEquals(0, command.call())

        val assignments = output.toString().lines().filter(String::isNotEmpty)
        assertEquals(2, assignments.size)
        assertTrue(assignments[0].startsWith("jdk='"))
        assertTrue(assignments[1].startsWith("jar='"))
        assertEquals("jdk", Path.of(assignments[0].substringAfter("='").dropLast(1)).readText())
        assertEquals("jar", Path.of(assignments[1].substringAfter("='").dropLast(1)).readText())
    }

    @Test
    fun `named URL inputs reject mixed output modes before resolving`() {
        val mixed = URL().apply { urls = listOf("jdk=example.com/jdk", "example.com/jar") }
        assertFailsWith<IllegalArgumentException> { mixed.call() }

        val customSeparator = URL().apply {
            urls = listOf("jdk=example.com/jdk")
            print0 = true
        }
        assertFailsWith<IllegalArgumentException> { customSeparator.call() }
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
    fun `parallel progress aggregates child sizes into the parent report`() {
        val reports = mutableListOf<dev.progress4j.api.ProgressReport>()
        val progress = ParallelURLProgress(
            listOf("https://example.com/releases/tool.zip/bin/tool", "http://example.com/second")
        ) { reports += it }

        progress.tracker(1)!!.report(dev.progress4j.api.ProgressReport.create(
            "Downloading", 20, 5, dev.progress4j.api.ProgressReport.Units.BYTES
        ))
        progress.tracker(0)!!.report(dev.progress4j.api.ProgressReport.create(
            "Downloading", 10, 10, dev.progress4j.api.ProgressReport.Units.BYTES
        ))

        val downloading = reports.last()
        assertEquals(30, downloading.expectedTotal)
        assertEquals(15, downloading.completed)
        assertEquals(dev.progress4j.api.ProgressReport.Units.BYTES, downloading.units)
        assertEquals(listOf("tool.zip/bin/tool", "second"), downloading.subReports.map { it!!.message })

        progress.complete(0)
        progress.complete(1)

        val final = reports.last()
        assertEquals("Resolving URLs", final.message)
        assertEquals(30, final.expectedTotal)
        assertEquals(30, final.completed)
        assertEquals(listOf("tool.zip/bin/tool", "second"), final.subReports.map { it!!.message })
    }

    @Test
    fun `single URL progress is reported directly without subreports`() {
        val reports = mutableListOf<dev.progress4j.api.ProgressReport>()
        val progress = ParallelURLProgress(listOf("https://example.com/download/tool.zip")) { reports += it }

        progress.tracker(0)!!.report(dev.progress4j.api.ProgressReport.create("Downloading", 20, 5))
        progress.complete(0)

        assertEquals(1, reports.size)
        assertEquals("tool.zip", reports.single().message)
        assertTrue(reports.single().subReports.isEmpty())
    }

    @Test
    fun `archive root progress uses the archive name`() {
        val reports = mutableListOf<dev.progress4j.api.ProgressReport>()
        val progress = ParallelURLProgress(listOf("https://example.com/releases/foobar.tar.gz/")) { reports += it }

        progress.tracker(0)!!.report(dev.progress4j.api.ProgressReport.create("Downloading", 20, 5))

        assertEquals("foobar.tar.gz", reports.last().message)
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
        assertTrue(extractedArchiveCacheKey(archive, quarantined = true).matches(
            Regex("Extracted archive\\nLayout version: 4\\nFile name: tool\\.zip\\nSHA-256: [a-z0-9]+\\nQuarantined: true")
        ))
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
            URLResolver(cache).resolve(server.uri("/tool.zip/tool-1.0/bin/tool")).use { resolved ->
                assertEquals("archive member", resolved.path.readText())
            }
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun `archive member hash locks authenticate the complete archive`() = withServer { server ->
        val zip = zipOf("tool-1.0/bin/tool" to "archive member")
        server.createContext("/") {
            if (it.requestURI.path == "/tool.zip") it.respond(zip) else it.respond404()
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/tool.zip/tool-1.0/bin/tool#sha256=${zip.sha256()}"))
                .use { resolved -> assertEquals("archive member", resolved.path.readText()) }
            assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/tool.zip/tool-1.0/bin/tool#sha256=${"0".repeat(64)}"))
            }
        }
    }

    @Test
    fun `archive-shaped URLs served directly validate the returned file`() = withServer { server ->
        val contents = "direct response".toByteArray()
        server.createContext("/") { it.respond(contents) }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/tool.zip/bin/tool#sha256=${contents.sha256()}"))
                .use { resolved -> assertEquals("direct response", resolved.path.readText()) }
            assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/tool.zip/bin/tool#sha256=${"0".repeat(64)}"))
            }
        }
    }

    @Test
    fun `hash locks on archive directory results authenticate the archive`() = withServer { server ->
        val zip = zipOf("tool-1.0/bin/tool" to "archive member")
        val tarball = tarGzOf("tool-1.0/bin/tool" to "streamed member")
        val innerZip = zipOf("inner/file.txt" to "nested member")
        val outerZip = zipOfBytes("inner.zip" to innerZip)
        server.createContext("/") {
            when (it.requestURI.path) {
                "/tool.zip" -> it.respond(zip)
                "/tool.tar.gz" -> it.respond(tarball)
                "/outer.zip" -> it.respond(outerZip)
                else -> it.respond404()
            }
        }
        val wrong = "0".repeat(64)

        makeCache().use { cache ->
            // Repeat each lookup so cache hits are checked as well as fresh extractions.
            repeat(2) {
                URLResolver(cache).resolve(server.uri("/tool.zip/#sha256=${zip.sha256()}")).use {
                    assertEquals("archive member", (it.path / "tool-1.0/bin/tool").readText())
                }
                URLResolver(cache).resolve(server.uri("/tool.zip/tool-1.0/bin/#sha256=${zip.sha256()}")).use {
                    assertEquals("archive member", (it.path / "tool").readText())
                }
                URLResolver(cache).resolve(server.uri("/tool.tar.gz/#sha256=${tarball.sha256()}")).use {
                    assertEquals("streamed member", (it.path / "tool-1.0/bin/tool").readText())
                }
                URLResolver(cache).resolve(server.uri("/outer.zip/inner.zip/inner/#sha256=${innerZip.sha256()}")).use {
                    assertEquals("nested member", (it.path / "file.txt").readText())
                }
            }
            for (path in listOf("/tool.zip/", "/tool.zip/tool-1.0/bin/", "/tool.tar.gz/", "/outer.zip/inner.zip/inner/")) {
                assertFailsWith<IllegalArgumentException>(path) {
                    URLResolver(cache).resolve(server.uri("$path#sha256=$wrong"))
                }
            }
            // The outer archive's hash does not authenticate a nested archive.
            assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/outer.zip/inner.zip/inner/#sha256=${outerZip.sha256()}"))
            }
        }
    }

    @Test
    fun `trailing slash resolves to the exact archive root`() = withServer { server ->
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
                assertEquals("archive member", (resolved.path / "tool-1.0/bin/tool").readText())
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
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.gz/tool-1.0/bin/tool")).use {
                assertEquals("streamed member", it.path.readText())
            }
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.gz/tool-1.0/bin/tool")).close()
            assertFalse(cache.has(HttpResourceCache.cacheKey(URI("https://example.com/tool.tar.gz"))))
        }
        assertEquals(
            listOf("/tool.tar.gz/tool-1.0/bin/tool", "/tool.tar.gz", "/tool.tar.gz/tool-1.0/bin/tool"),
            requests.map { it.path }
        )
    }

    @Test
    fun `hash locked streamed tarball is reused without revalidation`() {
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val tarball = tarGzOf("tool-1.0/bin/tool" to "locked member")
        val archiveRequests = AtomicInteger()
        val transport = HttpTransport { uri, headers ->
            if (uri == archiveURI) {
                assertEquals(0, archiveRequests.getAndIncrement())
                assertTrue(headers["If-None-Match"] == null)
                HttpTransport.Response(
                    200,
                    mapOf("Cache-Control" to listOf("no-cache"), "ETag" to listOf("\"archive\"")),
                    ByteArrayInputStream(tarball)
                )
            } else {
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }
        val uri = URI("$archiveURI/tool-1.0/bin/tool#sha256=${tarball.sha256()}")

        makeCache().use { cache ->
            URLResolver(cache, transport = transport).resolve(uri).close()
            URLResolver(cache, transport = transport).resolve(uri).close()
        }

        assertEquals(1, archiveRequests.get())
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

            URLResolver(cache, transport = transport).resolve(URI("$archiveURI/tool-1.0/bin/tool")).use {
                assertEquals("cached member", it.path.readText())
            }
        }
    }

    @Test
    fun `refresh fetches a streamed tarball again`() {
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val first = tarGzOf("tool-1.0/bin/tool" to "version 1")
        val second = tarGzOf("tool-1.0/bin/tool" to "version 2")
        val archiveRequests = AtomicInteger()
        val transport = HttpTransport { uri, _ ->
            if (uri == archiveURI) {
                val body = if (archiveRequests.incrementAndGet() == 1) first else second
                HttpTransport.Response(200, mapOf("Cache-Control" to listOf("max-age=3600")), ByteArrayInputStream(body))
            } else {
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }

        makeCache().use { cache ->
            URLResolver(cache, transport = transport).resolve(URI("$archiveURI/tool-1.0/bin/tool")).use {
                assertEquals("version 1", it.path.readText())
            }
            URLResolver(cache, transport = transport, refresh = true)
                .resolve(URI("$archiveURI/tool-1.0/bin/tool")).use {
                    assertEquals("version 2", it.path.readText())
                }
        }

        assertEquals(2, archiveRequests.get())
    }

    @Test
    fun `new streamed tarball responses discard obsolete validators`() {
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val archiveRequests = AtomicInteger()
        val validators = mutableListOf<String?>()
        val transport = HttpTransport { uri, headers ->
            if (uri == archiveURI) {
                validators += headers["If-None-Match"]
                val request = archiveRequests.incrementAndGet()
                val responseHeaders = if (request == 1) {
                    mapOf("Cache-Control" to listOf("max-age=0"), "ETag" to listOf("\"version-1\""))
                } else {
                    emptyMap()
                }
                HttpTransport.Response(
                    200,
                    responseHeaders,
                    ByteArrayInputStream(tarGzOf("tool-1.0/bin/tool" to "version $request"))
                )
            } else {
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }

        makeCache().use { cache ->
            val uri = URI("$archiveURI/tool-1.0/bin/tool")
            URLResolver(cache, transport = transport).resolve(uri).close()
            URLResolver(cache, transport = transport).resolve(uri).close()
            URLResolver(cache, transport = transport).resolve(uri).close()
        }

        assertEquals(listOf(null, "\"version-1\"", null), validators)
    }

    @Test
    fun `streamed tarball Age reduces its freshness lifetime`() {
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val archiveRequests = AtomicInteger()
        val transport = HttpTransport { uri, _ ->
            if (uri == archiveURI) {
                archiveRequests.incrementAndGet()
                HttpTransport.Response(
                    200,
                    mapOf("Cache-Control" to listOf("max-age=60"), "Age" to listOf("3600")),
                    ByteArrayInputStream(tarGzOf("tool-1.0/bin/tool" to "aged"))
                )
            } else {
                HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }

        makeCache().use { cache ->
            val uri = URI("$archiveURI/tool-1.0/bin/tool")
            URLResolver(cache, transport = transport).resolve(uri).close()
            URLResolver(cache, transport = transport).resolve(uri).close()
        }

        assertEquals(2, archiveRequests.get())
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
            URLResolver(cache, transport = transport).resolve(URI("https://example.com/tool.tar.zst/tool/member")).use {
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
    fun `macOS downloaded files receive quarantine unless opted out`() = withServer { server ->
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return@withServer
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Cache-Control", "max-age=3600")
            // Like a browser download, quarantine does not depend on the content being executable.
            exchange.respond("plain text payload".toByteArray())
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/enabled")).use { resolved ->
                assertContains(resolved.path.macExtendedAttribute("com.apple.quarantine"), ";Hydraulic\\x20URL;")
                assertFalse("user.com.apple.quarantine" in resolved.path.macExtendedAttributes())
            }
            URLResolver(cache, gatekeeper = false).resolve(server.uri("/enabled")).use { resolved ->
                assertFalse("com.apple.quarantine" in resolved.path.macExtendedAttributes())
            }
        }
        assertEquals(1, requests.get())
    }

    @Test
    fun `macOS extracted archives are quarantined throughout unless opted out`() = withServer { server ->
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return@withServer
        val zip = zipOf("Tool.app/Contents/MacOS/Tool" to "app binary", "bin/tool" to "loose binary", "README" to "text")
        val tarball = tarGzOf("tool-1.0/bin/tool" to "loose binary", "tool-1.0/README" to "text")
        server.createContext("/") {
            when (it.requestURI.path) {
                "/tool.zip" -> it.respond(zip)
                "/tool.tar.gz" -> it.respond(tarball)
                else -> it.respond404()
            }
        }
        fun Path.quarantinedEntries(): Map<String, Boolean> = Files.walk(this).use { paths ->
            paths.toList().associate { relativize(it).toString() to ("com.apple.quarantine" in it.macExtendedAttributes()) }
        }

        makeCache().use { cache ->
            for (path in listOf("/tool.zip/", "/tool.tar.gz/")) {
                URLResolver(cache).resolve(server.uri(path)).use { resolved ->
                    val entries = resolved.path.quarantinedEntries()
                    assertTrue(entries.size > 3, "$path: $entries")
                    assertTrue(entries.values.all { it }, "$path: $entries")
                }
                URLResolver(cache, gatekeeper = false).resolve(server.uri(path)).use { resolved ->
                    val entries = resolved.path.quarantinedEntries()
                    assertTrue(entries.values.none { it }, "$path: $entries")
                }
            }
        }
    }

    @Test
    fun `macOS revalidated streamed tarballs stay quarantined`() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return
        val archiveURI = URI("https://example.com/tool.tar.gz")
        val tarball = tarGzOf("tool-1.0/bin/tool" to "loose binary")
        val statuses = mutableListOf<Int>()
        val transport = HttpTransport { uri, headers ->
            if (uri != archiveURI)
                return@HttpTransport HttpTransport.Response(404, emptyMap(), ByteArrayInputStream(byteArrayOf()))
            val status = if (headers["If-None-Match"] == "\"v1\"") 304 else 200
            statuses += status
            val responseHeaders = mapOf("Cache-Control" to listOf("no-cache"), "ETag" to listOf("\"v1\""))
            HttpTransport.Response(status, responseHeaders, ByteArrayInputStream(if (status == 200) tarball else byteArrayOf()))
        }

        makeCache().use { cache ->
            repeat(2) {
                URLResolver(cache, transport = transport).resolve(URI("$archiveURI/tool-1.0/")).use { resolved ->
                    assertTrue("com.apple.quarantine" in (resolved.path / "bin/tool").macExtendedAttributes())
                    assertTrue("com.apple.quarantine" in resolved.path.macExtendedAttributes())
                }
            }
        }
        assertEquals(listOf(200, 304), statuses)
    }

    @Test
    fun `macOS quarantine handles read-only files`() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return
        val tree = tempDir / "tree"
        val file = tree / "legal" / "LICENSE"
        file.parent.createDirectories()
        file.writeText("read only")
        val readOnly = PosixFilePermissions.fromString("r--r--r--")
        Files.setPosixFilePermissions(file, readOnly)

        tree.quarantineTree()
        assertTrue("com.apple.quarantine" in file.macExtendedAttributes())
        assertEquals(readOnly, Files.getPosixFilePermissions(file))

        file.applyGatekeeperQuarantine(enabled = false)
        assertFalse("com.apple.quarantine" in file.macExtendedAttributes())
        assertEquals(readOnly, Files.getPosixFilePermissions(file))
    }

    @Test
    fun `macOS quarantine metadata is not replaced`() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
            return
        val file = tempDir / "already-quarantined"
        val existing = "0081;5f5e100;Existing;12345678-1234-1234-1234-123456789abc"
        file.writeBytes(bytes(0xfeedfacf.toInt()) + " payload".toByteArray())
        xattr("-w", "com.apple.quarantine", existing, file.toString())

        file.applyGatekeeperQuarantine(enabled = true)

        assertEquals(existing, file.macExtendedAttribute("com.apple.quarantine"))
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
    fun `cache entry missing its content directory is rebuilt`() = withServer { server ->
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.respond("download".toByteArray())
        }

        makeCache().use { cache ->
            val uri = server.uri("/missing-content")
            URLResolver(cache).resolve(uri).use { assertEquals("download", it.path.readText()) }
            val content = cache.lookup(HttpResourceCache.cacheKey(uri))!!.use { it.directory }
            assertTrue(content.toFile().deleteRecursively())

            URLResolver(cache).resolve(uri).use { assertEquals("download", it.path.readText()) }
        }

        assertEquals(2, requests.get())
    }

    @Test
    fun `cache entry missing its downloaded file is rebuilt`() = withServer { server ->
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Cache-Control", "max-age=3600")
            exchange.respond("download".toByteArray())
        }

        makeCache().use { cache ->
            val uri = server.uri("/missing-file")
            val downloaded = URLResolver(cache).resolve(uri).use { it.path }
            assertTrue(Files.deleteIfExists(downloaded))

            URLResolver(cache).resolve(uri).use { assertEquals("download", it.path.readText()) }
        }

        assertEquals(2, requests.get())
    }

    @Test
    fun `cache entry missing an extracted archive member is rebuilt`() = withServer { server ->
        val archiveRequests = AtomicInteger()
        val zip = zipOf("tool-1.0/bin/tool" to "archive member")
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/tool.zip") {
                archiveRequests.incrementAndGet()
                exchange.responseHeaders.add("Cache-Control", "max-age=3600")
                exchange.respond(zip)
            } else {
                exchange.respond404()
            }
        }

        makeCache().use { cache ->
            val uri = server.uri("/tool.zip/tool-1.0/bin/tool")
            val member = URLResolver(cache).resolve(uri).use { it.path }
            assertTrue(Files.deleteIfExists(member))

            URLResolver(cache).resolve(uri).use { assertEquals("archive member", it.path.readText()) }
        }

        assertEquals(2, archiveRequests.get())
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
            URLResolver(cache).resolve(server.uri("/outer.zip/outer/dir/inner.zip/inner/file.txt")).use { resolved ->
                assertEquals("nested member", resolved.path.readText())
            }
        }
        assertEquals(
            listOf(
                "/outer.zip/outer/dir/inner.zip/inner/file.txt",
                "/outer.zip/outer/dir/inner.zip",
                "/outer.zip"
            ),
            requests
        )
    }

    @Test
    fun `nested archive hash locks authenticate only the innermost archive`() = withServer { server ->
        val innerZip = zipOf("inner/file.txt" to "nested member")
        val outerZip = zipOfBytes("outer/dir/inner.zip" to innerZip)
        server.createContext("/") {
            if (it.requestURI.path == "/outer.zip") it.respond(outerZip) else it.respond404()
        }

        makeCache().use { cache ->
            URLResolver(cache).resolve(
                server.uri("/outer.zip/outer/dir/inner.zip/inner/file.txt#sha256=${innerZip.sha256()}")
            ).use { resolved -> assertEquals("nested member", resolved.path.readText()) }
        }
    }

    @Test
    fun `locked nested archives cannot write through outer archive symlinks`() = withServer { server ->
        val innerZip = zipOf("file.txt" to "authenticated member")
        val outerTar = maliciousSymlinkTarGz("inner.zip" to innerZip)
        server.createContext("/") {
            if (it.requestURI.path == "/outer.tar.gz") it.respond(outerTar) else it.respond404()
        }

        makeCache().use { cache ->
            assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(
                    server.uri("/outer.tar.gz/inner.zip/file.txt#sha256=${innerZip.sha256()}")
                )
            }
        }
        assertFalse(Files.exists(tempDir / "payload", java.nio.file.LinkOption.NOFOLLOW_LINKS))
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
            extractSafeLocalArchive(archive, (tempDir / "extracted").createDirectories())
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
                URLResolver(cache).resolve(server.uri("/symlink.zip/root/link"))
            }
            assertTrue(exception.message!!.contains("escapes its extraction root"))
        }
    }

    @Test
    fun `local extraction creates an archive symlink without a pre-existing target`() {
        val archive = tempDir / "symlink.zip"
        archive.writeBytes(unixZipOf("root/bin/java", "../lib/jvm", UnixStat.LINK_FLAG or 0b111_101_101))
        val destination = (tempDir / "extracted").createDirectories()

        extractSafeLocalArchive(archive, destination)

        val link = destination / "root/bin/java"
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
    fun `command line prints a stack trace in verbose mode`() {
        val stderr = StringWriter()
        val exitCode = commandLine().apply { err = PrintWriter(stderr) }.execute(
            "--verbose", "--cache-dir", (tempDir / "verbose-cli-cache").toString(), "file:///tmp/not-http"
        )

        assertEquals(1, exitCode)
        assertContains(stderr.toString(), "url: Not an HTTP(S) URI: file:///tmp/not-http")
        assertContains(stderr.toString(), "IllegalArgumentException")
        assertContains(stderr.toString(), "at hydraulic.url")
    }

    @Test
    fun `URL_VERBOSE enables detailed command line failures`() {
        val stderr = StringWriter()
        val exitCode = commandLine(environment = mapOf("URL_VERBOSE" to "1"))
            .apply { err = PrintWriter(stderr) }
            .execute("--cache-dir", (tempDir / "environment-cli-cache").toString(), "file:///tmp/not-http")

        assertEquals(1, exitCode)
        assertContains(stderr.toString(), "IllegalArgumentException")
        assertContains(stderr.toString(), "at hydraulic.url")
    }

    @Test
    fun `command line distinguishes usage errors from execution failures`() {
        assertEquals(2, commandLine().execute("--does-not-exist"))
        assertEquals(1, commandLine().execute("--print0", "--print-separator=:"))
        assertEquals(0, commandLine().execute("--help"))
    }

    @Test
    fun `command line reports its version`() {
        val output = StringWriter()
        val error = StringWriter()
        val command = commandLine().setOut(PrintWriter(output)).setErr(PrintWriter(error))

        assertEquals(0, command.execute("-V"))
        assertEquals("$VERSION\n", output.toString())
        assertEquals("", error.toString())

        val runOutput = StringWriter()
        val run = commandLine("run").setOut(PrintWriter(runOutput))
        assertEquals(0, run.execute("--version"))
        assertEquals("$VERSION\n", runOutput.toString())
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
    fun `run directory convention preserves URL suffixes and selects the JavaScript package`() {
        assertEquals(URI("https://example.com/run.zip/run.js"), runTargetURI(URI("https://example.com")))
        assertEquals(
            URI("https://example.com/tools/run.zip/run.js?channel=beta#sha256=abc"),
            runTargetURI(URI("https://example.com/tools/?channel=beta#sha256=abc"))
        )
        assertEquals(URI("https://example.com/tools/run.zip/run.js"), runTargetURI(URI("https://example.com/tools")))
        assertEquals(URI("https://example.com/tools/run.zip/run.js"), runTargetURI(URI("https://example.com/tools/")))
        assertEquals(URI("https://example.com/tool.js"), runTargetURI(URI("https://example.com/tool.js")))
        assertEquals(URI("https://example.com/tool.zip"), runTargetURI(URI("https://example.com/tool.zip")))
    }

    @Test
    fun `run target extracts a version suffix without disturbing URL suffixes`() {
        assertEquals(RunTarget("example.com", "1.2.3"), parseRunTarget("example.com@1.2.3"))
        assertEquals(
            RunTarget("example.com/someapp?channel=beta#sha256=abc", "v7-beta"),
            parseRunTarget("example.com/someapp@v7-beta?channel=beta#sha256=abc")
        )
        assertEquals(
            RunTarget("https://user@example.com/path", null),
            parseRunTarget("https://user@example.com/path")
        )
        assertEquals(RunTarget("example.com/tool@", null), parseRunTarget("example.com/tool@"))
    }

    @Test
    fun `runscript platform values are normalized`() {
        assertEquals("linux", runOperatingSystem("Linux"))
        assertEquals("macos", runOperatingSystem("Mac OS X"))
        assertEquals("freebsd", runOperatingSystem("FreeBSD"))
        assertEquals("android", runOperatingSystem("Linux", "Android Runtime"))
        assertEquals("windows", runOperatingSystem("Windows 11"))
        assertEquals("x86_64", runArchitecture("amd64"))
        assertEquals("arm64", runArchitecture("aarch64"))
        assertEquals("riscv64", runArchitecture("riscv64"))
    }

    @Test
    fun `run selects the JavaScript package from a local directory`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val script = directory / "run.js"
        script.writeText("await urls({}); export default { executable: \"/bin/true\", arguments: [] };\n")

        assertEquals(script.toAbsolutePath(), localRunPackage(directory.toString()))
        assertEquals(null, localRunPackage("missing-run-package"))
    }

    @Test
    fun `missing explicit local run paths do not fall through to URL resolution`() {
        val relative = "./missing-run-package-${tempDir.fileName}"
        val absolute = tempDir.resolve("missing-run-package").toString()

        for (target in listOf(relative, absolute)) {
            val failure = assertFailsWith<IllegalArgumentException> { localRunPackage(target) }
            assertContains(failure.message.orEmpty(), "Local run directory does not exist")
        }
    }

    @Test
    fun `bare launch-plan executables use the host command search path`() {
        val packageFile = Path.of("site/r/demo/run.zip.d/run.js").toAbsolutePath()
        val packageDir = packageFile.parent
        val plan = evaluateRunJavaScript(
            packageFile,
            RunContext("windows", "x86_64", null, emptyList(), packageDir),
            resolve = { error("unused") }
        )

        assertEquals(Path.of("powershell.exe"), plan.executable)
    }

    @Test
    fun `run evaluates a local JavaScript package and passes its arguments`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
            return
        val directory = (tempDir / "run.zip.d").createDirectories()
        val output = tempDir / "local-arguments"
        val executable = directory / "tool.sh"
        executable.writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\" > '$output'\n")
        executable.toFile().setExecutable(true)
        (directory / "run.js").writeText(
            """
            await urls({});
            export default { executable: `${'$'}{context.packageDir}/tool.sh`, arguments: context.args };
            """.trimIndent()
        )
        val run = Run(
            executablePath = { tempDir / "run" },
            windows = false,
            operatingSystem = "linux",
            architecture = "arm64"
        )

        val exitCode = CommandLine(run).setStopAtPositional(true)
            .execute(directory.toString(), "--help", "two words")

        assertEquals(0, exitCode)
        assertEquals("--help\ntwo words\n", output.readText())
    }

    @Test
    fun `JavaScript urls function resolves one named map and returns paths`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const resolved = await urls({ alpha: "https://example.com/alpha", beta: "https://example.com/beta" });
            export default { executable: resolved.alpha, arguments: [resolved.beta, context.os, ...context.args] };
            """.trimIndent()
        )
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val plan = evaluateRunJavaScript(
            packageFile,
            RunContext("linux", "x86_64", "1.2.3", listOf("one"), directory)
        ) { url ->
            requested += url
            Path.of("/cache/${url.substringAfterLast('/')}")
        }

        assertEquals(listOf("https://example.com/alpha", "https://example.com/beta"), requested.sorted())
        assertEquals(Path.of("/cache/alpha"), plan.executable)
        assertEquals(listOf("/cache/beta", "linux", "one"), plan.arguments)
    }

    @Test
    fun `JavaScript urls function resolves unnamed and grouped arrays`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const unnamed = await urls(["https://example.com/one", "https://example.com/two"]);
            const named = await urls({ tools: ["https://example.com/three", "https://example.com/four"], none: [] });
            const empty = await urls([]);
            export default {
              executable: unnamed[0],
              arguments: [unnamed[1], named.tools[0], named.tools[1], `${'$'}{named.none.length} ${'$'}{empty.length}`],
            };
            """.trimIndent()
        )
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val plan = evaluateRunJavaScript(
            packageFile,
            RunContext("linux", "x86_64", null, emptyList(), directory)
        ) { url ->
            requested += url
            Path.of("/cache/${url.substringAfterLast('/')}")
        }

        assertEquals(
            setOf(
                "https://example.com/one",
                "https://example.com/two",
                "https://example.com/three",
                "https://example.com/four"
            ),
            requested.toSet()
        )
        assertEquals(Path.of("/cache/one"), plan.executable)
        assertEquals(listOf("/cache/two", "/cache/three", "/cache/four", "0 0"), plan.arguments)
    }

    @Test
    fun `JavaScript sibling modules resolve URLs concurrently`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        (directory / "first.js").writeText("export const first = await url(\"https://example.com/first\");\n")
        (directory / "second.js").writeText("export const second = await url(\"https://example.com/second\");\n")
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            import { first } from "./first.js";
            import { second } from "./second.js";
            export default { executable: first, arguments: [second] };
            """.trimIndent()
        )
        // Each resolution waits for the other to start, so this only completes if the modules' requests overlap.
        val started = CountDownLatch(2)
        val plan = evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { url ->
            started.countDown()
            assertTrue(started.await(10, TimeUnit.SECONDS), "resolutions did not overlap")
            Path.of("/cache/${url.substringAfterLast('/')}")
        }

        assertEquals(Path.of("/cache/first"), plan.executable)
        assertEquals(listOf("/cache/second"), plan.arguments)
    }

    @Test
    fun `JavaScript can handle resolution failures`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            let tool;
            try {
              tool = await url("https://primary.example.com/tool");
            } catch (e) {
              tool = await url("https://mirror.example.com/tool");
            }
            const invalid = await url(42).then(() => "resolved", e => e.message);
            const native = url("https://mirror.example.com/tool") instanceof Promise;
            export default { executable: tool, arguments: [invalid, String(native)] };
            """.trimIndent()
        )
        val plan = evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { url ->
            if ("primary" in url)
                throw IllegalStateException("primary is down")
            Path.of("/cache/mirror")
        }

        assertEquals(Path.of("/cache/mirror"), plan.executable)
        assertEquals(listOf("run.js function url() expects a URL string", "true"), plan.arguments)
    }

    @Test
    fun `unhandled JavaScript resolution failures fail with the original exception`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            url("https://example.com/missing");
            await url("https://example.com/slow");
            export default { executable: "true", arguments: [] };
            """.trimIndent()
        )
        val failure = IllegalStateException("not found")
        val slowStarted = CountDownLatch(1)
        val slowInterrupted = CountDownLatch(1)
        val thrown = assertFailsWith<IllegalStateException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { url ->
                if ("missing" in url) {
                    assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
                    throw failure
                }
                slowStarted.countDown()
                try {
                    Thread.sleep(60_000)
                } catch (e: InterruptedException) {
                    slowInterrupted.countDown()
                    throw e
                }
                error("not interrupted")
            }
        }

        assertSame(failure, thrown)
        // Abandoned work is interrupted and finishes before evaluation returns.
        assertEquals(0, slowInterrupted.count)
    }

    @Test
    fun `JavaScript evaluation waits for operations that are never awaited`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            url("https://example.com/background");
            export default { executable: "true", arguments: [] };
            """.trimIndent()
        )
        val finished = AtomicBoolean(false)
        evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) {
            Thread.sleep(200)
            finished.set(true)
            Path.of("/cache/background")
        }

        assertTrue(finished.get())
    }

    @Test
    fun `JavaScript evaluation fails when it awaits a promise that never settles`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText("await new Promise(() => {});\nexport default { executable: \"true\", arguments: [] };\n")

        val failure = assertFailsWith<IllegalStateException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { error("unused") }
        }
        assertContains(failure.message.orEmpty(), "never settles")
    }

    @Test
    fun `JavaScript can fall back from a failure that arrives while awaiting another operation`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const tool = url("https://example.com/tool");
            const config = url("https://primary.example.com/config").catch(() => url("https://mirror.example.com/config"));
            export default { executable: await tool, arguments: [await config] };
            """.trimIndent()
        )
        // The tool resolves only after the fallback has started, so the primary failure arrives while run.js awaits it.
        val fallbackStarted = CountDownLatch(1)
        val plan = evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { url ->
            when {
                "primary" in url -> throw IllegalStateException("primary is down")
                "mirror" in url -> fallbackStarted.countDown()
                else -> assertTrue(fallbackStarted.await(10, TimeUnit.SECONDS), "fallback did not start")
            }
            Path.of("/cache/${url.substringAfterLast('/')}")
        }

        assertEquals(Path.of("/cache/tool"), plan.executable)
        assertEquals(listOf("/cache/config"), plan.arguments)
    }

    @Test
    fun `abandoned operations that ignore interruption do not hang a failed evaluation`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            url("https://example.com/stuck");
            await url("https://example.com/missing");
            export default { executable: "true", arguments: [] };
            """.trimIndent()
        )
        val failure = IllegalStateException("not found")
        val stuckStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                evaluateRunJavaScript(
                    packageFile,
                    RunContext("linux", "x86_64", null, emptyList(), directory),
                    abandonmentGrace = Duration.ofMillis(100)
                ) { url ->
                    if ("missing" in url) {
                        assertTrue(stuckStarted.await(10, TimeUnit.SECONDS))
                        throw failure
                    }
                    stuckStarted.countDown()
                    while (true) {
                        try {
                            if (release.await(10, TimeUnit.SECONDS))
                                break
                        } catch (_: InterruptedException) {
                            // Simulates work that cannot be interrupted.
                        }
                    }
                    Path.of("/cache/stuck")
                }
            }
            assertSame(failure, thrown)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `JavaScript errors report their location in the package`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText("await url(\"https://example.com/tool\");\nthrow new Error(\"boom\");\n")

        val failure = assertFailsWith<PolyglotException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) {
                Path.of("/cache/tool")
            }
        }
        assertContains(failure.message.orEmpty(), "boom")
        // Verbose diagnostics print the stack trace, whose first guest frame is the throw in run.js.
        val location = assertNotNull(failure.polyglotStackTrace.first { it.isGuestFrame }.sourceLocation)
        assertEquals(packageFile.toRealPath().toString(), location.source.path)
        assertEquals(2, location.startLine)
    }

    @Test
    fun `JavaScript values thrown in place of errors are described`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText("await url(\"https://example.com/tool\");\nthrow {code: 1};\n")

        val failure = assertFailsWith<IllegalStateException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) {
                Path.of("/cache/tool")
            }
        }
        assertContains(failure.message.orEmpty(), "{\"code\":1}")
    }

    @Test
    fun `make-run-zip JavaScript selects the portable zip fallback`() {
        val packageFile = Path.of("site/r/make-run-zip/run.zip.d/run.js").toAbsolutePath()
        val packageDir = packageFile.parent
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val plan = evaluateRunJavaScript(
            packageFile,
            RunContext("linux", "x86_64", null, listOf("source", "output.zip"), packageDir)
        ) { url ->
            requested += url
            Path.of("/cache/zip")
        }

        assertTrue(requested.single().contains("7z2603-linux-x64.tar.xz/7zz"))
        assertEquals(packageDir.resolve("make-run-zip.sh").toAbsolutePath(), plan.executable)
        assertEquals(listOf("/cache/zip", "source", "output.zip"), plan.arguments)
        assertContains(Path.of("site/r/make-run-zip/run.zip.d/make-run-zip.sh").readText(), "OpenSSL is required on PATH")
        assertContains(Path.of("site/r/make-run-zip/run.zip.d/make-run-zip.sh").readText(), "-cert")
        assertContains(Path.of("site/r/make-run-zip/run.zip.d/make-run-zip.ps1").readText(), "-cert")
    }

    @Test
    fun `make-run-zip rejects newlines before creating its manifest`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val source = (tempDir / "source").createDirectories()
        Files.createFile(source.resolve("line\nbreak"))
        val script = Path.of("site/r/make-run-zip/run.zip.d/make-run-zip.sh").toAbsolutePath()
        val output = tempDir.resolve("output.zip")
        val fakeBin = (tempDir / "bin").createDirectories()
        for (tool in listOf("openssl", "zip")) {
            val fake = fakeBin.resolve(tool)
            Files.createFile(fake)
            fake.toFile().setExecutable(true)
        }
        val process = ProcessBuilder(
            "/bin/sh", script.toString(), "/missing/zip",
            source.toString(), output.toString()
        ).redirectErrorStream(true).apply {
            environment()["PATH"] = "${fakeBin}:/usr/bin:/bin"
        }.start()
        val result = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(2, process.waitFor())
        assertContains(result, "Source file names may not contain newlines")
        assertFalse(Files.exists(output))
    }

    @Test
    fun `run package manifest is sorted by UTF-8 path and excludes the root timestamp`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        (directory / "z.txt").writeText("z")
        (directory / "a.txt").writeText("a")
        (directory / "é.txt").writeText("e")
        (directory / "nested").createDirectories()
        (directory / "nested" / "context.d.ts").writeText("validated")
        (directory / "nested" / "timestamp.tsr").writeText("validated")
        (directory / "timestamp.tsr").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(
            """
            ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb  a.txt
            e3e4aec3737242616e0b3dd3a6c7eaaf3fe42746de7209e880dae8db335799cd  nested/context.d.ts
            e3e4aec3737242616e0b3dd3a6c7eaaf3fe42746de7209e880dae8db335799cd  nested/timestamp.tsr
            594e519ae499312b29433b7dd8a97ff068defcba9755b6d5d00e84c524d67b06  z.txt
            3f79bb7b435b05321651daefd374cdc681dc06faa65e374e38337b88ca046dea  é.txt
            """.trimIndent() + "\n",
            canonicalRunManifest(directory).toString(StandardCharsets.UTF_8)
        )
    }

    @Test
    fun `run package manifest includes nested timestamp files`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        (directory / "run.js").writeText("export default { executable: \"true\", arguments: [] };")
        (directory / "nested").createDirectories()
        (directory / "nested" / "timestamp.tsr").writeText("untimestamped")

        assertEquals(
            """
            e5236e362a88511209e95774d79a74d2e13fc0fa92e8bdd915ae441ffbfa7fc2  nested/timestamp.tsr
            c525d242f87a3eeb3e15fda93d54d636626ec0257eac8ae36a648379f73d4ffb  run.js
            """.trimIndent() + "\n",
            canonicalRunManifest(directory).toString(StandardCharsets.UTF_8)
        )
    }

    @Test
    fun `run package verification rejects an invalid timestamp response`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        (directory / "run.js").writeText("export default { executable: \"true\", arguments: [] };")
        (directory / "timestamp.tsr").writeText("not a timestamp")

        assertFailsWith<IllegalArgumentException> { verifyRunPackage(directory) }
    }

    @Test
    fun `make-run-zip PowerShell launcher checks reparse points when PowerShell is available`() {
        val commandLookup = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
            { command: String -> ProcessBuilder("where.exe", command) }
        else
            { command: String -> ProcessBuilder("/bin/sh", "-c", "command -v $command") }
        val powershell = sequenceOf("pwsh", "powershell")
            .mapNotNull { command ->
                runCatching {
                    commandLookup(command).start().also { process -> process.waitFor() }
                }.getOrNull()?.takeIf { it.exitValue() == 0 }?.let { command }
            }
            .firstOrNull()
        assumeTrue(powershell != null)

        val source = (tempDir / "source").createDirectories()
        val outside = (tempDir / "outside").createDirectories()
        (outside / "secret.txt").writeText("secret")
        try {
            Files.createSymbolicLink(source.resolve("link"), outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }
        val script = Path.of("site/r/make-run-zip/run.zip.d/make-run-zip.ps1").toAbsolutePath()
        val process = ProcessBuilder(
            powershell!!, "-NoProfile", "-NonInteractive", "-File", script.toString(),
            "/missing/openssl.exe", "/missing/7z.exe", source.toString(), tempDir.resolve("output.zip").toString()
        ).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue(process.waitFor() != 0)
        assertContains(result, "Source directory contains unsupported symbolic links")
    }

    @Test
    fun `JavaScript launch plans allow multiple URL calls and reject malformed results`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val secondCall = directory / "second.js"
        secondCall.writeText("await urls({ first: \"https://example.com/first\" }); await urls({ second: \"https://example.com/second\" }); export default { executable: \"true\", arguments: [] }; ")
        val callCount = AtomicInteger()
        evaluateRunJavaScript(secondCall, RunContext("linux", "x86_64", null, emptyList(), directory)) {
            callCount.incrementAndGet()
            Path.of("/cache/${it.substringAfterLast('/')}")
        }
        assertEquals(2, callCount.get())

        val malformed = directory / "malformed.js"
        malformed.writeText("await urls({}); export default { executable: \"true\", arguments: [1] }; ")
        assertFailsWith<IllegalArgumentException> {
            evaluateRunJavaScript(malformed, RunContext("linux", "x86_64", null, emptyList(), directory)) { error("unused") }
        }
    }

    @Test
    fun `JavaScript packages can import local modules`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        (directory / "helper.js").writeText("export const executable = \"true\";\n")
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            import { executable } from "./helper.js";
            await urls({});
            export default { executable, arguments: [] };
            """.trimIndent()
        )

        assertEquals(
            Path.of("true"),
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { error("unused") }
                .executable
        )
    }

    @Test
    fun `JavaScript packages cannot access host classes or IO`() {
        val directory = (tempDir / "run.zip.d").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            await urls({});
            Java.type("java.lang.System");
            export default { executable: "true", arguments: [] };
            """.trimIndent()
        )

        assertFailsWith<RuntimeException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory)) { error("unused") }
        }

        (tempDir / "outside.js").writeText("export default \"outside\";\n")
        val escape = directory / "escape.js"
        escape.writeText(
            """
            import outside from "../outside.js";
            await urls({});
            export default { executable: outside, arguments: [] };
            """.trimIndent()
        )
        assertFailsWith<RuntimeException> {
            evaluateRunJavaScript(escape, RunContext("linux", "x86_64", null, emptyList(), directory)) { error("unused") }
        }
    }

    @Test
    fun `run resolves named JavaScript URLs before evaluating the launch command`() = withServer { server ->
        val directory = (tempDir / "run.zip.d").createDirectories()
        val output = tempDir / "resolved-output"
        server.createContext("/tool") {
            it.respond("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\" > \"${'$'}1\"\n".toByteArray())
        }
        (directory / "run.js").writeText(
            """
            const resolved = await urls({ tool: "${server.uri("/tool")}" });
            export default { executable: resolved.tool, arguments: context.args };
            """.trimIndent()
        )
        val run = Run(
            executablePath = { tempDir / "run" },
            windows = false,
            operatingSystem = "linux",
            architecture = "x86_64"
        )
        val cache = (tempDir / "run-cache").createDirectories()
        val exitCode = CommandLine(run).setStopAtPositional(true).execute(
            "--cache-dir", cache.toString(),
            "--progress=never",
            directory.toString(),
            output.toString()
        )

        assertEquals(0, exitCode)
        assertEquals(output.toString() + "\n", output.readText())
    }

    @Test
    fun `run adds an omitted exe suffix on Windows`() = withServer { server ->
        val zip = zipOf("tool-1.0/bin/tool.exe" to "windows executable")
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/tool.zip")
                exchange.respond(zip)
            else
                exchange.respond404()
        }

        makeCache().use { cache ->
            val resolver = URLResolver(cache)
            resolveRunURL(resolver, server.uri("/tool.zip/tool-1.0/bin/tool"), windows = true).use { resolved ->
                assertEquals("tool.exe", resolved.path.fileName.toString())
                assertEquals("windows executable", resolved.path.readText())
            }
            assertFailsWith<IllegalArgumentException> {
                resolveRunURL(resolver, server.uri("/tool.zip/tool-1.0/bin/tool"), windows = false)
            }
        }
    }

    @Test
    fun `cel verifier JavaScript produces portable launch plans`() {
        val packageFile = Path.of("site/r/cel-verifier/run.zip.d/run.js").toAbsolutePath()
        val packageDir = packageFile.parent
        val java = tempDir / "java"
        val javaExe = tempDir / "java.exe"
        val verifier = tempDir / "verifier.jar"

        val macUrls = Collections.synchronizedList(mutableListOf<String>())
        val macPlan = evaluateRunJavaScript(
            packageFile,
            RunContext("macos", "arm64", null, listOf("input.cel"), packageDir)
        ) { url ->
            macUrls += url
            if ("verifier-cli" in url) verifier else java
        }
        assertTrue(macUrls.single { "graalvm" in it }.contains(
            "graalvm-jdk-25_macos-aarch64_bin.tar.gz/graalvm-jdk-25.0.4+7.1/Contents/Home/bin/java#sha256="
        ))
        assertTrue(macUrls.single { "verifier-cli" in it }.endsWith(
            "/0.14.0/verifier-cli-0.14.0.jar#sha256=25dae07dedab8b5997c3f08b798ce432ed8115bd8e782630c2db4dfaff064c2b"
        ))
        assertEquals(java, macPlan.executable)
        assertEquals(
            listOf(
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                verifier.toString(),
                "input.cel"
            ),
            macPlan.arguments
        )

        val windowsUrls = Collections.synchronizedList(mutableListOf<String>())
        val windowsPlan = evaluateRunJavaScript(
            packageFile,
            RunContext("windows", "x86_64", "0.14.0", emptyList(), packageDir)
        ) { url ->
            windowsUrls += url
            if ("verifier-cli" in url) verifier else javaExe
        }
        assertTrue(windowsUrls.single { "graalvm" in it }.contains(
            "graalvm-jdk-25_windows-x64_bin.zip/graalvm-jdk-25.0.4+7.1/bin/java#sha256="
        ))
        assertTrue(windowsUrls.single { "verifier-cli" in it }.endsWith(
            "/0.14.0/verifier-cli-0.14.0.jar#sha256=25dae07dedab8b5997c3f08b798ce432ed8115bd8e782630c2db4dfaff064c2b"
        ))
        assertEquals(javaExe, windowsPlan.executable)
        assertEquals(
            listOf(
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                verifier.toString()
            ),
            windowsPlan.arguments
        )

        val unlockedUrls = Collections.synchronizedList(mutableListOf<String>())
        evaluateRunJavaScript(
            packageFile,
            RunContext("windows", "x86_64", "1.2.3", emptyList(), packageDir)
        ) { url ->
            unlockedUrls += url
            if ("verifier-cli" in url) verifier else javaExe
        }
        assertTrue(unlockedUrls.single { "verifier-cli" in it }.endsWith(
            "/1.2.3/verifier-cli-1.2.3.jar"
        ))

        val unsupportedOs = assertFailsWith<RuntimeException> {
            evaluateRunJavaScript(
                packageFile,
                RunContext("freebsd", "x86_64", null, emptyList(), packageDir),
                resolve = { error("unused") }
            )
        }
        assertContains(unsupportedOs.message.orEmpty(), "Unsupported GraalVM platform: freebsd-x64")
    }

    @Test
    fun `unknown GraalVM versions are not hashlocked`() {
        val sourceDirectory = Path.of("site/r/cel-verifier/run.zip.d").toAbsolutePath()
        val directory = (tempDir / "run.zip.d").createDirectories()
        Files.copy(sourceDirectory / "graalvm.js", directory / "graalvm.js")
        Files.copy(sourceDirectory / "utils.js", directory / "utils.js")
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            import { graalvm } from "./graalvm.js";
            export default { executable: await graalvm("25.0.5+7.2"), arguments: [] };
            """.trimIndent()
        )
        var requested = ""

        evaluateRunJavaScript(
            packageFile,
            RunContext("linux", "x86_64", null, emptyList(), directory)
        ) { url ->
            requested = url
            tempDir / "java"
        }

        assertContains(requested, "/graalvm-jdk-25_linux-x64_bin.tar.gz/graalvm-jdk-25.0.5+7.2/bin/java")
        assertFalse(requested.contains("#sha256="))
    }

    @Test
    fun `Windows launch plan executables may omit the exe suffix`() {
        val bin = (tempDir / "bin").createDirectories()
        (bin / "tool.exe").writeText("")
        (bin / "tool-1.2.exe").writeText("")
        (bin / "both").writeText("")
        (bin / "both.exe").writeText("")

        assertEquals(bin / "tool.exe", launchExecutable(bin / "tool", windows = true))
        assertEquals(bin / "tool-1.2.exe", launchExecutable(bin / "tool-1.2", windows = true))
        assertEquals(bin / "tool.exe", launchExecutable(bin / "tool.exe", windows = true))
        assertEquals(bin / "both", launchExecutable(bin / "both", windows = true))
        assertEquals(bin / "missing", launchExecutable(bin / "missing", windows = true))
        assertEquals(bin / "tool", launchExecutable(bin / "tool", windows = false))
        assertEquals(Path.of("tool"), launchExecutable(Path.of("tool"), windows = true))
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

        assertEquals(0, runResolvedPath(script, listOf("--help", "two words")))
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

        extractSafeLocalArchive(archive, destination)

        assertEquals("contents", (destination / "root/bin/tool").readText())
        assertTrue(Files.isExecutable(destination / "root/bin/tool"))
    }

    @Test
    fun `streaming extraction rejects writes through chained archive symlinks`() {
        val archive = maliciousSymlinkTarGz()
        val destination = tempDir / "streamed"

        assertFailsWith<IllegalArgumentException> {
            extractStreamingTar(ByteArrayInputStream(archive), destination)
        }
        assertFalse(Files.exists(tempDir / "payload", java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `streaming extraction writes child entries through safe archive symlinks`() {
        val archive = tarGzWithLinkedChild()
        val destination = tempDir / "streamed"

        extractStreamingTar(ByteArrayInputStream(archive), destination)

        assertEquals("payload", (destination / "d/c").readText())
        assertEquals("payload", (destination / "a/b/c").readText())
    }

    @Test
    fun `streaming extraction accepts tar root directory entry`() {
        val archive = ByteArrayOutputStream().use { bytes ->
            TarArchiveOutputStream(bytes).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("./"))
                tar.closeArchiveEntry()
                val contents = "payload".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("./file").apply { size = contents.size.toLong() })
                tar.write(contents)
                tar.closeArchiveEntry()
            }
            bytes.toByteArray()
        }
        val destination = tempDir / "streamed"

        extractStreamingTar(ByteArrayInputStream(archive), destination)

        assertEquals("payload", (destination / "file").readText())
    }

    @Test
    fun `local extraction rejects writes through chained archive symlinks`() {
        val archive = tempDir / "attack.tar.gz"
        archive.writeBytes(maliciousSymlinkTarGz())
        val destination = tempDir / "local"

        assertFailsWith<IllegalArgumentException> {
            extractSafeLocalArchive(archive, destination)
        }
        assertFalse(Files.exists(tempDir / "payload", java.nio.file.LinkOption.NOFOLLOW_LINKS))
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

    private fun maliciousSymlinkTarGz(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
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

    private fun tarGzWithLinkedChild(): ByteArray = ByteArrayOutputStream().use { bytes ->
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

    private fun Path.macExtendedAttributes(): Set<String> =
        xattr(toString()).lineSequence().filter(String::isNotEmpty).toSet()

    private fun Path.macExtendedAttribute(name: String): String {
        val hex = xattr("-px", name, toString()).filterNot(Char::isWhitespace)
        return HexFormat.of().parseHex(hex).toString(StandardCharsets.UTF_8)
    }

    private fun xattr(vararg arguments: String): String {
        val process = ProcessBuilder("/usr/bin/xattr", *arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
