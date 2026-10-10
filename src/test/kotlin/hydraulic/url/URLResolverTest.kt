package hydraulic.url

import kotlin.io.path.div
import hydraulic.diskcache.http.HttpResourceCache
import hydraulic.diskcache.http.HttpTransport
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
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

class URLResolverTest : URLTestSupport() {
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
    fun `hash lock on a nested archive file authenticates that file`() = withServer { server ->
        val innerZip = zipOf("file.txt" to "nested contents")
        val outerZip = zipOfBytes("inner.zip" to innerZip)
        server.createContext("/") {
            if (it.requestURI.path == "/outer.zip") it.respond(outerZip) else it.respond404()
        }
        makeCache().use { cache ->
            URLResolver(cache).resolve(server.uri("/outer.zip/inner.zip#sha256=${innerZip.sha256()}"))
                .use { assertEquals(innerZip.sha256(), it.path.sha256()) }
            val mismatch = assertFailsWith<IllegalArgumentException> {
                URLResolver(cache).resolve(server.uri("/outer.zip/inner.zip#sha256=${outerZip.sha256()}"))
            }
            assertContains(mismatch.message.orEmpty(), "SHA-256 mismatch")
            URLResolver(cache).resolve(server.uri("/outer.zip/inner.zip"))
                .use { assertEquals(innerZip.sha256(), it.path.sha256()) }
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

}
