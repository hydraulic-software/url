package hydraulic.url

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/** Applies quarantine through Core Foundation's supported URL resource-property API. */
internal object MacOSQuarantine {
    fun apply(path: Path) {
        Arena.ofConfined().use { arena ->
            val nativePath = arena.allocateFrom(path.toAbsolutePath().toString())
            val fileURL = CF_URL_CREATE_FROM_FILE_SYSTEM_REPRESENTATION.invokeWithArguments(
                MemorySegment.NULL, nativePath, nativePath.byteSize() - 1, 0.toByte()
            ) as MemorySegment
            check(fileURL != MemorySegment.NULL) { "Core Foundation could not create a file URL for $path" }

            try {
                if (!hasQuarantineProperties(fileURL, arena))
                    setQuarantineProperties(fileURL, arena)
            } finally {
                CF_RELEASE.invokeWithArguments(fileURL)
            }
        }
    }

    private fun hasQuarantineProperties(fileURL: MemorySegment, arena: Arena): Boolean {
        val value = arena.allocate(ValueLayout.ADDRESS)
        val error = arena.allocate(ValueLayout.ADDRESS)
        val succeeded = (CF_URL_COPY_RESOURCE_PROPERTY.invokeWithArguments(
            fileURL, quarantinePropertiesKey, value, error
        ) as Byte).toInt() != 0
        checkSucceeded(succeeded, error, "read quarantine properties")

        val properties = value.get(ValueLayout.ADDRESS, 0)
        if (properties == MemorySegment.NULL)
            return false
        CF_RELEASE.invokeWithArguments(properties)
        return true
    }

    private fun setQuarantineProperties(fileURL: MemorySegment, arena: Arena) {
        val agentName = cfString("Hydraulic URL", arena)
        val keys = arena.allocate(ValueLayout.ADDRESS, 2)
        val values = arena.allocate(ValueLayout.ADDRESS, 2)
        keys.setAtIndex(ValueLayout.ADDRESS, 0, quarantineAgentNameKey)
        keys.setAtIndex(ValueLayout.ADDRESS, 1, quarantineTypeKey)
        values.setAtIndex(ValueLayout.ADDRESS, 0, agentName)
        values.setAtIndex(ValueLayout.ADDRESS, 1, quarantineTypeWebDownload)

        val properties = CF_DICTIONARY_CREATE.invokeWithArguments(
            MemorySegment.NULL, keys, values, 2L,
            cfTypeDictionaryKeyCallbacks, cfTypeDictionaryValueCallbacks
        ) as MemorySegment
        if (properties == MemorySegment.NULL) {
            CF_RELEASE.invokeWithArguments(agentName)
            error("Core Foundation could not create quarantine properties")
        }

        try {
            val error = arena.allocate(ValueLayout.ADDRESS)
            val succeeded = (CF_URL_SET_RESOURCE_PROPERTY.invokeWithArguments(
                fileURL, quarantinePropertiesKey, properties, error
            ) as Byte).toInt() != 0
            checkSucceeded(succeeded, error, "set quarantine properties")
        } finally {
            CF_RELEASE.invokeWithArguments(properties)
            CF_RELEASE.invokeWithArguments(agentName)
        }
    }

    private fun cfString(value: String, arena: Arena): MemorySegment {
        val result = CF_STRING_CREATE_WITH_C_STRING.invokeWithArguments(
            MemorySegment.NULL, arena.allocateFrom(value), CF_STRING_ENCODING_UTF8
        ) as MemorySegment
        check(result != MemorySegment.NULL) { "Core Foundation could not create a string" }
        return result
    }

    private fun checkSucceeded(succeeded: Boolean, errorOut: MemorySegment, operation: String) {
        if (succeeded)
            return
        val error = errorOut.get(ValueLayout.ADDRESS, 0)
        val code = if (error == MemorySegment.NULL) null else CF_ERROR_GET_CODE.invokeWithArguments(error) as Long
        if (error != MemorySegment.NULL)
            CF_RELEASE.invokeWithArguments(error)
        throw IOException("Core Foundation failed to $operation${code?.let { " (error $it)" } ?: ""}")
    }

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        LINKER.downcallHandle(CORE_FOUNDATION.find(name).orElseThrow(), descriptor)

    private fun globalPointer(lookup: SymbolLookup, name: String): MemorySegment =
        lookup.find(name).orElseThrow().reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)

    private val LINKER = Linker.nativeLinker()
    private val CORE_FOUNDATION = SymbolLookup.libraryLookup(CORE_FOUNDATION_PATH, Arena.global())
    private val LAUNCH_SERVICES = SymbolLookup.libraryLookup(LAUNCH_SERVICES_PATH, Arena.global())
    private val C_POINTER = LINKER.canonicalLayouts().getValue("void*")
    private val C_LONG = LINKER.canonicalLayouts().getValue("long")
    private val C_INT = LINKER.canonicalLayouts().getValue("int")
    private val C_CHAR = LINKER.canonicalLayouts().getValue("char")

    private val CF_URL_CREATE_FROM_FILE_SYSTEM_REPRESENTATION = function(
        "CFURLCreateFromFileSystemRepresentation",
        FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_LONG, C_CHAR)
    )
    private val CF_URL_COPY_RESOURCE_PROPERTY = function(
        "CFURLCopyResourcePropertyForKey",
        FunctionDescriptor.of(C_CHAR, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
    )
    private val CF_URL_SET_RESOURCE_PROPERTY = function(
        "CFURLSetResourcePropertyForKey",
        FunctionDescriptor.of(C_CHAR, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
    )
    private val CF_STRING_CREATE_WITH_C_STRING = function(
        "CFStringCreateWithCString",
        FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_INT)
    )
    private val CF_DICTIONARY_CREATE = function(
        "CFDictionaryCreate",
        FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_LONG, C_POINTER, C_POINTER)
    )
    private val CF_ERROR_GET_CODE = function(
        "CFErrorGetCode",
        FunctionDescriptor.of(C_LONG, C_POINTER)
    )
    private val CF_RELEASE = function("CFRelease", FunctionDescriptor.ofVoid(C_POINTER))

    private val quarantinePropertiesKey = globalPointer(CORE_FOUNDATION, "kCFURLQuarantinePropertiesKey")
    private val quarantineAgentNameKey = globalPointer(LAUNCH_SERVICES, "kLSQuarantineAgentNameKey")
    private val quarantineTypeKey = globalPointer(LAUNCH_SERVICES, "kLSQuarantineTypeKey")
    private val quarantineTypeWebDownload = globalPointer(LAUNCH_SERVICES, "kLSQuarantineTypeWebDownload")
    private val cfTypeDictionaryKeyCallbacks = CORE_FOUNDATION.find("kCFTypeDictionaryKeyCallBacks").orElseThrow()
    private val cfTypeDictionaryValueCallbacks = CORE_FOUNDATION.find("kCFTypeDictionaryValueCallBacks").orElseThrow()
}

private const val CORE_FOUNDATION_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val LAUNCH_SERVICES_PATH =
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/LaunchServices"
private const val CF_STRING_ENCODING_UTF8 = 0x08000100
