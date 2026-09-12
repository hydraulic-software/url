import java.nio.file.Files

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("kapt") version "2.4.10"
    id("org.graalvm.buildtools.native") version "1.1.9"
    application
}

repositories { mavenCentral() }

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
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("info.picocli:picocli:4.7.6")
    kapt("info.picocli:picocli-codegen:4.7.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

application {
    mainClass.set("hydraulic.url.URLKt")
    applicationName = "url"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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
    }
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
