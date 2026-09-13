import java.nio.file.Files
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("kapt") version "2.4.10"
    id("org.graalvm.buildtools.native") version "1.1.9"
    application
}

repositories { mavenCentral() }

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
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    implementation("org.tinylog:tinylog-api-kotlin:2.7.0")
    implementation("org.tinylog:tinylog-impl:2.7.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    implementation("de.malkusch.whois-server-list:public-suffix-list:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0-RC")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
    implementation("io.airlift:aircompressor-v3:3.6")
    // 3.1.0 fixes an AArch64 macOS native-image crash caused by calling the
    // variadic ioctl entry point with the wrong ABI when detecting TTY size.
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.pkl-lang:pkl-core:0.32.1")
    implementation("org.graalvm.sdk:nativeimage:25.0.4")
    kapt("info.picocli:picocli-codegen:4.7.6")

    add(pklNativeImageSupport.compileOnlyConfigurationName, "org.graalvm.nativeimage:svm:25.0.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
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

kotlin { jvmToolchain(25) }
tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries.named("main") {
        imageName.set("run")
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
        jvmArgs(application.applicationDefaultJvmArgs)
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
        providers.gradleProperty("nativePgoProfile").orNull?.let { buildArgs.add("--pgo=$it") }
        if (providers.gradleProperty("nativePgoInstrument").isPresent) buildArgs.add("--pgo-instrument")
    }
}

tasks.register<JavaExec>("pgoTrainingData") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("hydraulic.url.PgoTrainingDataKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(providers.gradleProperty("pgoTrainingDirectory").get())
    })
}

tasks.register("nativePair") {
    dependsOn("nativeCompile")
    val nativeDirectory = layout.buildDirectory.dir("native/nativeCompile")
    outputs.files(nativeDirectory.map { it.file("url") }, nativeDirectory.map { it.file("run") })
    doLast {
        val run = nativeDirectory.get().file("run").asFile.toPath()
        val url = nativeDirectory.get().file("url").asFile.toPath()
        Files.deleteIfExists(url)
        Files.createLink(url, run)
    }
}
