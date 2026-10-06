package hydraulic.url

import hydraulic.diskcache.LocalDiskCache
import hydraulic.diskcache.http.HttpTransport
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityLimitsTest {
    @TempDir lateinit var directory: Path

    @Test fun `new and existing cache directories are private on POSIX`() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
        val cache = directory.resolve("parent/cache")
        preparePrivateCacheDirectory(cache)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(cache))
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxrwxrwx"))
        preparePrivateCacheDirectory(cache)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(cache))
    }

    @Test fun `private cache setup rejects a symlink without changing its target`() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
        val target = directory.resolve("target").createDirectories()
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        val link = directory.resolve("link")
        Files.createSymbolicLink(link, target)
        assertFailsWith<IllegalArgumentException> { preparePrivateCacheDirectory(link) }
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(target))
    }

    @Test fun `streamed tar and local tar and zip remove group and other write bits`() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
        val entries = listOf("tool" to "payload")
        val tar = tar(entries, mode = 511)
        val zip = archiveZip(entries, mode = 511)
        extractStreamingTar(ByteArrayInputStream(tar), directory.resolve("streamed"))
        for ((name, contents) in listOf("tar.gz" to tar, "zip" to zip)) {
            val archive = directory.resolve("archive.$name").apply { writeBytes(contents) }
            extractSafeLocalArchive(archive, directory.resolve(name))
        }
        for (name in listOf("streamed", "tar.gz", "zip")) {
            val root = directory.resolve(name)
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root))
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(root.resolve("tool")))
            assertEquals("payload", root.resolve("tool").readText())
        }
    }

    @Test fun `manifest rejects literal Unix backslashes and accepts directory separators`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = directory.resolve("package").createDirectories()
        val literal = root.resolve("a\\b.js").apply { writeText("signed bytes") }
        assertFailsWith<IllegalArgumentException> { canonicalRunManifest(root) }
        root.resolve("a").createDirectories()
        Files.move(literal, root.resolve("a/b.js"))
        assertContains(canonicalRunManifest(root).toString(Charsets.UTF_8), "  a/b.js\n")
    }

    @Test fun `Unix package builder rejects backslashes before requesting a timestamp`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(ProcessBuilder("sh", "-c", "command -v openssl").start().waitFor() == 0)
        val source = directory.resolve("source").createDirectories()
        source.resolve("a\\b.js").writeText("payload")
        val process = ProcessBuilder("sh", "site/r/make-run-zip/run.zip.d/make-run-zip.sh", "unused",
            source.toString(), directory.resolve("run.zip").toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(2, process.waitFor())
        assertContains(output, "backslashes")
        assertFalse(Files.exists(directory.resolve("run.zip")))
    }

    @Test fun `download stops when free space drops during the response`() {
        var available = 200L
        val response = transport("abcdefgh".toByteArray(), minimum = 100, usable = { available })
            .get(URI("https://example.test/file"), emptyMap())
        response.body.use { body ->
            assertEquals(4, body.read(ByteArray(4)))
            available = 103
            assertFailsWith<IllegalStateException> { body.read(ByteArray(4)) }
        }
    }

    @Test fun `download checks space when bytes are skipped`() {
        var available = 200L
        val response = transport("abcdef".toByteArray(), usable = { available })
            .get(URI("https://example.test/file"), emptyMap())
        response.body.use { body ->
            assertEquals(3, body.skip(3))
            available = 100
            assertFailsWith<IllegalStateException> { body.skip(3) }
        }
    }

    @Test fun `disabled free space guard permits downloads and extraction without checking space`() {
        transport(byteArrayOf(1, 2, 3), minimum = 0, usable = { error("Space guard is disabled") })
            .get(URI("https://example.test/file"), emptyMap()).body.use {
                assertTrue(it.readAllBytes().contentEquals(byteArrayOf(1, 2, 3)))
            }
        extractStreamingTar(ByteArrayInputStream(tar(listOf("a" to "payload"))), directory.resolve("disabled"),
            minimumFreeSpaceBytes = 0, usableSpace = { error("Space guard is disabled") })
        assertEquals("payload", directory.resolve("disabled/a").readText())
    }

    @Test fun `streamed tar and local tar and zip stop as expanded data consumes free space`() {
        val entries = listOf("first" to "1234", "second" to "5678")
        val tar = tar(entries)
        for (name in listOf("streamed", "tar.gz", "zip")) {
            val destination = directory.resolve(name)
            val available = {
                val written = Files.walk(destination).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
                }
                107L - written
            }
            assertFailsWith<IllegalStateException> {
                if (name == "streamed") {
                    extractStreamingTar(ByteArrayInputStream(tar), destination, 100, available)
                } else {
                    val bytes = if (name == "zip") archiveZip(entries) else tar
                    val archive = directory.resolve("archive.$name").apply { writeBytes(bytes) }
                    extractSafeLocalArchive(archive, destination, 100, available)
                }
            }
            assertEquals("1234", destination.resolve("first").readText())
            assertFalse(Files.exists(destination.resolve("second")) && Files.size(destination.resolve("second")) > 0)
        }
    }

    @Test fun `tar and zip check space before creating directory entries`() {
        val entries = listOf("a/" to "", "b/" to "")
        for (name in listOf("streamed", "zip")) {
            val destination = directory.resolve(name)
            val available = { if (Files.exists(destination.resolve("a"))) 99L else 200L }
            assertFailsWith<IllegalStateException> {
                if (name == "streamed") {
                    extractStreamingTar(ByteArrayInputStream(tar(entries)), destination, 100, available)
                } else {
                    val archive = directory.resolve("archive.zip").apply { writeBytes(archiveZip(entries)) }
                    extractSafeLocalArchive(archive, destination, 100, available)
                }
            }
            assertTrue(Files.isDirectory(destination.resolve("a")))
            assertFalse(Files.exists(destination.resolve("b")))
        }
    }

    @Test fun `extraction checks actual bytes and available space before each chunk written`() {
        val output = ByteArrayOutputStream()
        var available = 200L
        val space = DiskSpaceGuard(100) { available }
        space.copy(ByteArrayInputStream(ByteArray(50)), output)
        available = 120
        assertFailsWith<IllegalStateException> { space.copy(ByteArrayInputStream(ByteArray(50)), output) }
        assertEquals(50, output.size())
    }

    @Test fun `failed streamed extraction is not cached and removes partial files`() {
        val bytes = tar(listOf("first" to "1234", "second" to "5678"))
        val cacheDirectory = directory.resolve("cache")
        LocalDiskCache(cacheDirectory, DownloadPolicy().cacheConfiguration()).open().use { cache ->
            val guardedCache = CompleteEntryDiskCache(cache)
            assertFailsWith<IllegalStateException> {
                guardedCache.getAndCustomizeEntry("extraction", false) { destination ->
                    extractStreamingTar(ByteArrayInputStream(bytes), destination, 100) {
                        if (Files.exists(destination.resolve("first"))) 100 else 200
                    }
                    hydraulic.diskcache.DiskCache.EntryComputationResult()
                }
            }
            assertNull(cache.lookup("extraction"))
            Files.walk(cacheDirectory).use { files -> assertFalse(files.anyMatch { it.fileName.toString() == "first" }) }
        }
    }

    @Test fun `failed downloads remove partial files and do not publish a cache entry`() {
        val cacheDirectory = directory.resolve("cache")
        LocalDiskCache(cacheDirectory, DownloadPolicy().cacheConfiguration()).open().use { cache ->
            val body = object : ByteArrayInputStream("abcdef".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, minOf(length, 3))
            }
            val transport = MinimumFreeSpaceHttpTransport(
                HttpTransport { _, _ -> HttpTransport.Response(200, emptyMap(), body) }, 100, {
                    val partialExists = Files.walk(cacheDirectory).use { paths ->
                        paths.anyMatch { it.fileName.toString() == "payload" && Files.size(it) >= 3 }
                    }
                    if (partialExists) 100 else 200
                }
            )
            assertFailsWith<IllegalStateException> {
                URLResolver(cache, transport = transport).resolve(URI("https://example.test/payload"))
            }
            Files.walk(cacheDirectory).use { files -> assertFalse(files.anyMatch { it.fileName.toString() == "payload" }) }
        }
    }

    @Test fun `url keeps ordinary failed build diagnostics and discards only space refusals`() {
        val configuration = DownloadPolicy().cacheConfiguration()
        assertTrue(configuration.discardFailedBuilds(InsufficientDiskSpaceException("not enough space")))
        assertFalse(configuration.discardFailedBuilds(IllegalStateException("bad archive")))
        val failure = IllegalArgumentException("invalid package")
        var partial: Path? = null
        LocalDiskCache(directory.resolve("diagnostic-cache"), configuration).open().use { cache ->
            val actual = assertFailsWith<IllegalArgumentException> {
                cache.getAndCustomizeEntry("invalid", false) { destination ->
                    partial = destination.resolve("diagnostic")
                    partial!!.writeText("partial build")
                    throw failure
                }
            }
            assertTrue(actual === failure)
            assertEquals("partial build", partial!!.readText())
            assertNull(cache.lookup("invalid"))
        }
    }

    @Test fun `HTTP 304 copies check free space and preserve the existing cached body on failure`() {
        var requests = 0
        val transport = HttpTransport { _, _ ->
            requests++
            if (requests == 1) HttpTransport.Response(200,
                mapOf("Cache-Control" to listOf("no-cache"), "ETag" to listOf("\"v1\"")),
                ByteArrayInputStream("payload".toByteArray()))
            else HttpTransport.Response(304, emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        LocalDiskCache(directory.resolve("cache")).open().use { cache ->
            val uri = URI("https://example.test/file")
            val original = URLResolver(cache, transport = transport).resolve(uri).use { it.path }
            assertFailsWith<IllegalStateException> {
                URLResolver(cache, transport = transport,
                    minimumFreeSpaceBytes = Long.MAX_VALUE).resolve(uri)
            }
            assertEquals(2, requests)
            assertEquals("payload", original.readText())
        }
    }

    @Test fun `ZIP absolute entry names are rejected before TAR name normalization`() {
        val archive = directory.resolve("absolute.zip").apply { writeBytes(archiveZip(listOf("/payload" to "data"))) }
        assertFailsWith<IllegalArgumentException> { extractSafeLocalArchive(archive, directory.resolve("extracted")) }
    }

    private fun transport(bytes: ByteArray, minimum: Long = 100,
        usable: () -> Long = { 1000 }) = MinimumFreeSpaceHttpTransport(
        HttpTransport { _, _ -> HttpTransport.Response(200, emptyMap(), ByteArrayInputStream(bytes)) },
        minimum, usable
    )

    private fun tar(entries: List<Pair<String, String>>, mode: Int = 420): ByteArray {
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                for ((name, content) in entries) {
                    val payload = content.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = payload.size.toLong(); this.mode = mode })
                    tar.write(payload)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun archiveZip(entries: List<Pair<String, String>>, mode: Int = 420): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putArchiveEntry(ZipArchiveEntry(name).apply {
                    unixMode = (if (name.endsWith('/')) UnixStat.DIR_FLAG else UnixStat.FILE_FLAG) or mode
                })
                zip.write(content.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
