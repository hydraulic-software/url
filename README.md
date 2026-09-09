# hydraulic.url

`url` resolves an HTTP(S) URL to a stable local cache path. URLs that append a
member path to a supported archive can resolve directly into the safely
extracted archive.

This is a standalone Gradle project that can also be included as a module in
the Hydraulic product repository. Its wrapper, vendored Hydraulic dependencies,
tests, and GitHub Actions workflow are self-contained. The JARs in `libs/` are
temporary until the corresponding Hydraulic libraries are published to Maven
Central.

Build and test with `./gradlew test`. Build the native command with GraalVM 25
using `./gradlew nativeCompile`.
