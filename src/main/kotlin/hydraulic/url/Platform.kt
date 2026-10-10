package hydraulic.url

import java.util.Locale

/** Platform names shared by native file handling and the run.js context. */
internal object Platform {
    val os: String get() = operatingSystem()
    val isWindows: Boolean get() = os == "windows"
    val isMacOS: Boolean get() = os == "macos"

    fun operatingSystem(
        osName: String = System.getProperty("os.name"),
        runtimeName: String = System.getProperty("java.runtime.name", "")
    ): String = when {
        runtimeName.contains("android", ignoreCase = true) -> "android"
        osName.startsWith("Linux", ignoreCase = true) -> "linux"
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> "macos"
        osName.startsWith("FreeBSD", ignoreCase = true) -> "freebsd"
        osName.startsWith("Windows", ignoreCase = true) -> "windows"
        else -> osName.lowercase(Locale.ROOT)
    }
}
