# AGENTS.md

## Project overview

`url` is a Kotlin command-line tool that resolves HTTP(S) resources into a
local disk cache. The same executable also exposes `run`, which resolves and
executes remote tools. The script-facing compatibility contract is defined in
[`SPEC.md`](SPEC.md); treat it as authoritative when changing behavior.

## Layout

- `src/main/kotlin/hydraulic/url/`: CLI, URL resolution, runner, and shell setup.
- `src/test/kotlin/hydraulic/url/URLResolverTest.kt`: unit and local HTTP-server
  integration tests.
- `site/`: static project website and interactive demo assets.
- `build-standalone.gradle.kts`: self-contained build used by this repository.
- `build.gradle.kts`: build definition used when this module is part of the
  larger Hydraulic source tree.

## Build and test

Use the Gradle wrapper from the repository root:

```sh
./gradlew test
./gradlew run --args='https://example.com'
./gradlew nativeCompile
./gradlew nativePair
```

The standalone build uses JDK 25. Native-image tasks require GraalVM Community
for Java 25. Prefer focused tests while iterating:

```sh
./gradlew test --tests hydraulic.url.URLResolverTest
```

## Contribution guidance

- Preserve the output contract: successful paths go only to stdout; progress
  and diagnostics go only to stderr.
- Add or update tests for every observable behavior change, especially URL
  parsing, caching, archive fallback, hash locks, proxy behavior, and CLI exit
  codes.
- Keep tests hermetic. Use `@TempDir` and the test-local HTTP server helpers;
  do not depend on public network services.
- Maintain cross-platform behavior. Guard POSIX- and macOS-specific assertions
  and retain Windows `run.ps1` handling.
- `url` inputs without a scheme imply HTTPS. Do not change that normalization
  or archive-member parsing without updating `SPEC.md` and its tests.
- Website-only changes belong under `site/`; avoid altering CLI code or build
  configuration unless the change needs it.

## Before handing off

Run the narrowest relevant Gradle test task. Run the complete test suite for
changes affecting resolver behavior, CLI parsing, caching, or shared helpers.
Update `README.md` and `SPEC.md` when public behavior changes.
