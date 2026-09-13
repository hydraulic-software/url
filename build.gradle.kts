import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("hydraulic.kotlin-common-conventions")
    id("org.graalvm.buildtools.native") version "1.1.9"
    kotlin("kapt")
    application
}

val pklNativeImageSupport = sourceSets.create("pklNativeImageSupport")

configurations[pklNativeImageSupport.implementationConfigurationName].extendsFrom(
    configurations.implementation.get()
)
configurations.named("nativeImageClasspath") {
    exclude("com.github.ajalt.mordant", "mordant-jvm-ffm")
    exclude("com.github.ajalt.mordant", "mordant-jvm-ffm-jvm")
    exclude("com.github.ajalt.mordant", "mordant-jvm-jna")
    exclude("com.github.ajalt.mordant", "mordant-jvm-jna-jvm")
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
    implementation("org.pkl-lang:pkl-core:0.32.1")
    implementation("org.graalvm.sdk:nativeimage:25.0.4")
    kapt(libs.info.picocli.codegen)

    add(pklNativeImageSupport.compileOnlyConfigurationName, "org.graalvm.nativeimage:svm:25.0.1")
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
        classpath.from(pklNativeImageSupport.output)
        // Pkl's native executable initializes the language implementation at
        // build time. Its SVM substitutions reset build-machine thread state
        // before the image is written; application state remains runtime-only.
        buildArgs.add("-H:+UnlockExperimentalVMOptions")
        // Pkl launch plans are tiny and short-lived. Use Truffle's fallback
        // interpreter so the native image does not carry runtime JIT support.
        buildArgs.add("-Dtruffle.UseFallbackRuntime=true")
        buildArgs.add("-Dpolyglot.engine.WarnInterpreterOnly=false")
        buildArgs.add("-Os")
        buildArgs.add("--initialize-at-build-time=")
        buildArgs.add("--initialize-at-run-time=hydraulic,org.tinylog")
        buildArgs.add("--initialize-at-run-time=org.msgpack.core.buffer.DirectBufferAccess")
        buildArgs.add("--initialize-at-run-time=org.pkl.core.util.BaseDirectory,org.pkl.core.util.BaseDirectories,org.pkl.core.util.DebugLogger")
        buildArgs.add("--initialize-at-run-time=org.jline.nativ,org.jline.terminal.impl.jni")
        buildArgs.add("--no-fallback")
        buildArgs.add("-H:IncludeResources=org/pkl/core/stdlib/.*\\.pkl")
        buildArgs.add("-H:IncludeResourceBundles=org.pkl.core.errorMessages")
        buildArgs.add("-H:IncludeResourceBundles=org.pkl.parser.errorMessages")
    }
}
