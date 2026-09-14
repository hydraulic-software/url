import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("hydraulic.kotlin-common-conventions")
    id("org.graalvm.buildtools.native") version "1.1.9"
    kotlin("kapt")
    application
}

dependencies {
    api(project(":hydraulic.diskcache"))
    implementation(project(":hydraulic.archives"))
    implementation(project(":hydraulic.utils"))
    implementation(project(":hydraulic.utils.hashing"))
    implementation("io.airlift:aircompressor-v3:3.6")
    implementation("dev.progress4j:progress-api")
    implementation("dev.progress4j:progress-terminal")
    implementation("dev.progress4j:progress-utils")
    // Mordant 3.1.0 fixes its macOS/AArch64 native-image ioctl ABI, which
    // otherwise corrupts terminal dimensions and can segfault during redraw.
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation(libs.info.picocli)
    implementation("org.graalvm.polyglot:polyglot:25.0.4")
    implementation("org.graalvm.polyglot:js:25.0.4")
    kapt(libs.info.picocli.codegen)

    runtimeOnly(libs.org.tinylog.impl)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    testImplementation(libs.org.apache.commons.compress)
}

application {
    mainClass.set("hydraulic.url.URLKt")
    applicationName = "url"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

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

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedVersionDirectory)
}
tasks.named("compileKotlin") {
    dependsOn(generateVersionSource)
}
tasks.matching { it.name == "kaptGenerateStubsKotlin" }.configureEach {
    dependsOn(generateVersionSource)
}

val runStartScripts = tasks.register<CreateStartScripts>("runStartScripts") {
    applicationName = "run"
    mainClass = "hydraulic.url.RunKt"
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    defaultJvmOpts = application.applicationDefaultJvmArgs
    outputDir = layout.buildDirectory.dir("scripts").get().asFile
    unixScriptFile = layout.buildDirectory.file("scripts/run")
    windowsScriptFile = layout.buildDirectory.file("scripts/run.bat")
}

distributions {
    named("main") {
        contents {
            from(runStartScripts.map { it.unixScriptFile.get().asFile }) {
                into("bin")
                eachFile { path = name }
            }
            from(runStartScripts.map { it.windowsScriptFile.get().asFile }) {
                into("bin")
                eachFile { path = name }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries.named("main") {
        imageName.set("url")
        mainClass.set(application.mainClass)
        sharedLibrary.set(false)
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(
                if (providers.gradleProperty("oracleGraalVM").isPresent)
                    JvmVendorSpec.ORACLE
                else
                    JvmVendorSpec.matching("GraalVM Community")
            )
        })
        buildArgs.add("-Os")
        buildArgs.add("--initialize-at-run-time=hydraulic,org.tinylog")
        buildArgs.add("--initialize-at-run-time=org.jline.nativ,org.jline.terminal.impl.jni")
    }
}
