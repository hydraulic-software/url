package hydraulic.url

import java.nio.file.Path

/** Extracts the local archive formats understood by composite Hydraulic URL paths without loading the Shell runtime. */
internal fun extractLocalArchive(archive: Path, destination: Path, skipSingleRoot: Boolean) {
    hydraulic.archives.extractLocalArchive(archive, destination, skipSingleRoot)
}
