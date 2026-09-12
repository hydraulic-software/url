import org.gradle.api.tasks.testing.Test

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
    }
}
