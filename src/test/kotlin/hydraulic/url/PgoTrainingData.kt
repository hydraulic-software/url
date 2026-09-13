package hydraulic.url

import io.airlift.compress.v3.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/** Creates a realistic, local archive used to train release native binaries. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected the output directory" }
    val outputDirectory = Path.of(args.single()).toAbsolutePath().createDirectories()
    val modules = Path.of(System.getProperty("java.home")) / "lib" / "modules"
    require(Files.isRegularFile(modules)) { "JDK modules image not found at $modules" }

    ZstdOutputStream(Files.newOutputStream(outputDirectory / "jdk-modules.tar.zst")).use { zstd ->
        TarArchiveOutputStream(zstd).use { tar ->
            val entry = TarArchiveEntry("root/modules").apply {
                size = Files.size(modules)
                mode = 0b110_100_100
                modTime = Date(0)
            }
            tar.putArchiveEntry(entry)
            Files.copy(modules, tar)
            tar.closeArchiveEntry()
        }
    }
}
