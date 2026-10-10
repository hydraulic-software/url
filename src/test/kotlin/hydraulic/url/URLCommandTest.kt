package hydraulic.url

import kotlin.io.path.div
import com.sun.net.httpserver.HttpServer
import hydraulic.diskcache.http.HttpTransport
import hydraulic.utils.os.OperatingSystemPaths
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class URLCommandTest : URLTestSupport() {
    @Test
    fun `default cache directory uses the application namespace`() {
        assertEquals(OperatingSystemPaths.current("dev.hydraulic", "url-tool").localCache, URL().resolverOptions.cacheDirectory)
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
            resolverOptions.cacheDirectory = (tempDir / "assignment-cache").createDirectories()
            resolverOptions.progress = "never"
            resolverOptions.downloadPolicy = DownloadPolicy().apply { minimumFreeSpaceMB = 0 }
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

}
