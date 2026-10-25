# `url` command specification

This document defines the stable, script-facing behavior of the Hydraulic URL
command. An independent implementation can conform without using the same HTTP
client, cache library, archive library, progress renderer, or on-disk layout.

Requirements using **must**, **must not**, **should**, and **may** are normative.
Examples and implementation notes are informative.

This document covers the `url` command-line interface. Resource resolution is
defined in the [shared resolution contract](shared-resolution.md), which also
applies to the [`run` command](run.md).

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

## Script-facing options

- `--print0`: terminate every output path with a NUL byte instead of a newline.
- `--print-separator=CHAR`: terminate every output path with the single
  character `CHAR`. Any other length must fail. This and `--print0` are
  mutually exclusive. Neither option may be used with named inputs.
- `--cache-key-url=URL`: use `URL` as the cache identity while fetching the
  input URL exactly as supplied. This requires exactly one input. An archive
  member requires a correspondingly archive-shaped cache-key URL.
- `--cache-dir=PATH`: select a cache directory. Its internal layout and the
  default cache location are not part of this specification. Cache layout
  upgrades may invalidate existing entries and require fresh downloads.
- `-r`, `--refresh`: ignore a cached HTTP response and fetch the resource
  again. The refreshed response replaces the cache entry used by later
  resolutions.
- `--no-gatekeeper`: on macOS, do not quarantine results, as described in the
  [shared resolution contract](shared-resolution.md#resolution).
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

Each input must resolve according to the [shared resolution contract](shared-resolution.md),
including HTTP caching, proxies, executable permissions, macOS quarantine,
archive members, free-space checks, and SHA-256 locks. The command must print
its absolute path or named assignment as described below.

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
