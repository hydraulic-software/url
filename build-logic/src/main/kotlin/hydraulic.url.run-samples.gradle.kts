import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test

val samplesDirectory = layout.projectDirectory.dir("site/r")
val sampleOutput = layout.buildDirectory.dir("run-packages")
val assembleRunSamples = tasks.register("assembleRunSamples") {
    group = "samples"
    description = "Assembles self-contained development directories for all run samples."
}
val packageRunSamples = tasks.register("packageRunSamples") {
    group = "samples"
    description = "Builds all sample run.zip archives."
}

// Dynamically generates build and distribution tasks for samples
samplesDirectory.asFile.listFiles().orEmpty()
    .filter { it.resolve("run.zip.d").isDirectory }
    .sortedBy { it.name }
    .forEach { sample ->
        val taskSuffix = sample.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val manifest = samplesDirectory.file("${sample.name}/shared-files.txt")
        // Resolves and validates shared helper file paths
        val helpers = providers.fileContents(manifest).asText.get().lineSequence()
            .map(String::trim).filter(String::isNotEmpty).map { name ->
                require(name != "." && name != ".." && '/' !in name && '\\' !in name) {
                    "Shared files must be plain filenames: $name"
                }
                samplesDirectory.file("shared/$name").asFile.also {
                    require(it.isFile) { "Missing shared helper: $it" }
                }
            }.toList()
        val assembled = tasks.register<Sync>("assemble${taskSuffix}RunSample") {
            group = "samples"
            description = "Assembles the ${sample.name} run sample with its shared helpers."
            inputs.file(manifest)
            from(sample.resolve("run.zip.d"))
            from(helpers)
            exclude("**/context.d.ts", "**/timestamp.tsr")
            duplicatesStrategy = DuplicatesStrategy.FAIL
            into(sampleOutput.map { it.dir("${sample.name}/run.zip.d") })
        }
        val archive = tasks.register<Zip>("package${taskSuffix}RunSample") {
            group = "samples"
            description = "Builds the ${sample.name} run.zip archive."
            from(assembled)
            archiveFileName.set("run.zip")
            destinationDirectory.set(sampleOutput.map { it.dir(sample.name) })
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            // Gradle 9 defaults archives to 0644. Retain executable shell scripts.
            useFileSystemPermissions()
        }
        assembleRunSamples.configure { dependsOn(assembled) }
        packageRunSamples.configure { dependsOn(archive) }
    }

tasks.withType<Test>().configureEach {
    dependsOn(packageRunSamples)
    systemProperty("run.samples.directory", sampleOutput.get().asFile.absolutePath)
}
