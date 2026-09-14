---
name: run-tutorial
description: Explain Hydraulic's run.zip format and guide authors creating or testing portable JavaScript launchers.
---

# Using `run`

The `run` command downloads a package from a URL and uses its `run.js` file to
produce a launch plan. During development, place the same files in a directory
named `run.zip.d` and pass that directory to `run`.

## `run.js`

A package contains a small JavaScript launch description. The host provides an
immutable `context` object with `os`, `arch`, nullable `ver`, `args`, and
`packageDir` properties.

Call `urls` with the resources needed by the launch plan. It accepts either an
array of URLs or a map whose values are URLs or arrays of URLs. The host
resolves all entries concurrently and returns the corresponding local cache
paths in the same shape. Multiple calls are allowed, though they complete in
script order. Finish
the file with the launch-plan expression:

```js
const resolved = urls({
  tool: "https://example.com/tool.tar.gz/bin/tool",
});

export default { executable: resolved.tool, arguments: context.args };
```

For unnamed resources, use an array. Named groups can contain arrays:

```js
const files = urls(["https://example.com/tool", "https://example.com/config"]);
const platformFiles = urls({
  macos: ["https://example.com/tool-macos", "https://example.com/helper-macos"],
});
```

The default export must contain a non-empty string `executable` and an array
of string `arguments`. The host launches the process directly and forwards the
arguments unchanged. It retains resolved cache entries until the child exits.

Import other JavaScript files with relative ECMAScript module imports. Imports
can only read files from the package directory; Node.js builtins are not
available.

`run.js` has no direct access to host classes, files, sockets, environment
variables, native APIs, processes, or threads. Use a separate downloaded or
packaged executable for behavior that belongs in the launched program.

## Package guidance

Respect `context.ver` when selecting a release. Treat unknown operating systems
and architectures as unsupported. Keep all URLs in one `urls` call so downloads
can run concurrently, use archive-member URLs where appropriate, and prefer
hash locks for releases whose content is known.

On macOS, leave Gatekeeper enabled and distribute signed executables. Do not
perform user interaction, installation, or OS integration from a package.

See [SPEC-RUN.md](../../../SPEC-RUN.md) for the complete contract.

## Timestamped packages

A timestamped `run.zip` can contain a top-level `timestamp.tsr` entry with a
DER encoded RFC 3161 timestamp response. The response covers a canonical
manifest of all other package entries. Implementations recompute that manifest
after extraction before applying timestamp based compatibility rules. Package
builders omit `context.d.ts`; timestamped packages containing that file are
invalid rather than leaving it outside the manifest.
