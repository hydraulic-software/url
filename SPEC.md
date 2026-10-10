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

When operands are present, their output order must be left to right. Resolution
may occur concurrently. When no operands are present, the command must read
UTF-8 text from standard input, one URL per line. It must trim surrounding
whitespace and ignore blank lines and lines whose first non-whitespace
character is `#`. It must fail when no URLs remain.

An input without a URI scheme must be interpreted as HTTPS. For example,
`example.com/file` is equivalent to `https://example.com/file`.

An input whose first `=` is preceded by a portable shell variable name
matching `[A-Za-z_][A-Za-z0-9_]*` is a named input. The text after `=` is its
URL. Named and unnamed inputs must not be mixed in one invocation. After every
named input resolves successfully, stdout must contain corresponding POSIX
shell assignment statements in input order, with paths quoted so the complete
output can be safely passed to `eval`. For example:

```sh
resolved=$(url jdk=https://example.com/jdk.tar.gz/ jar=https://example.com/app.jar) &&
eval "$resolved"
```

An `=` elsewhere in an input, such as in a URL path or query, has no special
meaning because the preceding text is not a shell variable name.

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
  mutually exclusive. Neither option may be used with named inputs.
- `--cache-key-url=URL`: use `URL` as the cache identity while fetching the
  input URL exactly as supplied. This requires exactly one input. An archive
  member requires a correspondingly archive-shaped cache-key URL.
- `--cache-dir=PATH`: select a cache directory. Its internal layout and the
  default cache location are not part of this specification.
- `-r`, `--refresh`: ignore a cached HTTP response and fetch the resource
  again. The refreshed response replaces the cache entry used by later
  resolutions.
- `--no-gatekeeper`: on macOS, do not quarantine results, as described below.
  It has no effect on other systems.
- `-V`, `--version`: print the build's version and exit successfully.
- `--progress=json`: emit machine-readable progress as newline-delimited JSON
  objects on standard error. Each progress object must have `"type":
  "progress"`; implementations may add fields, and scripts must ignore fields
  they do not recognize. Progress frequency is not guaranteed, and successful
  resolution does not require any progress object to be emitted.

Missing or malformed values for these options must fail. Value-taking options
must also accept the separated form, for example `--cache-dir PATH`.

## Resolution

For each input, the command must resolve the resource into a stable local cache
entry and print its absolute path or named assignment. A successful path must
continue to exist after the command exits, subject to later cache eviction.
Repeated resolution may reuse a fresh response and should use standard HTTP
validators when revalidation is required.

For HTTP and HTTPS, the command must honor conventional `http_proxy`,
`https_proxy`, and `no_proxy` environment variables, with uppercase aliases
accepted. Incidental request headers that do not alter behavior defined here
are implementation-specific.

On systems with POSIX file permissions, a final regular file beginning with a
hashbang (`#!`) or an ELF, Mach-O, or fat Mach-O magic value must gain the
owner, group, and other execute bits. All existing permission bits must be
preserved. Other content and non-regular-file results must not gain execute
permission from this rule.

On macOS, the command must quarantine results as a web browser does, so that
Gatekeeper assesses them before they run. Every downloaded file result and
every file and directory created by archive extraction carries a
`com.apple.quarantine` attribute. Extracted entries share one attribute value,
as Archive Utility produces. Existing quarantine metadata on a cached file is
preserved. With `--no-gatekeeper`, results must carry no quarantine metadata;
the command removes it from cached files, and an archive extracted under one
setting must not be reused under the other. Symbolic links are not marked.

A hashbang text file may override the origin's HTTP cache policy with a second
line of the form `# Cache-Control: DIRECTIVES` or `// Cache-Control:
DIRECTIVES` (case-insensitive field name). The directive must be non-empty
printable ASCII and has the semantics of an HTTP `Cache-Control` field value.
It replaces the origin cache-control value for freshness decisions and must
remain effective across `304 Not Modified` revalidation. The comment has no
effect unless it immediately follows the initial hashbang line.

### Archive members

If requesting an input returns 404 and its path contains a supported archive
suffix, the command must treat the remaining path as an archive member and try
the archive resource. Supported suffixes are `.zip`, `.tar`, `.tar.gz`,
`.tar.bz2`, `.tar.xz`, `.tar.zst`, `.tar.zstd`, and `.tar.Z`,
case-insensitively. Archives may be nested.
A trailing slash after an archive name selects its extracted root.

Extraction must reject traversal, including writes redirected outside the
extracted root by archive-created or pre-existing symbolic links. It must not
return a member whose resolved path escapes the extracted root.

On POSIX systems, extracted regular files must not retain group or other
write permissions from the archive; their read and execute bits may be
preserved.

The `--min-free-space=MB` guard (default 100 MB, overridable by
`URL_MIN_FREE_SPACE_MB`) must check the destination's available space while
consuming responses, writing extracted files, and copying cache content;
zero disables this guard. A failed operation must not publish its partial
cache entry. Disk-space refusals must clean up partial files where possible;
other failed builds may retain working files for investigation. Response size,
expanded archive size, and archive entry count have no fixed limits.

The exact extraction-cache path is not part of this specification.

### SHA-256 locks

An input may append `#sha256=<64 hexadecimal characters>`. The fragment must
not be sent in the HTTP request. If resolution returns an ordinary HTTP
resource, the command must hash that final file even when its path looks like
an archive member. If resolution extracts an archive, it must hash the complete
innermost archive before extraction. For example, a lock on
`outer.zip/inner.zip/file` authenticates `inner.zip`. A nested archive
requested as a file, such as `outer.zip/inner.zip` without a trailing slash,
returns and authenticates that file; adding a slash requests its extracted root. The same applies when
the result is a directory: a lock on `app.zip/` or `app.zip/dir/` authenticates
`app.zip`. Other URI fragments have no locking meaning.

## Output, diagnostics, and failure

On success with unnamed inputs, standard output must contain exactly one
absolute path per input in input order, each followed by the selected
separator. With named inputs, it must contain the corresponding shell
assignments described above. Progress and diagnostics must go to standard
error.

A failure must print no path for the failing input, stop emitting results,
cancel outstanding resolution work where practical, and return non-zero. Paths
already printed for earlier inputs are not rolled back. Exit statuses are:

- `0`: success.
- `2`: invalid CLI syntax, such as an unknown option or missing value.
- `1`: an invalid runtime combination or input, or a resolution, HTTP,
  archive, hash, cache, or local I/O failure.

Exact diagnostic wording is not normative.

## Timestamped run packages

When verifying a run package timestamp, manifest paths must be constructed
from filesystem path components joined with `/`. Components containing
literal backslashes, carriage returns, or newlines must be rejected, so a
Unix filename cannot authenticate as a different nested path. These rules
also apply to the supplied Unix package builder.
