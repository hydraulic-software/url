package hydraulic.url

import kotlin.io.path.div
import com.sun.net.httpserver.HttpExchange
import hydraulic.diskcache.LocalDiskCache
import hydraulic.diskcache.http.HttpResourceCache
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComposeTest {
    @TempDir
    lateinit var tempDir: Path

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val macOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    // Composer behaviour

    @Test
    fun `copies merge, replace and remove in order`() {
        val base = source("base", "bin/tool" to "old tool", "bin/unwanted" to "unwanted", "plugins/legacy/a" to "legacy",
            "plugins/kept/b" to "kept", "README" to "readme")
        val patch = source("patch", "bin/tool" to "new tool", "plugins/fresh/c" to "fresh")

        val app = compose(
            listOf(
                copy(base, to = "."),
                ComposeOperation.Remove("plugins/legacy"),
                ComposeOperation.Remove("bin/unwanted"),
                copy(patch, select = "bin/tool", to = "bin/tool"),
            ),
            base, patch
        )

        assertEquals("new tool", (app / "bin/tool").readText())
        assertFalse((app / "bin/unwanted").exists())
        assertFalse((app / "plugins/legacy").exists())
        assertEquals("kept", (app / "plugins/kept/b").readText())
        assertEquals("readme", (app / "README").readText())
        // Composition never modifies its sources.
        assertEquals("old tool", (base.path / "bin/tool").readText())
        assertTrue((base.path / "plugins/legacy/a").exists())
    }

    @Test
    fun `directory copies merge unless replace is set`() {
        val base = source("base", "plugins/old" to "old", "plugins/shared" to "base")
        val patch = source("patch", "plugins/new" to "new", "plugins/shared" to "patch")

        val merged = compose(listOf(copy(base, to = "."), copy(patch, select = "plugins", to = "plugins")), base, patch)
        assertEquals(setOf("old", "new", "shared"), names(merged / "plugins"))
        assertEquals("patch", (merged / "plugins/shared").readText())

        val replaced = compose(
            listOf(copy(base, to = "."), copy(patch, select = "plugins", to = "plugins", replace = true)), base, patch
        )
        assertEquals(setOf("new", "shared"), names(replaced / "plugins"))

        val cleared = compose(listOf(copy(base, to = "."), copy(patch, select = "plugins", to = ".", replace = true)), base, patch)
        assertEquals(setOf("new", "shared"), names(cleared))
    }

    @Test
    fun `files and directories replace each other`() {
        val first = source("first", "thing" to "file", "dir/inner" to "inner")
        val second = source("second", "thing/child" to "now a directory", "dir" to "now a file")

        val result = compose(listOf(copy(first, to = "."), copy(second, to = ".")), first, second)

        assertEquals("now a directory", (result / "thing/child").readText())
        assertEquals("now a file", (result / "dir").readText())
    }

    @Test
    fun `empty directories are copied and file sources need an exact destination`() {
        val base = source("base", "file" to "contents")
        (base.path / "empty/nested").createDirectories()
        val file = ComposeSource("test:file", base.path / "file", base.path)

        val result = compose(listOf(copy(base, to = "out"), copy(file, to = "copies/renamed")), base, file)

        assertTrue((result / "out/empty/nested").isDirectory())
        assertEquals("contents", (result / "copies/renamed").readText())
        assertFailure("'to' must name a file") { compose(listOf(copy(file, to = ".")), file) }
        assertFailure("'select' must be \".\"") { compose(listOf(copy(file, select = "x", to = "x")), file) }
        assertFailure("selection does not exist") { compose(listOf(copy(base, select = "missing", to = "x")), base) }
    }

    @Test
    fun `removal handles missing paths, the root and file ancestors`() {
        val base = source("base", "a/b" to "b", "file" to "file", "keep" to "keep")

        val result = compose(
            listOf(
                copy(base, to = "."),
                ComposeOperation.Remove("missing/path"),
                ComposeOperation.Remove("file/child"),
                ComposeOperation.Remove("a"),
            ),
            base
        )
        assertEquals(setOf("file", "keep"), names(result))

        val emptied = compose(listOf(copy(base, to = "."), ComposeOperation.Remove(".")), base)
        assertTrue(emptied.isDirectory())
        assertEquals(emptySet(), names(emptied))
    }

    @Test
    fun `writing beneath a file is an error`() {
        val base = source("base", "bin" to "a file")
        assertFailure("destination ancestor is a file") {
            compose(listOf(copy(base, to = "."), copy(base, select = "bin", to = "bin/tool")), base)
        }
    }

    @Test
    fun `paths are validated and normalized`() {
        val base = source("base", "a/b" to "b")
        for (bad in listOf("/abs", "a\\b", "../up", "a/../b", "C:/drive", "c:relative"))
            assertFailsWith<IllegalArgumentException>(bad) { compose(listOf(copy(base, select = bad, to = "x")), base) }
        assertFailsWith<IllegalArgumentException> { compose(listOf(copy(base, to = "")), base) }

        val plain = compose(listOf(copy(base, select = "a/b", to = "out/b")), base)
        val redundant = compose(listOf(copy(base, select = "./a//b/.", to = "out/./b")), base)
        assertEquals(plain, redundant)
        val defaulted = compose(listOf(ComposeOperation.Copy(from = base.path.toString(), to = ".")), base)
        val explicit = compose(listOf(ComposeOperation.Copy(from = base.path.toString(), select = ".", to = ".", replace = false)), base)
        assertEquals(defaulted, explicit)
    }

    @Test
    fun `compositions are cached by recipe and source identity`() {
        val base = source("base", "file" to "one")
        val first = compose(listOf(copy(base, to = ".")), base)
        // Changing the files under the same identity shows the cached assembly is reused rather than rebuilt.
        (base.path / "file").writeText("two")
        assertEquals(first, compose(listOf(copy(base, to = ".")), base))
        assertEquals("one", (first / "file").readText())

        val newRevision = base.copy(identity = "test:base-revision-2")
        val second = compose(listOf(copy(newRevision, to = ".")), newRevision)
        assertNotEquals(first, second)
        assertEquals("two", (second / "file").readText())

        val reordered = compose(listOf(copy(base, to = "."), ComposeOperation.Remove("file")), base)
        assertNotEquals(first, reordered)
    }

    @Test
    fun `sources must be known, have an identity and stay inside their entry`() {
        val base = source("base", "file" to "x")
        assertFailure("is not a path returned by url(), urls() or compose()") {
            Composer(makeCache(), quarantine = false, minimumFreeSpaceBytes = 0).compose(listOf(copy(base, to = "."))) { null }
        }
        val anonymous = base.copy(identity = null)
        assertFailure("cannot be used as a composition source") { compose(listOf(copy(anonymous, to = ".")), anonymous) }

        assumeFalse(windows)
        val outside = source("outside", "secret" to "secret")
        Files.createSymbolicLink(base.path / "escape", outside.path)
        assertFailure("escapes its cache entry") { compose(listOf(copy(base, select = "escape/secret", to = "secret")), base) }
    }

    @Test
    fun `compositions can be composition sources`() {
        val base = source("base", "a" to "a")
        val patch = source("patch", "b" to "b")
        val cache = makeCache()
        val composer = Composer(cache, quarantine = false, minimumFreeSpaceBytes = 0)
        val sources = mutableMapOf(base.path.toString() to base, patch.path.toString() to patch)
        val (key, inner) = composer.compose(listOf(copy(base, to = "."))) { sources[it] }
        inner.use {
            sources[inner.path.toString()] = ComposeSource(composer.identityOf(key), inner.path, inner.path)
            val (_, outer) = composer.compose(
                listOf(ComposeOperation.Copy(from = inner.path.toString(), to = "."), copy(patch, to = "."))
            ) { sources[it] }
            outer.use { assertEquals(setOf("a", "b"), names(outer.path)) }
        }
    }

    @Test
    fun `symbolic links are preserved and validated after all operations`() {
        assumeFalse(windows)
        val base = source("base", "lib/real" to "library")
        Files.createSymbolicLink(base.path / "lib/alias", Path.of("real"))
        Files.createSymbolicLink(base.path / "dangling", Path.of("lib/missing"))

        // A dangling link fails unless a later operation removes it or supplies its target.
        assertFailure("dangling -> lib/missing is invalid") { compose(listOf(copy(base, to = ".")), base) }
        val removed = compose(listOf(copy(base, to = "."), ComposeOperation.Remove("dangling")), base)
        assertEquals(Path.of("real"), Files.readSymbolicLink(removed / "lib/alias"))
        assertEquals("library", (removed / "lib/alias").readText())
        val supplier = source("supplier", "missing" to "supplied")
        val supplied = compose(listOf(copy(base, to = "."), copy(supplier, to = "lib")), base, supplier)
        assertEquals("supplied", (supplied / "dangling").readText())

        // Selecting a link copies the link, which must still resolve inside the assembly.
        assertFailure("is invalid") { compose(listOf(copy(base, select = "lib/alias", to = "alias")), base) }
        val withTarget = compose(listOf(copy(base, select = "lib/alias", to = "alias"), copy(base, select = "lib/real", to = "real")), base)
        assertTrue((withTarget / "alias").isSymbolicLink())
    }

    @Test
    fun `absolute, escaping and cyclic links are errors`() {
        assumeFalse(windows)
        val absolute = source("absolute")
        Files.createSymbolicLink(absolute.path / "link", tempDir)
        assertFailure("it is absolute") { compose(listOf(copy(absolute, to = ".")), absolute) }

        val escaping = source("escaping", "dir/file" to "x")
        Files.createSymbolicLink(escaping.path / "dir/link", Path.of("../../outside"))
        assertFailure("escapes the assembled directory") { compose(listOf(copy(escaping, to = ".")), escaping) }

        val cyclic = source("cyclic")
        Files.createSymbolicLink(cyclic.path / "a", Path.of("b"))
        Files.createSymbolicLink(cyclic.path / "b", Path.of("a"))
        assertFailure("cyclic") { compose(listOf(copy(cyclic, to = ".")), cyclic) }
    }

    @Test
    fun `links in destination ancestors are errors but exact destinations may be replaced`() {
        assumeFalse(windows)
        val base = source("base", "real/file" to "x")
        Files.createSymbolicLink(base.path / "link", Path.of("real"))
        val patch = source("patch", "file" to "patched")

        assertFailure("destination ancestor is a symbolic link") {
            compose(listOf(copy(base, to = "."), copy(patch, select = "file", to = "link/file")), base, patch)
        }
        assertFailure("removal ancestor is a symbolic link") {
            compose(listOf(copy(base, to = "."), ComposeOperation.Remove("link/file")), base)
        }
        val replaced = compose(listOf(copy(base, to = "."), copy(patch, select = "file", to = "link")), base, patch)
        assertFalse((replaced / "link").isSymbolicLink())
        assertEquals("patched", (replaced / "link").readText())
        val unlinked = compose(listOf(copy(base, to = "."), ComposeOperation.Remove("link")), base)
        assertFalse((unlinked / "link").exists())
        assertTrue((unlinked / "real/file").exists())
    }

    @Test
    fun `intermediate selection links are followed`() {
        assumeFalse(windows)
        val bundle = source("bundle", "Contents/Home/bin/java" to "java")
        Files.createSymbolicLink(bundle.path / "Home", Path.of("Contents/Home"))

        val result = compose(listOf(copy(bundle, select = "Home/bin", to = "bin")), bundle)

        assertEquals("java", (result / "bin/java").readText())
    }

    @Test
    fun `names differing only by case or normalization collide`() {
        val upper = source("upper", "README" to "upper")
        val lower = source("lower", "readme" to "lower")
        assertFailure("differs only in case") { compose(listOf(copy(upper, to = "."), copy(lower, to = ".")), upper, lower) }

        val composed = source("nfc", "\u00e9" to "composed")
        val decomposed = source("nfd", "e\u0301" to "decomposed")
        // Normalization-insensitive filesystems cannot hold both spellings, so this source only exists elsewhere.
        if (names(decomposed.path) == setOf("e\u0301") && names(composed.path) == setOf("\u00e9"))
            assertFailure("differs only in case") {
                compose(listOf(copy(composed, to = "."), copy(decomposed, to = ".")), composed, decomposed)
            }
    }

    @Test
    fun `copies are independent and preserve permissions`() {
        val base = source("base", "bin/tool" to "#!/bin/sh\n", "readonly" to "data")
        val posix = Files.getFileAttributeView(base.path, java.nio.file.attribute.PosixFileAttributeView::class.java) != null
        if (posix) {
            Files.setPosixFilePermissions(base.path / "bin/tool", PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.setPosixFilePermissions(base.path / "readonly", PosixFilePermissions.fromString("r--r--r--"))
        }

        val result = compose(listOf(copy(base, to = ".")), base)

        if (posix) {
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(result / "bin/tool"))
            assertEquals(PosixFilePermissions.fromString("r--r--r--"), Files.getPosixFilePermissions(result / "readonly"))
            assertNotEquals(Files.getAttribute(base.path / "bin/tool", "unix:ino"), Files.getAttribute(result / "bin/tool", "unix:ino"))
        }
        // Read-only copies can still be replaced by later operations.
        val patch = source("patch", "readonly" to "replaced")
        val overlaid = compose(listOf(copy(base, to = "."), copy(patch, to = ".")), base, patch)
        assertEquals("replaced", (overlaid / "readonly").readText())
    }

    @Test
    fun `macOS compositions follow the Gatekeeper setting`() {
        if (!macOS)
            return
        val base = source("base", "bin/tool" to "tool")
        (base.path / "bin/tool").quarantineTree()
        val sources = mapOf(base.path.toString() to base)
        val cache = makeCache()

        Composer(cache, quarantine = true, minimumFreeSpaceBytes = 0).compose(listOf(copy(base, to = "."))) { sources[it] }.second.use {
            for (path in listOf(it.path, it.path / "bin", it.path / "bin/tool"))
                assertTrue(path.hasQuarantine(), "$path")
        }
        Composer(cache, quarantine = false, minimumFreeSpaceBytes = 0).compose(listOf(copy(base, to = "."))) { sources[it] }.second.use {
            for (path in listOf(it.path, it.path / "bin", it.path / "bin/tool"))
                assertFalse(path.hasQuarantine(), "$path")
        }
    }

    // run.js recipe validation

    @Test
    fun `run js compose recipes are checked before composition`() {
        val directory = (tempDir / "package").createDirectories()
        fun failure(recipe: String): String {
            val packageFile = directory / "run.js"
            packageFile.writeText("await compose($recipe);\nexport default { executable: \"x\", arguments: [] };")
            return assertFailsWith<RuntimeException>(recipe) {
                evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = { Path.of("/unused") }) {
                    error("unused")
                }
            }.message.orEmpty()
        }
        assertContains(failure("[]"), "non-empty array")
        assertContains(failure("{}"), "non-empty array")
        assertContains(failure("[42]"), "must be an object")
        assertContains(failure("[{remove: \"a\", to: \"b\"}]"), "only a 'remove' property")
        assertContains(failure("[{remove: \"\"}]"), "non-empty string")
        assertContains(failure("[{from: \"a\", to: \"b\", extra: 1}]"), "unknown properties: extra")
        assertContains(failure("[{to: \"b\"}]"), "requires a 'from' property")
        assertContains(failure("[{from: \"a\"}]"), "requires a 'to' property")
        assertContains(failure("[{from: \"a\", to: \"b\", select: 3}]"), "'select' must be a non-empty string")
        assertContains(failure("[{from: \"a\", to: \"b\", replace: \"yes\"}]"), "'replace' must be a boolean")
    }

    @Test
    fun `run js compose passes operations and returns the path`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const app = await compose([{from: "/a", to: "."}, {remove: "x"}, {from: Promise.resolve("/b"), select: "s", to: "t", replace: true}]);
            export default { executable: app, arguments: [] };
            """.trimIndent()
        )
        var received: List<ComposeOperation>? = null
        val result = tempDir / "composed"
        val plan = evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
            received = it
            result
        }) { error("unused") }

        assertEquals(
            listOf(
                ComposeOperation.Copy(from = "/a", to = "."),
                ComposeOperation.Remove("x"),
                ComposeOperation.Copy(from = "/b", select = "s", to = "t", replace = true),
            ),
            received
        )
        assertEquals(result.toAbsolutePath().normalize(), plan.executable)
    }

    @Test
    fun `run js compose uses the recipe as it was when called`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const recipe = [{from: Promise.resolve("/a"), to: "."}];
            const composed = compose(recipe);
            recipe[0].to = "changed";
            recipe.push({remove: "x"});
            export default { executable: await composed, arguments: [] };
            """.trimIndent()
        )
        var received: List<ComposeOperation>? = null
        evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
            received = it
            tempDir / "composed"
        }) { error("unused") }

        assertEquals(listOf(ComposeOperation.Copy(from = "/a", to = ".")), received)
    }

    @Test
    fun `run js evaluation waits for compositions that are never awaited`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            compose([{from: url("https://example.com/slow"), to: "."}]);
            export default { executable: "x", arguments: [] };
            """.trimIndent()
        )
        var received: List<ComposeOperation>? = null
        evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
            received = it
            tempDir / "composed"
        }) {
            Thread.sleep(200)
            Path.of("/cache/slow")
        }

        assertEquals(listOf(ComposeOperation.Copy(from = Path.of("/cache/slow").toString(), to = ".")), received)
    }

    @Test
    fun `run js compose sources that never settle fail evaluation`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            compose([{from: new Promise(() => {}), to: "."}]);
            export default { executable: "x", arguments: [] };
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalStateException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
                error("unused")
            }) { error("unused") }
        }
        assertContains(failure.message.orEmpty(), "never settles")
    }

    @Test
    fun `run js compose failures reading the recipe reject its promise`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            const message = await compose([{get from() { throw new Error("getter failed") }, to: "."}]).catch(e => e.message);
            export default { executable: "x", arguments: [message] };
            """.trimIndent()
        )
        val plan = evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
            error("unused")
        }) { error("unused") }

        assertEquals(listOf("getter failed"), plan.arguments)
    }

    @Test
    fun `run js compose source failures are reported with the original exception`() {
        val directory = (tempDir / "package").createDirectories()
        val packageFile = directory / "run.js"
        packageFile.writeText(
            """
            await compose([{from: url("https://example.com/missing"), to: "."}]);
            export default { executable: "x", arguments: [] };
            """.trimIndent()
        )
        val failure = IllegalStateException("not found")
        val thrown = assertFailsWith<IllegalStateException> {
            evaluateRunJavaScript(packageFile, RunContext("linux", "x86_64", null, emptyList(), directory), compose = {
                error("unused")
            }) { throw failure }
        }

        assertSame(failure, thrown)
    }

    // Content revisions

    @Test
    fun `content revisions change only when a new body is accepted`() = withServer { server ->
        var body = "one"
        val bodies = AtomicInteger()
        server.createContext("/file") { exchange ->
            val etag = "\"$body\""
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("ETag", etag)
            if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
            } else {
                bodies.incrementAndGet()
                exchange.respondBytes(body.toByteArray(), cache = false)
            }
        }
        makeCache().use { cache ->
            fun revision(refresh: Boolean = false) =
                URLResolver(cache, refresh = refresh).resolve(server.uri("/file")).use { assertNotNull(it.contentRevision) }

            val first = revision()
            assertEquals(first, revision(), "a 304 keeps the revision")
            body = "two"
            val second = revision()
            assertNotEquals(first, second, "a new body gets a new revision")
            assertNotEquals(second, revision(refresh = true), "refresh accepts a new body")
        }
        assertEquals(3, bodies.get())
    }

    @Test
    fun `archive members inherit the archive revision`() = withServer { server ->
        val zip = zipOf("tool/bin/tool" to "tool")
        val tarball = tarGzOf("tool/bin/tool" to "tool")
        server.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/tool.zip" -> exchange.respondBytes(zip)
                "/tool.tar.gz" -> exchange.respondBytes(tarball)
                else -> { exchange.sendResponseHeaders(404, -1); exchange.close() }
            }
        }
        makeCache().use { cache ->
            for (archive in listOf("/tool.zip", "/tool.tar.gz")) {
                val member = URLResolver(cache).resolve(server.uri("$archive/tool/bin/tool")).use { it.contentRevision }
                val root = URLResolver(cache).resolve(server.uri("$archive/")).use { it.contentRevision }
                assertNotNull(member, archive)
                assertEquals(member, root, archive)
            }
        }
    }

    @Test
    fun `entries cached without a revision receive a stable one`() = withServer { server ->
        server.createContext("/legacy") { it.respondBytes("legacy".toByteArray()) }
        makeCache().use { cache ->
            // An entry written directly by the HTTP cache library has no revision, as entries from older versions do not.
            HttpResourceCache(cache).resolve(server.uri("/legacy")).use { assertFalse(CONTENT_REVISION_METADATA in it.metadata) }
            val first = URLResolver(cache).resolve(server.uri("/legacy")).use { it.contentRevision }
            assertNotNull(first)
            assertEquals(first, URLResolver(cache).resolve(server.uri("/legacy")).use { it.contentRevision })
        }
    }

    // End to end

    @Test
    fun `run composes resolved archives and launches from the assembly`() = withServer { server ->
        assumeFalse(windows)
        var patchVersion = "patched 1"
        server.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/base.zip" -> exchange.respondBytes(unixZipOf(
                    Triple("app/bin/tool", "#!/bin/sh\ncat \"$(dirname \"$0\")/../message\" > \"$1\"\n", "100755".toInt(8)),
                    Triple("app/message", "base", "100644".toInt(8)),
                    Triple("app/plugins/legacy", "legacy", "100644".toInt(8)),
                ))
                "/patch.zip" -> {
                    // Revalidated on every run, so a changed body is noticed.
                    val etag = "\"$patchVersion\""
                    exchange.responseHeaders.add("Cache-Control", "no-cache")
                    exchange.responseHeaders.add("ETag", etag)
                    if (exchange.requestHeaders.getFirst("If-None-Match") == etag) {
                        exchange.sendResponseHeaders(304, -1)
                        exchange.close()
                    } else {
                        exchange.respondBytes(zipOf("message" to patchVersion), cache = false)
                    }
                }
                else -> { exchange.sendResponseHeaders(404, -1); exchange.close() }
            }
        }
        val directory = (tempDir / "package").createDirectories()
        (directory / "run.js").writeText(
            """
            // Sources are passed as promises, so composition starts once both resolve.
            const base = url("${server.uri("/base.zip/app/")}");
            const patch = url("${server.uri("/patch.zip/")}");
            const app = await compose([
              {from: base, to: "."},
              {remove: "plugins/legacy"},
              {from: patch, select: "message", to: "message"},
            ]);
            export default { executable: `${'$'}{app}/bin/tool`, arguments: [...context.args] };
            """.trimIndent()
        )
        val cacheDir = tempDir / "run-cache"
        fun run(output: Path): Int = CommandLine(Run(executablePath = { tempDir / "run" }, windows = false))
            .setStopAtPositional(true)
            .execute("--cache-dir", cacheDir.toString(), "--progress=never", directory.toString(), output.toString())

        val first = tempDir / "first"
        assertEquals(0, run(first))
        assertEquals("patched 1", first.readText())
        val compositions = { compositionCount(cacheDir) }
        assertEquals(1, compositions())

        // A 304 keeps the patch's revision, so the same composition is reused.
        val second = tempDir / "second"
        assertEquals(0, run(second))
        assertEquals("patched 1", second.readText())
        assertEquals(1, compositions())

        patchVersion = "patched 2"
        val third = tempDir / "third"
        assertEquals(0, run(third))
        assertEquals("patched 2", third.readText())
        assertEquals(2, compositions())
    }

    @Test
    fun `run fails without launching when composition fails`() = withServer { server ->
        server.createContext("/base.zip") { it.respondBytes(zipOf("app/file" to "x")) }
        val directory = (tempDir / "package").createDirectories()
        val marker = tempDir / "launched"
        (directory / "run.js").writeText(
            """
            const {base} = await urls({base: "${server.uri("/base.zip/")}"});
            await compose([{from: base, select: "missing", to: "x"}]);
            export default { executable: "touch", arguments: ["$marker"] };
            """.trimIndent()
        )
        val exitCode = CommandLine(Run(executablePath = { tempDir / "run" }, windows = windows))
            .setStopAtPositional(true)
            .execute("--cache-dir", (tempDir / "run-cache").toString(), "--progress=never", directory.toString())
        assertNotEquals(0, exitCode)
        assertFalse(marker.exists())
    }

    // Helpers

    private fun compositionCount(cacheDir: Path): Int = Files.walk(cacheDir).use { paths ->
        paths.filter { it.fileName.toString() == "key.md" && it.readText().startsWith("Composition\n") }.count().toInt()
    }

    private fun source(name: String, vararg files: Pair<String, String>): ComposeSource {
        val root = (tempDir / "sources" / name).createDirectories().toRealPath()
        for ((path, contents) in files) {
            val file = root.resolve(path)
            file.parent.createDirectories()
            file.writeText(contents)
        }
        return ComposeSource("test:$name", root, root)
    }

    private fun copy(source: ComposeSource, select: String = ".", to: String, replace: Boolean = false) =
        ComposeOperation.Copy(from = source.path.toString(), select = select, to = to, replace = replace)

    private val sharedCache by lazy { makeCache() }

    /** Composes with a shared cache and returns the assembled path. Leases are released at once, which tests may ignore. */
    private fun compose(operations: List<ComposeOperation>, vararg sources: ComposeSource): Path {
        val byPath = sources.associateBy { it.path.toString() }
        val (_, result) = Composer(sharedCache, quarantine = false, minimumFreeSpaceBytes = 0).compose(operations) { byPath[it] }
        result.close()
        return result.path
    }

    private fun assertFailure(message: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertContains(failure.message.orEmpty(), message)
    }

    private fun names(directory: Path): Set<String> = Files.list(directory).use { stream ->
        stream.map { it.fileName.toString() }.toList().toSet()
    }

    private fun Path.hasQuarantine(): Boolean {
        val process = ProcessBuilder("/usr/bin/xattr", "-p", "com.apple.quarantine", toString()).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor() == 0
    }

    private fun makeCache(): LocalDiskCache = makeCache(tempDir / "cache-${System.nanoTime()}")

    private fun HttpExchange.respondBytes(bytes: ByteArray, cache: Boolean = true) {
        if (cache)
            responseHeaders.add("Cache-Control", "max-age=3600")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** A ZIP whose entries carry Unix modes, so scripts inside extracted directories are executable. */
    private fun unixZipOf(vararg files: Triple<String, String, Int>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipArchiveOutputStream(bytes).use { zip ->
            for ((name, contents, mode) in files) {
                zip.putArchiveEntry(ZipArchiveEntry(name).apply { unixMode = mode })
                zip.write(contents.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        bytes.toByteArray()
    }

}
