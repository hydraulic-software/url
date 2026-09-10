# `url` functional specification

This document defines the stable, script-facing behavior of the Hydraulic URL
command. An independent implementation can conform without using the same HTTP
client, cache library, archive library, progress renderer, or on-disk layout.

Requirements using **must**, **must not**, **should**, and **may** are normative.
Examples and implementation notes are informative.

## Invocation and inputs

The command is named `url` and accepts zero or more URL operands. An
implementation may provide additional end-user options; they are outside this
script-facing contract.

```text
url [OPTIONS] [URL...]
```

When operands are present, they must be processed from left to right. When none
are present, the command must read UTF-8 text from standard input, one URL per
line. It must trim surrounding whitespace and ignore blank lines and lines
whose first non-whitespace character is `#`. It must fail when no URLs remain.

An input without a URI scheme must be interpreted as HTTPS. For example,
`example.com/file` is equivalent to `https://example.com/file`.

A conforming implementation must support `http` and `https` URLs. HTTP/1.1,
HTTP/2, and HTTP/3 are all valid underlying protocol versions and must have
the same observable resolution semantics. Protocol negotiation and fallback
are implementation details. Implementations may additionally support other
URI schemes; scripts requiring portability across conforming implementations
must use HTTP or HTTPS.

## Script-facing options

- `--print0`: terminate every output path with a NUL byte instead of a newline.
- `--print-separator=CHAR`: terminate every output path with the single
  character `CHAR`. Any other length must fail. This and `--print0` are
  mutually exclusive.
- `--cache-key-url=URL`: use `URL` as the cache identity while fetching the
  input URL exactly as supplied. This requires exactly one input. An archive
  member requires a correspondingly archive-shaped cache-key URL.
- `--cache-dir=PATH`: select a cache directory. Its internal layout and the
  default cache location are not part of this specification.
- `--progress=json`: emit machine-readable progress as newline-delimited JSON
  objects on standard error. Each progress object must have `"type":
  "progress"`; implementations may add fields, and scripts must ignore fields
  they do not recognize. Progress frequency is not guaranteed, and successful
  resolution does not require any progress object to be emitted.

Missing or malformed values for these options must fail. Value-taking options
must also accept the separated form, for example `--cache-dir PATH`.

## Resolution

For each input, the command must resolve the resource into a stable local cache
entry and print its absolute path. A successful path must continue to exist
after the command exits, subject to later cache eviction. Repeated resolution
may reuse a fresh response and should use standard HTTP validators when
revalidation is required.

For HTTP and HTTPS, the command must honor conventional `http_proxy`,
`https_proxy`, and `no_proxy` environment variables, with uppercase aliases
accepted. Incidental request headers that do not alter behavior defined here
are implementation-specific.

On systems with POSIX file permissions, a final regular file beginning with a
hashbang (`#!`) or an ELF, Mach-O, or fat Mach-O magic value must gain the
owner, group, and other execute bits. All existing permission bits must be
preserved. Other content and non-regular-file results must not gain execute
permission from this rule.

A hashbang text file may override the origin's HTTP cache policy with a second
line of the form `# Cache-Control: DIRECTIVES` (case-insensitive field name).
The directive must be non-empty printable ASCII and has the semantics of an
HTTP `Cache-Control` field value. It replaces the origin cache-control value
for freshness decisions and must remain effective across `304 Not Modified`
revalidation. The comment has no effect unless it immediately follows the
initial hashbang line.

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

- `0`: success.
- `2`: invalid CLI syntax, such as an unknown option or missing value.
- `1`: an invalid runtime combination or input, or a resolution, HTTP,
  archive, hash, cache, or local I/O failure.

Exact diagnostic wording is not normative.

## Explicitly non-contractual behavior

Scripts must not depend on human-facing options, non-JSON progress modes,
progress appearance or timing, automatic terminal detection, request identity
headers, default or internal cache paths, cache limits or eviction order, cache
entry names, temporary or lock files, download concurrency, setup/install
shell integration, or platform-specific desktop integration. Those may change
without a compatibility revision to this specification.
