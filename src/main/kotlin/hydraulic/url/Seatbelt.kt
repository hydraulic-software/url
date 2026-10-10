package hydraulic.url

import java.nio.file.Files
import java.nio.file.Path

/** All caller-controlled paths go through sandbox-exec parameters, never SBPL source text. */
internal data class SeatbeltProfile(val source: String, val parameters: Map<String, String>)

internal val SEATBELT_SYSTEM_PATHS = listOf("/System", "/usr/lib", "/usr/bin", "/bin", "/usr/share",
    "/private/etc", "/private/var/select", "/private/var/db/dyld", "/Library/Apple/System")

internal fun seatbeltProfile(grant: SandboxGrant): SeatbeltProfile {
    val parameters = linkedMapOf<String, String>()
    val source = buildString {
        appendLine("(version 1)")
        appendLine("(deny default)")
        appendLine("(allow process-fork process-exec)")
        appendLine("(allow sysctl-read)")
        // Traversal and readlink are required even when the final grant uses a canonical path.
        appendLine("(allow file-read-metadata (vnode-type DIRECTORY SYMLINK))")
        appendLine("(allow file-read* (vnode-type SYMLINK))")
        appendLine("(allow signal (target self))")
        fun path(operations: String, path: Path, recursive: Boolean) {
            val name = "PATH_${parameters.size}"
            parameters[name] = path.toString()
            appendLine("(allow $operations (${if (recursive) "subpath" else "literal"} (param \"$name\")))")
            if (path.parent != null) appendLine("(allow file-read-metadata (path-ancestors (param \"$name\")))")
        }
        SEATBELT_SYSTEM_PATHS.forEach { path("file-read* file-map-executable", Path.of(it), true) }
        path("file-read*", Path.of("/"), false)
        // getcwd needs to traverse the current directory's ancestors. Grant directory entries only.
        path("file-read*", grant.workingDirectory, false)
        var ancestor = grant.workingDirectory.parent
        while (ancestor != null) {
            path("file-read*", ancestor, false)
            ancestor = ancestor.parent
        }
        grant.baselineRead.forEach { path("file-read* file-map-executable", it, true) }
        grant.read.forEach { path("file-read* file-map-executable", it.path, it.missing || Files.isDirectory(it.path)) }
        grant.write.forEach { path("file-read* file-write* file-map-executable", it.path, it.missing || Files.isDirectory(it.path)) }
        path("file-read* file-write* file-map-executable", grant.temporaryDirectory, true)
        grant.stdio.forEach { stdio ->
            path(if (stdio.descriptor == 0) "file-read* file-ioctl" else "file-read-metadata file-write-data file-ioctl",
                stdio.path, false)
        }
        appendLine("(allow file-read* file-write-data file-ioctl (literal \"/dev/null\") (literal \"/dev/tty\") (regex #\"^/dev/tty[^/]*$\") (subpath \"/dev/fd\"))")
        appendLine("(allow file-read* (literal \"/dev/random\") (literal \"/dev/urandom\"))")
        // These two lookups support getpwuid without allowing Launch Services or arbitrary Mach services.
        appendLine("(allow mach-lookup (global-name \"com.apple.system.opendirectoryd.libinfo\") (global-name \"com.apple.system.opendirectoryd.membership\"))")
        if (grant.requested.networkClient == true) {
            appendLine("(allow network-outbound)")
            appendLine("(allow mach-lookup (global-name \"com.apple.dnssd.service\") (global-name \"com.apple.trustd\") (global-name \"com.apple.trustd.agent\"))")
        }
        if (grant.requested.networkServer == true) appendLine("(allow network-inbound network-bind)")
    }
    return SeatbeltProfile(source, parameters)
}

internal fun seatbeltCommand(executable: Path, arguments: List<String>, grant: SandboxGrant): List<String> {
    val profile = seatbeltProfile(grant)
    val profileFile = grant.temporaryDirectory.resolve("sandbox.sb")
    Files.writeString(profileFile, profile.source)
    return buildList {
        add("/usr/bin/sandbox-exec")
        add("-f")
        add(profileFile.toString())
        profile.parameters.forEach { (key, value) -> add("-D"); add("$key=$value") }
        add(executable.toString())
        addAll(arguments)
    }
}

/** Inspect the actual executable too: a symlink must not disguise an app bundle. */
internal fun isAppBundleExecutable(executable: Path): Boolean {
    fun inBundle(path: Path) = path.any { it.toString().endsWith(".app", ignoreCase = true) }
    return inBundle(executable.toAbsolutePath()) ||
        runCatching { inBundle(executable.toRealPath()) }.getOrDefault(false)
}
