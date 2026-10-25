# Shared URL resolution specification

This document defines resource resolution used by both the [`url` command](url.md)
and the [`run` command](run.md). It applies to remote run-package resolution and
to the JavaScript `url()` and `urls()` host functions. Command-line input,
output, and exit-status rules belong to the respective command specifications.

Requirements using **must**, **must not**, **should**, and **may** are normative.
Examples and implementation notes are informative.

References to options below describe resolver settings exposed by the host;
the `url` command's option syntax is defined in its [command-line contract](url.md#script-facing-options).

## URL inputs and protocols

An input without a URI scheme must be interpreted as HTTPS. For example,
`example.com/file` is equivalent to `https://example.com/file`.

A conforming implementation must support `http` and `https` URLs. HTTP/1.1,
HTTP/2, and HTTP/3 are all valid underlying protocol versions and must have
the same observable resolution semantics. Protocol negotiation and fallback
are implementation details. Implementations may additionally support other
URI schemes; scripts requiring portability across conforming implementations
must use HTTP or HTTPS.

## Resolution

For each input, the resolver must resolve the resource into a stable local cache
entry and return its absolute path. A successful path must continue to exist after the command exits, subject to later cache eviction.
Repeated resolution may reuse a fresh response and should use standard HTTP
validators when revalidation is required.

For HTTP and HTTPS, the resolver must honor conventional `http_proxy`,
`https_proxy`, and `no_proxy` environment variables, with uppercase aliases
accepted. Incidental request headers that do not alter behavior defined here
are implementation-specific.

On systems with POSIX file permissions, a final regular file beginning with a
hashbang (`#!`) or an ELF, Mach-O, or fat Mach-O magic value must gain the
owner, group, and other execute bits. All existing permission bits must be
preserved. Other content and non-regular-file results must not gain execute
permission from this rule.

A hashbang text file may override the origin's HTTP cache policy with a second
line of the form `# Cache-Control: DIRECTIVES` or `// Cache-Control:
DIRECTIVES` (case-insensitive field name). The directive must be non-empty
printable ASCII and has the semantics of an HTTP `Cache-Control` field value.
It replaces the origin cache-control value for freshness decisions and must
remain effective across `304 Not Modified` revalidation. The comment has no
effect unless it immediately follows the initial hashbang line.

### Archive members

If requesting an input returns 404 and its path contains a supported archive
suffix, the resolver must treat the remaining path as an archive member and try
the archive resource. Supported suffixes are `.zip`, `.tar`, `.tar.gz`, `.tgz`,
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
resource, the resolver must hash that final file even when its path looks like
an archive member. If resolution extracts an archive, it must hash the complete
innermost archive before extraction. For example, a lock on
`outer.zip/inner.zip/file` authenticates `inner.zip`. A nested archive
requested as a file, such as `outer.zip/inner.zip` without a trailing slash,
returns and authenticates that file; adding a slash requests its extracted root. The same applies when
the result is a directory: a lock on `app.zip/` or `app.zip/dir/` authenticates
`app.zip`. Other URI fragments have no locking meaning.
