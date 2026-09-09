# `url` functional specification

This document defines the stable, script-facing behavior of the Hydraulic URL
command. An independent implementation can conform without using the same HTTP
client, cache library, archive library, progress renderer, or on-disk layout.

Requirements using **must**, **must not**, **should**, and **may** are normative.
Examples and implementation notes are informative.

## Invocation and inputs

The command is named `url` and accepts zero or more URL operands:

```text
url [OPTIONS] [URL...]
```

When operands are present, they must be processed from left to right. When none
are present, the command must read UTF-8 text from standard input, one URL per
line. It must trim surrounding whitespace and ignore blank lines and lines
whose first non-whitespace character is `#`. It must fail when no URLs remain.

An input without a URI scheme must be interpreted as HTTPS. For example,
`example.com/file` is equivalent to `https://example.com/file`. Resolvable
inputs must use HTTP or HTTPS.

## Stable options

- `-h`, `--help`: write usage and exit successfully without resolving inputs.
- `-V`, `--version`: write version information and exit successfully without
  resolving inputs.
- `--print0`: terminate every output path with a NUL byte instead of a newline.
- `--print-separator=CHAR`: terminate every output path with the single
  character `CHAR`. Any other length must fail. This and `--print0` are
  mutually exclusive.
- `--cache-key-url=URL`: use `URL` as the cache identity while fetching the
  input URL exactly as supplied. This requires exactly one input. An archive
  member requires a correspondingly archive-shaped cache-key URL.
- `--cache-dir=PATH`: select a cache directory. Its internal layout and the
  default cache location are not part of this specification.
- `--cache-limit=GB` and `--cache-free-space-limit=GB`: bound cache retention
  by total size and required free space. Eviction order and timing are not part
  of this specification.
- `--progress=MODE`: select `never`, `plain`, `json`, `term`, or `bar` progress.
  Progress is diagnostic output and must not be written to standard output.
  Its wording, frequency, rendering, and terminal detection are non-normative.

Unknown options and missing or malformed values must fail. Options may also use
the separated form, for example `--cache-dir PATH`.

## Resolution

For each input, the command must resolve the HTTP resource into a stable local
cache entry and print its absolute path. A successful path must continue to
exist after the command exits, subject to later cache eviction. Repeated
resolution may reuse a fresh response and should use standard HTTP validators
when revalidation is required.

Direct HTTP requests must send `User-Agent: Hydraulic URL/1.0`. The command
must honor conventional `http_proxy`, `https_proxy`, and `no_proxy` environment
variables, with uppercase aliases accepted.

### Archive members

If requesting an input returns 404 and its path contains a supported archive
suffix, the command must treat the remaining path as an archive member and try
the archive resource. Supported suffixes are `.zip`, `.tar`, `.tar.gz`,
`.tar.bz2`, `.tar.xz`, and `.tar.Z`, case-insensitively. Archives may be nested.
A trailing slash after an archive name selects its extracted root.

Extraction must reject traversal and must not return a member whose resolved
path escapes the extracted root. A single wrapper directory may be removed.
The exact extraction-cache path is not part of this specification.

### SHA-256 locks

An input may append `#sha256=<64 hexadecimal characters>`. The fragment must
not be sent in the HTTP request. The command must hash the final file, including
an archive member, and fail without printing that result on mismatch. Locking
a directory must fail. Other URI fragments have no locking meaning.

## Output, diagnostics, and failure

On success, standard output must contain exactly one absolute path per input in
input order, each followed by the selected separator. Progress and diagnostics
must go to standard error.

A failure must print no path for the failing input, stop further processing,
and return non-zero. Paths already printed for earlier inputs are not rolled
back. Exit statuses are:

- `0`: success, including `--help` and `--version`.
- `2`: invalid CLI syntax, such as an unknown option or missing value.
- `1`: an invalid runtime combination or input, or a resolution, HTTP,
  archive, hash, cache, or local I/O failure.

Exact diagnostic wording is not normative.

## Explicitly non-contractual behavior

Scripts must not depend on progress appearance or timing, automatic terminal
detection, default or internal cache paths, cache entry names, temporary or
lock files, download concurrency, setup/install shell integration, or
platform-specific desktop integration. Those may change without a
compatibility revision to this specification.
