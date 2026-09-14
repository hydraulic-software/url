# `url` and `run`

- `url` downloads the given URL to a disk cache and prints its path.
- `run` takes a URL, appends a well-known path and executes what it finds there, with `zsh` integration so you can run URLs directly.

To learn how to publish software for `run` [read this user guide](.agents/skills/run-tutorial/SKILL.md).

---

## Intro

```shell
$ run --install

$ url https://raw.githubusercontent.com/hydraulic-software/url/refs/heads/master/README.md
/Users/mikehearn/Library/Caches/./entries/8d/0f/8d0fd7ca687ebe77/content/README.md

$ https://example.com --version
```

They're like curl, but simpler when you don't want to manage the downloaded files. Useful with substitutions!
`url` cleans up the cache when it grows too large, and refuses new downloads
when less than 100 MB is free. Override the threshold with
`--min-free-space=MB` or `URL_MIN_FREE_SPACE_MB`; use `0` to disable the guard.
The `https://` part is optional:

```
$ file `url hydraulic.dev`
/Users/mikehearn/Library/Caches/dev.hydraulic/url-tool/entries/14/35/14353bf86b68943b/content/download: HTML document text, Unicode text, UTF-8 text, with very long lines (26118)
```

Pass `-r` or `--refresh` to ignore a cached HTTP response and download it again.

## Building

Run `./build.sh nativePair` to build the native executables. If `url` is already
on `PATH`, the script resolves Oracle GraalVM 25 through the URL cache and points
Gradle at that JDK; otherwise it delegates directly to `./gradlew`.
`./gradlew installDist` creates both `bin/url` and `bin/run` launchers in the
installed distribution. The default development version is `development`; set
`-PurlVersion=VERSION` for a release build.

## Archives

When a URL contains a .zip or tarball, paths within the archive can be appended and `url` will print the path to
that file within the extracted archive. `/` at the end counts as the root of the archive:

```shell
$ url https://github.com/sharkdp/bat/archive/refs/tags/v0.26.1.zip
/Users/mikehearn/Library/Caches/./entries/45/99/4599d2be0fea81b5/content/v0.26.1.zip

$ url https://github.com/sharkdp/bat/archive/refs/tags/v0.26.1.zip/
/Users/mikehearn/Library/Caches/entries/33/e8/33e8825e5015aac5/content

$ ls `url https://github.com/sharkdp/bat/archive/refs/tags/v0.26.1.zip/`
assets          Cargo.lock      CHANGELOG.md    diagnostics     examples        LICENSE-MIT     README.md       SECURITY.md     tests
build           Cargo.toml      CONTRIBUTING.md doc             LICENSE-APACHE  NOTICE          rustfmt.toml    src

