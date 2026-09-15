import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("hydraulic.kotlin-common-conventions")
    id("org.graalvm.buildtools.native") version "1.1.9"
    kotlin("kapt")
    application
}

// Oracle's last JDK 25 build for macOS Intel is 25.0.1. Truffle Native Image
// features require the embedded language artifacts to match that compiler.
val graalVersion = if (
    System.getProperty("os.name") == "Mac OS X" &&
    System.getProperty("os.arch") in setOf("x86_64", "amd64")
) "25.0.1" else "25.0.4"

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
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.polyglot:js:$graalVersion") {
        // Run plans are tiny and short-lived. Do not carry Truffle's
        // optimizing runtime and compiler into the native executable.
        exclude(group = "org.graalvm.truffle", module = "truffle-runtime")
        exclude(group = "org.graalvm.truffle", module = "truffle-enterprise")
    }
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
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
        // JavaScript launch plans only need Truffle's interpreter. The
        // dependency exclusions above remove the optimizing runtime; this
        // property makes the native-image choice explicit as well.
        buildArgs.add("-Dtruffle.UseFallbackRuntime=true")
        buildArgs.add("-Dpolyglot.engine.WarnInterpreterOnly=false")
        buildArgs.add("-Os")
        buildArgs.add("--initialize-at-run-time=hydraulic,org.tinylog")
        buildArgs.add("--initialize-at-run-time=org.jline.nativ,org.jline.terminal.impl.jni")
    }
}
