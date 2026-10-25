# AGENTS.md

## Project overview

`url` is a Kotlin command-line tool that resolves HTTP(S) resources into a
local disk cache. The same executable also exposes `run`, which resolves and
executes remote tools. The script-facing compatibility contract is defined in
the [specifications](specs/README.md); treat them as authoritative when changing
behavior.

## Layout

- `src/main/kotlin/hydraulic/url/`: CLI, URL resolution, runner, and shell setup.
- `src/test/kotlin/hydraulic/url/URLResolverTest.kt`: unit and local HTTP-server
  integration tests.
- `specs/`: command contracts and shared URL resolution requirements.
- `site/`: static project website and interactive demo assets.
- `build-standalone.gradle.kts`: self-contained build used by this repository.
- `build.gradle.kts`: build definition used when this module is part of the
  larger Hydraulic source tree.

## Build and test

Use the Gradle wrapper from the repository root for JVM builds and tests:

```sh
./gradlew test
./gradlew run --args='https://example.com'
```

Use the repository-root `./build.sh` launcher for native-image tasks:

```sh
./build.sh nativeCompile
./build.sh nativePair
```

The standalone build uses JDK 25 and does not provision Java toolchains through
Gradle. When `url` is available, `build.sh` resolves Oracle GraalVM 25, sets it
as the only Gradle Java installation, and disables toolchain auto-detection.
Prefer focused tests while iterating:

```sh
./gradlew test --tests hydraulic.url.URLResolverTest
```

Run the packaged-sample integration suite separately when changing run samples
or their packaging. It builds every timestamped `run.zip` and executes each one
through `run --no-gatekeeper`:

```sh
./gradlew integrationTest
```

## Contribution guidance

- Stage newly created files with `git add` by default. Leave files unstaged when the user explicitly asks for that.
- Gradle assembles sample source directories by copying declared shared helpers.
  Use `package<Name>RunSample` or `packageRunSamples` to automate packaging via
  the actual `make-run-zip` launcher; tests that need an archive should also
  create it with `make-run-zip`, not a Gradle ZIP task.
- Preserve the output contract: successful paths go only to stdout; progress
  and diagnostics go only to stderr.
- To inspect a remote resource or archive layout, use this repository's `url`
  command. Resolve an archive URL with a trailing slash to inspect its extracted
  directory, or append `/member/path` to resolve a specific archive member; do
  not guess the archive's internal root directory.
- Add or update tests for every observable behavior change, especially URL
  parsing, caching, archive fallback, hash locks, proxy behavior, and CLI exit
  codes.
- Keep the default test suite hermetic. Use `@TempDir` and the test-local HTTP server helpers;
  do not depend on public network services, except that RFC 3161 timestamp
  authority (TSA) requests are free, so timestamped-package tests should use a
  real TSA instead of mocking the TSA interaction. The jinfer run.zip
  integration test also installs its locked npm dependency and resolves the
  pinned esbuild binary so it can test the actual archive produced by
  `make-run-zip`. The separately invoked `integrationTest` task intentionally
  packages and executes every sample archive, including their real network
  downloads; run it when those sample packages change.
- Maintain cross-platform behavior. Guard POSIX- and macOS-specific assertions
  and retain Windows `run.ps1` handling.
- `url` inputs without a scheme imply HTTPS. Do not change that normalization
  or archive-member parsing without updating `specs/shared-resolution.md` and
  its tests.
- Website-only changes belong under `site/`; avoid altering CLI code or build
  configuration unless the change needs it.

## Before handing off

Run the narrowest relevant Gradle test task. Run the complete test suite for
changes affecting resolver behavior, CLI parsing, caching, or shared helpers.
Update `README.md` and the relevant specification in `specs/` when public
behavior changes.
