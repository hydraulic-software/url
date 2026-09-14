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

Call `urls` with the resources needed by the launch plan. The host resolves each
map concurrently and returns the corresponding local cache paths. Multiple
calls are allowed, though they complete in script order. Finish
the file with the launch-plan expression:

```js
const resolved = urls({
  tool: "https://example.com/tool.tar.gz/bin/tool",
});

export default { executable: resolved.tool, arguments: context.args };
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
