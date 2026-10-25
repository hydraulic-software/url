package hydraulic.url

import java.util.Locale
import java.nio.file.Path

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

/** The user's platform cache root; discovering it neither creates nor grants access to it. */
internal fun userCacheDirectory(
    operatingSystem: String = Platform.os,
    home: Path = Path.of(System.getProperty("user.home")),
    environment: Map<String, String> = System.getenv()
): Path {
    fun absoluteOverride(name: String, ignoreCase: Boolean = false): Path? {
        val value = environment.entries.firstOrNull { it.key.equals(name, ignoreCase) }?.value
        if (value.isNullOrBlank()) return null
        return runCatching { Path.of(value) }.getOrNull()?.takeIf { it.isAbsolute }
    }
    return when (operatingSystem) {
        "macos" -> home.resolve("Library/Caches")
        "windows" -> absoluteOverride("LOCALAPPDATA", ignoreCase = true) ?: home.resolve("AppData/Local")
        else -> absoluteOverride("XDG_CACHE_HOME") ?: home.resolve(".cache")
    }.toAbsolutePath().normalize()
}
