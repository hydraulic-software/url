package hydraulic.url

import kotlin.io.path.div
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
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

class RunTest : URLTestSupport() {
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
        assertEquals("linux", Platform.operatingSystem("Linux"))
        assertEquals("macos", Platform.operatingSystem("Mac OS X"))
        assertEquals("freebsd", Platform.operatingSystem("FreeBSD"))
        assertEquals("android", Platform.operatingSystem("Linux", "Android Runtime"))
        assertEquals("windows", Platform.operatingSystem("Windows 11"))
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
            resolveRunURLWithEffectiveURI(resolver, server.uri("/tool.zip/tool-1.0/bin/tool"), windows = true).first.use { resolved ->
                assertEquals("tool.exe", resolved.path.fileName.toString())
                assertEquals("windows executable", resolved.path.readText())
            }
            assertFailsWith<IllegalArgumentException> {
                resolveRunURLWithEffectiveURI(resolver, server.uri("/tool.zip/tool-1.0/bin/tool"), windows = false).first
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
        assertTrue(url.resolverOptions.refresh)

        val run = Run(executablePath = { tempDir / "run" }, windows = false)
        CommandLine(run).setStopAtPositional(true).parseArgs("--refresh", "https://example.com")
        assertTrue(run.resolverOptions.refresh)
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

}
