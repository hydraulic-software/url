val applicationVersion = providers.gradleProperty("urlVersion").orElse(
    project.version.toString().takeUnless { it == "unspecified" } ?: "development"
).get()
version = applicationVersion

val generatedVersionDirectory = layout.buildDirectory.dir("generated/sources/version/main")
val generatedVersionFile = generatedVersionDirectory.map { it.file("hydraulic/url/Version.kt") }
val generateVersionSource = tasks.register("generateVersionSource") {
    inputs.property("version", applicationVersion)
    outputs.file(generatedVersionFile)
    doLast {
        val versionLiteral = applicationVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        generatedVersionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package hydraulic.url\n\ninternal const val VERSION = \"$versionLiteral\"\n")
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateVersionSource)
}
tasks.matching { it.name == "kaptGenerateStubsKotlin" }.configureEach {
    dependsOn(generateVersionSource)
}

