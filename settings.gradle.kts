pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "hydraulic.url"
rootProject.buildFileName = "build-standalone.gradle.kts"