$ head -n 1 `url https://github.com/sharkdp/bat/archive/refs/tags/v0.26.1.zip/NOTICE`
Copyright (c) 2018-2021 bat-developers (https://github.com/sharkdp/bat).
```

Archives may be nested, for example `url example.com/outer.zip/dir/inner.zip/file.txt`.

Remote tar archives are decompressed directly from the HTTP response into the
extraction cache, so the compressed tarball is not retained. If that exact
tarball is already in the download cache, the local copy is reused. Supported
tar forms are uncompressed `.tar`, gzip (`.tar.gz`), bzip2 (`.tar.bz2`), XZ
(`.tar.xz`), Zstandard (`.tar.zst` and `.tar.zstd`), and UNIX compress
(`.tar.Z`); ZIP is also supported but requires a seekable cached file.
Extraction stays in-process, consistently across Linux, macOS, and Windows,
without depending on locally installed tools.

## Proxies

HTTP requests honor the conventional `http_proxy`, `https_proxy`, and
`no_proxy` environment variables (with uppercase aliases also accepted).
`no_proxy` accepts comma-separated hosts or domain suffixes, optional ports,
and `*` to bypass proxies for every request.

## Progress tracking

If stderr points to an interactive terminal then `url` emits OSC progress bar events. Modern terminal emulators like
iTerm2 can render these nicely in the UI. You can also use the `--progress=bar` flag to get a nice animated Unicode
progress bar. Because stderr is inherited this works even inside substitutions.

Progress events can be emitted as JSON or plain text if you wish also.

## Resolving into shell variables

Prefix every URL with a portable shell variable name to resolve them in
parallel and emit safely quoted assignments:

```shell
$ resolved=$(url "jdk=$jdk_url" "jar=$jar_url") && eval "$resolved"
$ "$jdk/bin/java" -jar "$jar" --help
```

Named inputs cannot be mixed with ordinary URL inputs or custom output
separators. An `=` inside a URL path or query string remains part of the URL.

## Running programs

`run foobar.com --help` resolves `https://foobar.com/run.zip/run.js`,
evaluates its launch plan, and passes `--help` to the resulting executable.
The JavaScript script describes which URLs to resolve and the final executable
to launch, so the package can select the right binary for each platform without
a shell wrapper.

Because of the caching, this means the program at `foobar.com` will keep itself up to date automatically.

During development, pass a local launcher directory to bypass URL resolution
and the cache. The directory must contain `run.js`, and `run` forwards all
remaining arguments:

```shell
$ run ./run.zip.d --help
```

The package accesses `context.os`, `context.arch`, nullable `context.ver`,
`context.args`, and `context.packageDir`. It calls `urls()` with a map of
URLs; the host resolves each map concurrently and returns a map of local paths.
It may call `urls()` multiple times; calls complete in script order.
The default export is the launch plan. On Windows, executable URLs may omit
their final `.exe` suffix.

Packages may import other JavaScript files from their own directory using
relative ECMAScript module imports.

`run.zip` can contain anything but it's conventional and strongly recommended that:

* It keep `run.js` small. The package should describe the launch rather than
  contain the downloaded program.
* It use `url` values with archive members when a program lives inside an
  archive.
* It run downloaded programs from inside the disk cache, not copy them
  elsewhere or install them.

## Security

`url` sets the +x bit on UNIX automatically for files that are detected to be native binaries or have a hashbang line.

URLs can be hash locked by adding `#sha256=....`. A mismatch will cause `url` to exit with an error code and no path is printed.

On macOS `url` marks executables for Gatekeeper checks unless you pass `--no-gatekeeper`. That option also removes quarantine metadata from an already cached executable. Programs are otherwise expected to be signed, and will be checked by Apple for malware. It's not recommended to override Gatekeeper: signing is cheap and helps keep the macOS ecosystem secure. If you distribute binaries, you have a responsibility to do it. If you don't like the code signing regime Apple maintains, use Linux!

## File lists

You can pass lists of URLs via stdin:

```
$ url <<'EOF'
# This is a comment
https://github.com/sharkdp/bat/archive/refs/tags/v0.26.1.zip#sha256=7e4ce5325c1fee3fc80a26324e203e7c5ed89096cf7c4bdc67d61a97f612c912

# Another comment
https://github.com/sharkdp/bat/releases/download/v0.26.1/bat-v0.26.1-x86_64-apple-darwin.tar.gz#sha256=830d63b0bba1fa040542ec569e3cf77f60d3356b9de75116a344b061e0894245
EOF
/Users/mikehearn/Library/Caches/./entries/45/99/4599d2be0fea81b5/content/v0.26.1.zip
/Users/mikehearn/Library/Caches/./entries/73/8f/738fa741db39ddc1/content/bat-v0.26.1-x86_64-apple-darwin.tar.gz
```

Blank lines and lines whose first non-whitespace character is `#` are ignored.
Inputs resolve concurrently with bounded parallelism, while their paths are
printed in input order. Each resulting path is newline-terminated by default;
use `--print0` for NUL termination or `--print-separator=:` to construct output
such as a classpath.
