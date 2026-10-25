import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

val samplesDirectory = layout.projectDirectory.dir("site/r")
val sampleOutput = layout.buildDirectory.dir("run-packages")
val assembleRunSamples = tasks.register("assembleRunSamples") {
    group = "samples"
    description = "Assembles source directories for all run samples with their shared helpers."
}
val packageRunSamples = tasks.register("packageRunSamples") {
    group = "samples"
    description = "Builds timestamped run.zip archives for all run samples."
}
val publishRunSamples = tasks.register("publishRunSamples") {
    group = "samples"
    description = "Packages all run samples and copies their archives into site/r for publishing."
    dependsOn(packageRunSamples)
    doLast {
        samplesDirectory.asFile.listFiles().orEmpty()
            .filter { it.resolve("run.zip.d").isDirectory }
            .forEach { sample ->
                val archive = sampleOutput.get().file("${sample.name}/run.zip").asFile
                require(archive.isFile) { "Missing packaged sample archive: $archive" }
                archive.copyTo(sample.resolve("run.zip"), overwrite = true)
            }
    }
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
            description = "Assembles the ${sample.name} source directory with its shared helpers."
            inputs.file(manifest)
            from(sample.resolve("run.zip.d"))
            from(helpers)
            exclude("**/context.d.ts", "**/timestamp.tsr", "**/node_modules/**")
            duplicatesStrategy = DuplicatesStrategy.FAIL
            into(sampleOutput.map { it.dir("${sample.name}/run.zip.d") })
        }
        assembleRunSamples.configure { dependsOn(assembled) }

        val packageSource = sampleOutput.map { it.dir("${sample.name}/package-source") }
        val npmWork = layout.buildDirectory.dir("run-npm/${sample.name}")
        val packageJson = sample.resolve("run.zip.d/package.json")
        val packageLock = sample.resolve("run.zip.d/package-lock.json")
        val installDependencies = if (packageJson.isFile && packageLock.isFile) {
            tasks.register<Exec>("install${taskSuffix}RunSampleDependencies") {
                group = "samples"
                description = "Installs locked npm dependencies for the ${sample.name} run sample."
                dependsOn(assembled)
                inputs.files(packageJson, packageLock)
                outputs.dir(npmWork.map { it.dir("node_modules") })
                doFirst {
                    val work = npmWork.get().asFile
                    work.mkdirs()
                    packageJson.copyTo(work.resolve("package.json"), overwrite = true)
                    packageLock.copyTo(work.resolve("package-lock.json"), overwrite = true)
                }
                workingDir(npmWork)
                commandLine("npm", "ci")
            }
        } else null

        val preparedPackage = tasks.register<Sync>("prepare${taskSuffix}RunPackage") {
            group = "samples"
            description = "Stages the ${sample.name} sample and its npm dependencies for packaging."
            dependsOn(assembled)
            if (installDependencies != null) dependsOn(installDependencies)
            from(sampleOutput.map { it.dir("${sample.name}/run.zip.d") })
            if (installDependencies != null) {
                from(npmWork.map { it.dir("node_modules") }) { into("node_modules") }
            }
            into(packageSource)
        }

        plugins.withId("application") {
            val mainRuntimeClasspath = extensions.getByType<SourceSetContainer>()
                .named("main").get().runtimeClasspath
            val packageArchive = sampleOutput.map { it.file("${sample.name}/run.zip") }
            val packageSample = tasks.register<JavaExec>("package${taskSuffix}RunSample") {
                group = "samples"
                description = "Creates a timestamped run.zip for the ${sample.name} sample."
                dependsOn(preparedPackage, "classes")
                classpath = mainRuntimeClasspath
                mainClass.set("hydraulic.url.RunKt")
                args(
                    sampleOutput.get().file("make-run-zip/run.zip.d").asFile.absolutePath,
                    packageSource.get().asFile.absolutePath,
                    packageArchive.get().asFile.absolutePath
                )
                inputs.dir(packageSource)
                outputs.file(packageArchive)
            }
            packageRunSamples.configure { dependsOn(packageSample) }
        }
    }

tasks.withType<Test>().configureEach {
    dependsOn(assembleRunSamples)
    systemProperty("run.samples.directory", sampleOutput.get().asFile.absolutePath)
}
