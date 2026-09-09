# hydraulic.url

`url` resolves an HTTP(S) URL to a stable local cache path. URLs that append a
member path to a supported archive can resolve directly into the safely
extracted archive. The `https://` scheme may be omitted, so `url example.com`
is equivalent to `url https://example.com`.

Archives may be nested, for example
`url example.com/outer.zip/dir/inner.zip/file.txt`.

Multiple URLs may be supplied as arguments or as lines on standard input.
Blank lines and lines whose first non-whitespace character is `#` are ignored.
Each resulting path is newline-terminated by default; use `--print0` for NUL
termination or `--print-separator=:` to construct output such as a classpath.

Append `#sha256=<64 hex characters>` to require that the final resolved file
has the given SHA-256 digest. The fragment is not sent to the server and works
for both ordinary downloads and individual files selected from archives.

HTTP requests honor the conventional `http_proxy`, `https_proxy`, and
`no_proxy` environment variables (with uppercase aliases also accepted).
`no_proxy` accepts comma-separated hosts or domain suffixes, optional ports,
and `*` to bypass proxies for every request.

This is a standalone Gradle project that can also be included as a module in
the Hydraulic product repository. Its wrapper, vendored Hydraulic dependencies,
tests, and GitHub Actions workflow are self-contained. The JARs in `libs/` are
temporary until the corresponding Hydraulic libraries are published to Maven
Central.

Build and test with `./gradlew test`. Build the native command with GraalVM 25
using `./gradlew nativeCompile`.
