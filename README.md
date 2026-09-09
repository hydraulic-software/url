# hydraulic.url

`url` resolves an HTTP(S) URL to a stable local cache path. URLs that append a
member path to a supported archive can resolve directly into the safely
extracted archive. The `https://` scheme may be omitted, so `url example.com`
is equivalent to `url https://example.com`. The stable, script-facing CLI
contract is defined in [SPEC.md](SPEC.md).

Archives may be nested, for example
`url example.com/outer.zip/dir/inner.zip/file.txt`.

Multiple URLs may be supplied as arguments or as lines on standard input.
Blank lines and lines whose first non-whitespace character is `#` are ignored.
Each resulting path is newline-terminated by default; use `--print0` for NUL
termination or `--print-separator=:` to construct output such as a classpath.

Append `#sha256=<64 hex characters>` to require that the final resolved file
has the given SHA-256 digest. The fragment is not sent to the server and works
for both ordinary downloads and individual files selected from archives.

On Unix-like systems, resolved hashbang scripts and ELF, Mach-O, or fat Mach-O
binaries automatically gain execute permission. On macOS, Mach-O results also
receive Gatekeeper quarantine metadata; use `--no-gatekeeper` to opt out.

HTTP requests honor the conventional `http_proxy`, `https_proxy`, and
`no_proxy` environment variables (with uppercase aliases also accepted).
`no_proxy` accepts comma-separated hosts or domain suffixes, optional ports,
and `*` to bypass proxies for every request.

Downloads show animated progress on stderr when stderr is an interactive
terminal, so stdout remains safe for command substitution such as
`path=$(url example.com/archive.zip/file)`. Use `--progress=never` to disable
progress, `--progress=plain` for line-oriented human output, or
`--progress=json` for Progress4J JSON Lines. Automatic mode is quiet when
stderr is redirected or `TERM=dumb`, and honors `NO_COLOR`.

Use `--progress=osc` to emit only terminal-native OSC 9;4 progress codes on
stderr, even when it is redirected, for example
`url --progress=osc example.com/file 2>progress.osc`. Supporting terminal
emulators can use these codes to show progress in a tab, taskbar, or other
native UI without a hand-drawn terminal animation.

This is a standalone Gradle project that can also be included as a module in
the Hydraulic product repository. Its wrapper, vendored Hydraulic dependencies,
tests, and GitHub Actions workflow are self-contained. The JARs in `libs/` are
temporary until the corresponding Hydraulic libraries are published to Maven
Central.

Build and test with `./gradlew test`. Build the native command with GraalVM 25
using `./gradlew nativeCompile`.
