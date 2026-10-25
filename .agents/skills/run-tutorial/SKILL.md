---
name: run-tutorial
description: Explain Hydraulic's run.zip format and guide authors creating, reviewing or testing portable JavaScript launchers, including the code style for reusable helper modules.
---

# Using `run`

The `run` command downloads a package from a URL and uses its `run.js` file to
produce a launch plan. During development, place the same files in a directory
named `run.zip.d` and pass that directory to `run`.

## `run.js`

A package contains a small JavaScript launch description. The host provides an
immutable `context` object with `os`, `arch`, nullable `ver`, `args`, and
`packageDir` and absolute working directory `cwd` properties.

The host provides three asynchronous functions. Each returns a promise and
starts its work immediately, so operations run concurrently with each other and
with the rest of the script:

* `url(location)` resolves one URL to a local cache path.
* `urls(requests)` takes an array of URLs, or a map whose values are URLs or
  arrays of URLs, and resolves them to paths in the same shape.
* `compose(operations)` assembles a cached directory from paths returned by the
  other functions. See [run specification](https://github.com/hydraulic-software/url/blob/master/specs/run.md) for its recipe format.

Start every download before awaiting any of them, then await them while
building the launch plan:

```js
const tool = url("https://example.com/tool.tar.gz/bin/tool");
const config = url("https://example.com/config.json");

export default { executable: await tool, arguments: ["--config", await config, ...context.args] };
```

A rejection that the package doesn't handle fails the run. To fall back to a
mirror, attach the handler when you call the function, not where you later
await the result:

```js
const tool = url("https://example.com/tool").catch(() => url("https://mirror.example.com/tool"));
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

## Dynamic permissions and dry runs

Add a `permissions` object to the launch plan to request confinement. Omit it
for unrestricted execution; `{}` requests only the baseline. Compute grants
from `context.args`, absolute `context.cwd`, platform and version as needed:

```js
const output = context.args[0] ?? "out";
export default {
    executable: await url("https://example.com/tool"),
    arguments: [output, ...context.args.slice(1)],
    permissions: {
        "std.files.read": [context.cwd],
        "std.files.write": [output],
        "std.environment": ["CC_*"],
        "std.network.client": false,
    },
};
```

Read and write lists contain non-empty paths. Relative paths resolve against
`context.cwd`, and write access includes reading. The baseline already allows
reading and executing the package and every resolved cache entry, stdio and
the terminal, system libraries and programs, a private temporary directory,
and `PATH`, `HOME`, `TERM`, `LANG`, and `LC_*`. Request other environment names
with `std.environment`; `*` globs are supported. Network client and server
permissions are booleans. `std.semantics` is an optional `YYYY-MM-DD` date to
opt in to later rules; earlier dates cannot downgrade timestamp-selected rules.
Unknown standard and vendor keys are ignored without warnings.

Use `run -d ./run.zip.d args` or `run --dry example.com/tool args` to evaluate,
cache, validate and inspect permissions without launching. In an integrated
zsh shell, `?./run.zip.d args` or `?example.com/tool args` does the same.
Permission enforcement is best effort: macOS uses Seatbelt for executables
outside `.app` bundles; other platforms and app bundles currently enforce only
environment filtering. Inspect the dry run's enforcement status when testing.

## Package guidance

Follow [the conventions](references/CONVENTIONS.md) when writing or reviewing a
package. They cover required behavior, how to structure reusable helper modules
so packages stay consistent, and Windows `.exe` handling. The
[CEL verifier package](https://github.com/hydraulic-software/url/tree/master/site/r/cel-verifier/run.zip.d) is a worked
example.

See [run specification](https://github.com/hydraulic-software/url/blob/master/specs/run.md) for the complete contract.

## Timestamped packages

A timestamped `run.zip` can contain a top-level `timestamp.tsr` entry with a
DER encoded RFC 3161 timestamp response. The response covers a canonical
manifest of all other package entries. Implementations recompute that manifest
after extraction before applying timestamp based compatibility rules. Package
builders may omit `context.d.ts` as a convenience; if present, it is included
in the manifest and validated normally.
