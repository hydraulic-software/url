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
    implementation("dev.progress4j:progress-api")
    implementation(libs.info.picocli)
    kapt(libs.info.picocli.codegen)
    runtimeOnly(libs.org.tinylog.impl)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    testImplementation(libs.org.apache.commons.compress)
}

application {
    mainClass.set("hydraulic.url.URLKt")
    applicationName = "url"
}

graalvmNative {
    binaries.named("main") {
        imageName.set("url")
        mainClass.set(application.mainClass)
        sharedLibrary.set(false)
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.matching("GraalVM Community"))
        })
    }
}
