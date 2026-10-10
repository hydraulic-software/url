package hydraulic.url

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.nio.file.Path

internal data class StdioPath(val descriptor: Int, val path: Path)

/** Resolve inherited regular-file and terminal descriptors; pipes have no filesystem path. */
internal fun inheritedMacOSStdio(): List<StdioPath> = if (Platform.isMacOS) MacOSStdio.paths() else emptyList()

private object MacOSStdio {
    private const val F_GETPATH = 50
    private const val MAXPATHLEN = 1024
    private val linker = Linker.nativeLinker()
    private val layouts = linker.canonicalLayouts()
    private val fcntl = linker.downcallHandle(linker.defaultLookup().find("fcntl").orElseThrow(),
        FunctionDescriptor.of(layouts.getValue("int"), layouts.getValue("int"), layouts.getValue("int"),
            layouts.getValue("void*")), Linker.Option.firstVariadicArg(2))

    fun paths(): List<StdioPath> = Arena.ofConfined().use { arena ->
        (0..2).mapNotNull { descriptor ->
            val buffer = arena.allocate(MAXPATHLEN.toLong())
            val result = fcntl.invokeWithArguments(descriptor, F_GETPATH, buffer) as Int
            if (result == 0) StdioPath(descriptor, Path.of(buffer.getString(0))) else null
        }
    }
}
