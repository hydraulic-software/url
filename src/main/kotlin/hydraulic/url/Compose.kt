package hydraulic.url

import hydraulic.diskcache.DiskCache
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.text.Normalizer
import java.util.Locale

/** One step of a `compose()` recipe. Paths are as supplied by run.js and are validated by [Composer]. */
internal sealed interface ComposeOperation {
    data class Copy(val from: String, val select: String = ".", val to: String, val replace: Boolean = false) : ComposeOperation
    data class Remove(val path: String) : ComposeOperation
}

/**
 * A path that run.js may use as a composition source.
 *
 * [identity] is the source's contribution to composition cache keys, or null if it has none, in which case using it is an
 * error. [root] is the leased cache entry containing [path], which selections must not leave.
 */
internal data class ComposeSource(val identity: String?, val path: Path, val root: Path)

/**
 * Assembles files and directory trees from cache entries into new cached directories, as specified for `compose()` in
 * SPEC-RUN.md. The cache key is derived from the recipe and its source identities, so building never hashes file contents.
 */
internal class Composer(
    cache: DiskCache,
    private val quarantine: Boolean,
    private val minimumFreeSpaceBytes: Long,
    private val windows: Boolean = Platform.isWindows
) {
    private val cache = CompleteEntryDiskCache(cache)

    /** The identity a composition contributes when it is itself used as a source. */
    fun identityOf(key: String): String = "Composition: ${JsonPrimitive(key)}"

    /**
     * Returns the cache key and leased result for [operations]. [source] maps each `from` value to its source, or returns
     * null when the value is not a composition source.
     */
    fun compose(operations: List<ComposeOperation>, source: (String) -> ComposeSource?): Pair<String, ResolvedURL> {
        require(operations.isNotEmpty()) { "compose() requires at least one operation" }
        val steps = operations.mapIndexed { index, operation ->
            val label = "compose() operation $index"
            when (operation) {
                is ComposeOperation.Copy -> {
                    val from = source(operation.from)
                        ?: throw IllegalArgumentException("$label: 'from' is not a path returned by url(), urls() or compose(): ${operation.from}")
                    val identity = from.identity
                        ?: throw IllegalArgumentException("$label: 'from' cannot be used as a composition source: ${operation.from}")
                    Step.Copy(
                        from, identity,
                        portableComponents(operation.select, "$label 'select'"),
                        portableComponents(operation.to, "$label 'to'"),
                        operation.replace
                    )
                }
                is ComposeOperation.Remove -> Step.Remove(portableComponents(operation.path, "$label 'remove'"))
            }
        }
        val key = cacheKey(steps)
        val entry = cache.getAndCustomizeEntry(key, rerun = false) { destination ->
            preparePrivateCacheDirectory(destination)
            Assembly(destination.toRealPath(), DiskSpaceGuard(minimumFreeSpaceBytes, destination), windows).build(steps)
            if (quarantine)
                destination.quarantineTree()
            else
                destination.removeQuarantineTree()
            DiskCache.EntryComputationResult()
        }
        val root = try {
            entry.directory.toRealPath()
        } catch (e: IOException) {
            entry.close()
            throw e
        }
        return key to ResolvedURL(root, entry)
    }

    private fun cacheKey(steps: List<Step>): String = buildString {
        append("Composition\nLayout version: 1\nQuarantined: ").append(quarantine).append('\n')
        for (step in steps) {
            // Every value is a JSON string, so separators and newlines inside values cannot be confused with structure.
            when (step) {
                is Step.Copy -> append("Copy from=").append(JsonPrimitive(step.identity))
                    .append(" select=").append(JsonPrimitive(step.select.joinPortable()))
                    .append(" to=").append(JsonPrimitive(step.to.joinPortable()))
                    .append(" replace=").append(step.replace)
                is Step.Remove -> append("Remove path=").append(JsonPrimitive(step.path.joinPortable()))
            }
            append('\n')
        }
    }

    private sealed interface Step {
        class Copy(val source: ComposeSource, val identity: String, val select: List<String>, val to: List<String>, val replace: Boolean) : Step
        class Remove(val path: List<String>) : Step
    }

    private class Assembly(private val root: Path, private val space: DiskSpaceGuard, private val windows: Boolean) {
        /** Names present in each assembled directory, keyed by [foldName], so collisions are detected on any filesystem. */
        private val names = HashMap<Path, MutableMap<String, String>>()

        fun build(steps: List<Step>) {
            for ((index, step) in steps.withIndex()) {
                val label = "compose() operation $index"
                try {
                    when (step) {
                        is Step.Copy -> copy(step)
                        is Step.Remove -> remove(step.path)
                    }
                } catch (e: CompositionException) {
                    throw IllegalArgumentException("$label: ${e.message}", e)
                }
            }
            validateLinks()
        }

        private fun copy(step: Step.Copy) {
            val selected = select(step.source, step.select)
            val attributes = attributesOf(selected) ?: fail("selection does not exist: ${step.select.joinPortable()}")
            if (attributes.isDirectory) {
                if (step.to.isEmpty()) {
                    if (step.replace)
                        clear(root)
                    mergeChildren(selected, root)
                } else {
                    val parent = prepareParents(step.to)
                    val name = step.to.last()
                    if (step.replace)
                        removeChild(parent, name)
                    place(selected, attributes, parent, name)
                }
            } else {
                if (step.to.isEmpty())
                    fail("'to' must name a file when the selection is not a directory")
                place(selected, attributes, prepareParents(step.to), step.to.last())
            }
        }

        /** Resolves a selection, following links in intermediate components but not in the final one. */
        private fun select(source: ComposeSource, components: List<String>): Path {
            val sourceRoot = source.root.toRealPath()
            var current = source.path.toRealPath()
            if (!current.startsWith(sourceRoot))
                fail("source escapes its cache entry: ${source.path}")
            if (components.isEmpty())
                return current
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                fail("'select' must be \".\" when 'from' is a file")
            for (component in components.dropLast(1)) {
                val next = current.resolve(component)
                if (attributesOf(next) == null)
                    fail("selection does not exist: ${components.joinPortable()}")
                current = next.toRealPath()
                if (!current.startsWith(sourceRoot))
                    fail("selection escapes its cache entry: ${components.joinPortable()}")
                if (!Files.isDirectory(current))
                    fail("selection does not exist: ${components.joinPortable()}")
            }
            return current.resolve(components.last())
        }

        /** Creates missing destination ancestors, refusing to write beneath files or through links. */
        private fun prepareParents(destination: List<String>): Path {
            var directory = root
            for (component in destination.dropLast(1)) {
                checkName(directory, component)
                val next = directory.resolve(component)
                val attributes = attributesOf(next)
                when {
                    attributes == null -> {
                        Files.createDirectory(next)
                        record(directory, component)
                    }
                    attributes.isSymbolicLink -> fail("destination ancestor is a symbolic link: ${relative(next)}")
                    !attributes.isDirectory -> fail("destination ancestor is a file: ${relative(next)}")
                }
                directory = next
            }
            return directory
        }

        private fun place(source: Path, attributes: BasicFileAttributes, parent: Path, name: String) {
            checkName(parent, name)
            val destination = parent.resolve(name)
            val existing = attributesOf(destination)
            when {
                attributes.isDirectory -> {
                    if (existing == null || !existing.isDirectory || existing.isSymbolicLink) {
                        if (existing != null)
                            delete(destination)
                        Files.createDirectory(destination)
                        record(parent, name)
                    }
                    mergeChildren(source, destination)
                }
                attributes.isRegularFile -> {
                    if (existing != null)
                        delete(destination)
                    space.check(attributes.size())
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    record(parent, name)
                }
                attributes.isSymbolicLink -> {
                    val target = Files.readSymbolicLink(source)
                    if (existing != null)
                        delete(destination)
                    try {
                        Files.createSymbolicLink(destination, target)
                    } catch (e: IOException) {
                        throw CompositionException("cannot create symbolic link ${relative(destination)}: ${e.message}", e)
                    } catch (e: UnsupportedOperationException) {
                        throw CompositionException("cannot create symbolic link ${relative(destination)}: ${e.message}", e)
                    }
                    record(parent, name)
                }
                else -> fail("unsupported filesystem object: $source")
            }
        }

        private fun mergeChildren(source: Path, destination: Path) {
            val children = Files.list(source).use { stream -> stream.sorted().toList() }
            for (child in children)
                place(child, attributesOf(child) ?: fail("cannot read $child"), destination, child.fileName.toString())
        }

        private fun remove(path: List<String>) {
            if (path.isEmpty()) {
                clear(root)
                return
            }
            var directory = root
            for (component in path.dropLast(1)) {
                if (namesIn(directory)[foldName(component)] != component)
                    return
                val next = directory.resolve(component)
                val attributes = attributesOf(next) ?: return
                when {
                    attributes.isSymbolicLink -> fail("removal ancestor is a symbolic link: ${relative(next)}")
                    !attributes.isDirectory -> return
                }
                directory = next
            }
            removeChild(directory, path.last())
        }

        private fun removeChild(parent: Path, name: String) {
            if (namesIn(parent)[foldName(name)] != name)
                return
            delete(parent.resolve(name))
        }

        private fun clear(directory: Path) {
            Files.list(directory).use { stream -> stream.toList() }.forEach(::delete)
        }

        /** Deletes a file, link or directory tree without following links. */
        private fun delete(path: Path) {
            names.keys.removeIf { it.startsWith(path) }
            names[path.parent]?.remove(foldName(path.fileName.toString()))
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deleteEntry(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null)
                        throw exc
                    deleteEntry(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        private fun deleteEntry(path: Path) {
            // Windows refuses to delete read-only files, which archives and POSIX permission copies can produce.
            if (windows && !Files.isSymbolicLink(path))
                Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.setReadOnly(false)
            Files.delete(path)
        }

        private fun validateLinks() {
            val links = Files.walk(root).use { paths -> paths.filter(Files::isSymbolicLink).toList() }
            for (link in links) {
                val parent = root.relativize(link.parent).components()
                val target = Files.readSymbolicLink(link)
                try {
                    resolveWithinRoot(parent, target, depth = 0)
                } catch (e: CompositionException) {
                    throw IllegalArgumentException("compose() symbolic link ${relative(link)} -> $target is invalid: ${e.message}", e)
                }
            }
        }

        /**
         * Resolves [target] relative to [base] without ever consulting a path outside the assembly. Links are expanded as
         * they are reached, so the result is the same wherever the assembly is later placed.
         */
        private fun resolveWithinRoot(base: List<String>, target: Path, depth: Int): List<String> {
            if (depth > MAX_LINK_DEPTH)
                fail("it is cyclic or nested too deeply")
            if (target.isAbsolute || target.root != null)
                fail("it is absolute")
            val current = base.toMutableList()
            val components = target.components()
            for ((index, component) in components.withIndex()) {
                when (component) {
                    "", "." -> continue
                    ".." -> {
                        if (current.isEmpty())
                            fail("it escapes the assembled directory")
                        current.removeAt(current.lastIndex)
                    }
                    else -> {
                        val directory = current.fold(root, Path::resolve)
                        if (namesIn(directory)[foldName(component)] != component)
                            fail("its target does not exist")
                        current += component
                        val path = directory.resolve(component)
                        val attributes = attributesOf(path) ?: fail("its target does not exist")
                        if (attributes.isSymbolicLink) {
                            val expanded = resolveWithinRoot(current.dropLast(1), Files.readSymbolicLink(path), depth + 1)
                            current.clear()
                            current += expanded
                        } else if (index < components.lastIndex && !attributes.isDirectory) {
                            fail("its target does not exist")
                        }
                    }
                }
            }
            return current
        }

        private fun checkName(directory: Path, name: String) {
            val existing = namesIn(directory)[foldName(name)]
            if (existing != null && existing != name)
                fail("${relative(directory.resolve(name))} collides with ${relative(directory.resolve(existing))}, which differs only in case or Unicode normalization")
        }

        private fun record(directory: Path, name: String) {
            namesIn(directory)[foldName(name)] = name
        }

        private fun namesIn(directory: Path): MutableMap<String, String> = names.getOrPut(directory) {
            Files.list(directory).use { stream ->
                stream.map { it.fileName.toString() }.toList().associateByTo(HashMap(), ::foldName)
            }
        }

        private fun relative(path: Path): String = root.relativize(path).components().joinPortable()
    }

    private class CompositionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    private companion object {
        const val MAX_LINK_DEPTH = 40

        fun fail(message: String): Nothing = throw CompositionException(message)

        fun attributesOf(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        }

        fun Path.components(): List<String> = map { it.toString() }.filter { it.isNotEmpty() }
    }
}

/**
 * Parses a portable `/`-separated relative path into its components. Redundant separators and `.` components are removed,
 * so `"."` yields the empty list, denoting the root.
 */
internal fun portableComponents(value: String, label: String): List<String> {
    require(value.isNotEmpty()) { "$label must be a non-empty string" }
    require('\\' !in value) { "$label must use '/' separators, not backslashes: $value" }
    require('\u0000' !in value) { "$label must not contain NUL characters" }
    require(!value.startsWith('/')) { "$label must be a relative path: $value" }
    require(!DRIVE_PREFIX.containsMatchIn(value)) { "$label must not have a drive prefix: $value" }
    val components = value.split('/').filter { it.isNotEmpty() && it != "." }
    require(".." !in components) { "$label must not contain '..' components: $value" }
    return components
}

private fun List<String>.joinPortable(): String = if (isEmpty()) "." else joinToString("/")

private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

/**
 * Folds a name for collision detection: NFC normalization followed by case folding. Uppercasing before lowercasing
 * approximates Unicode full case folding, mapping for example `ß` and `SS` together.
 */
internal fun foldName(name: String): String =
    Normalizer.normalize(Normalizer.normalize(name, Normalizer.Form.NFC).uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC)
