The bundled DiskCache includes configurable failed-build cleanup via
`LocalDiskCache.Configuration.discardFailedBuilds`, a predicate receiving the
build exception. The default preserves ordinary failed builds for diagnosis;
interruptions always discard their working directory. Cleanup and policy
exceptions are suppressed on the original build exception.

The source patch is [hydraulic.diskcache-failed-build-cleanup.patch](../patches/hydraulic.diskcache-failed-build-cleanup.patch).
It applies to Conveyor commit `c1e09382a` (`codex/archive-extraction-hardening`),
the source matching the previously bundled DiskCache. The library was rebuilt
with Kotlin 2.4.10 and JDK 25, and its digest is recorded in `SHA256SUMS`.
`url` selects only its disk-space refusal exception for immediate cleanup.
