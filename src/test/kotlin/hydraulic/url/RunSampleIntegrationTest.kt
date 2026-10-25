package hydraulic.url

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the timestamped archives produced by packageRunSamples, through the real `run` CLI. */
@Tag("run-sample-integration")
class RunSampleIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val samplesDirectory: Path
        get() = Path.of(System.getProperty("run.samples.directory"))

    private fun invokeRun(name: String, args: List<String>): Pair<Int, String> {
        val archive = samplesDirectory.resolve(name).resolve("run.zip")
        assertTrue(Files.isRegularFile(archive), "Missing packaged sample archive: $archive")
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", if (Platform.isWindows) "java.exe" else "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            "hydraulic.url.RunKt",
            "--no-gatekeeper",
            "--progress", "never",
            "--cache-dir", tempDir.resolve("cache-$name").toString(),
            archive.toString(),
            *args.toTypedArray(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    private fun runSample(name: String, vararg args: String): String {
        val (exitCode, output) = invokeRun(name, args.toList())
        assertEquals(0, exitCode, "`run` failed for $name:\n$output")
        return output
    }

    @Test
    fun `cel verifier archive launches its help`() {
        assertContains(runSample("cel-verifier", "--help").lowercase(), "usage")
    }

    @Test
    fun `demo archive forwards arguments`() {
        val output = runSample("demo", "--format=esm", "src file/index.js")
        assertContains(output, "Hello from run.zip.")
        assertContains(output, "argument: --format=esm")
        assertContains(output, "argument: src file/index.js")
    }

    @Test
    fun `esbuild wasm archive runs its version command`() {
        assertContains(runSample("esbuild-wasm", "--version"), "0.28.2")
    }

    @Test
    fun `jinfer archive launches its help`() {
        assertContains(runSample("jinfer", "--help").lowercase(), "usage")
    }

    @Test
    fun `make run zip archive creates a timestamped archive`() {
        val source = samplesDirectory.resolve("demo/run.zip.d")
        val output = tempDir.resolve("packaged-demo.zip")
        runSample("make-run-zip", source.toString(), output.toString())

        assertTrue(Files.isRegularFile(output), "make-run-zip did not create $output")
        ZipFile(output.toFile()).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            assertTrue("timestamp.tsr" in names, "Archive has no RFC 3161 timestamp")
            assertTrue("run.js" in names, "Archive has no run.js")
        }
    }
}
