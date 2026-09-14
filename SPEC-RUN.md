# Spec for the `run` command

The `run` command takes as its first argument either:

1. An HTTP or HTTPS URL. If the scheme is missing, `https://` is inferred. `/run.zip/` is appended to a directory-like URL and the resulting resource is resolved using the algorithm defined in the [URL spec](SPEC.md).
2. A directory containing the same files that a `run.zip` package would contain.

The URL or directory name can have a version string after an `@` character. The version is exposed to the package as `context.ver`.

Inside the zip or directory there must be a `run.js` file. It is evaluated as an ECMAScript module by a JavaScript engine supplied by the implementation. The package should be a small launch description; downloaded programs belong in the URL cache.

## Example `run.js`

```js
const ver = context.ver ?? "0.14.0";
const platform = `${context.os}-${context.arch}`;

const resolved = urls({
  java: `https://example.com/jdk-${platform}.tar.gz/bin/java`,
  verifier: `https://example.com/verifier-${ver}.jar`,
});

export default {
  executable: resolved.java,
  arguments: ["-jar", resolved.verifier, ...context.args],
};
```

The package must export its launch plan as the default export. The exported value must be an object with a non-empty string `executable` property and an `arguments` array containing only strings. The host starts the resulting process directly and passes the arguments unchanged.

The package may import other JavaScript modules with relative paths, for example `import { select } from "./platform.js"`. Imports are resolved from the package directory and may only read files within that package. Node.js builtins and non-local module sources are unavailable.

## JavaScript environment

The implementation supplies one immutable `context` object:

* `context.os`: `linux`, `macos`, `android`, `freebsd`, or `windows` on those systems.
* `context.arch`: `x86_64` on x86-64 systems and `arm64` on AArch64 systems.
* `context.ver`: the non-empty version suffix without the `@`, or `null` when none was given.
* `context.args`: arguments passed after the locator.
* `context.packageDir`: the absolute path to the directory containing `run.js`.

The `urls` function is the only host capability available to the package. It may be called any number of times. It accepts either an array of URL strings, returning an array of absolute local paths, or an object whose property values are URL strings or arrays of URL strings. Object properties containing arrays return arrays of paths under the same property name. The host resolves all entries in each call concurrently. Calls complete in script order. Empty arrays and objects are valid.

```js
const files = urls([
  "https://example.com/tool",
  "https://example.com/config",
]);
const platforms = urls({
  linux: "https://example.com/tool-linux",
  macos: ["https://example.com/tool-macos", "https://example.com/helper-macos"],
});
```

URL values are passed to the ordinary URL resolver unchanged, including archive members and `#sha256=` fragments. On Windows, an executable URL may omit its final `.exe` suffix; resolution retries with `.exe` when the original resource or archive member is missing.

The JavaScript package cannot access host classes, arbitrary files, sockets, environment variables, native APIs, processes, or threads. The module loader may read imported JavaScript files from within the package. Implementations may use a JavaScript engine embedded in the host or a separate JavaScript subprocess, but must preserve this capability boundary and must not grant the package a direct process-execution function.

JavaScript errors, invalid URL maps, invalid launch plans, and resolver errors cause `run` to fail without launching a process. Diagnostics go to stderr.

A package may use files in its directory as executable or argument data. A package that needs more complicated behavior should include a separate executable or script and return it in the launch plan.

## Launch plan paths

A bare `executable` name is resolved using the host's normal command search path. Other relative executable paths are resolved against `context.packageDir`. Absolute paths are used as supplied after normalization.

The host keeps resolved cache entries open until the child process exits. This allows future implementations to expose exactly those paths to a process sandbox.

## Timestamped packages

A timestamped `run.zip` may contain a top-level `timestamp.tsr` entry holding a
DER encoded RFC 3161 timestamp response. The response covers a canonical
manifest of every other package entry. The manifest uses UTF-8 relative paths
with `/` separators, sorts records by path bytes, and records each regular
file's SHA-256 digest as `<digest>  <path>` followed by LF. Package paths may
not contain newline characters, duplicate entries are invalid, and symbolic
links are not part of this format.

Package builders omit files named `context.d.ts`. A timestamped package that
contains such a file is invalid rather than treating it as an unverified
exception. The package-root `timestamp.tsr` entry is excluded from its own
manifest; another `timestamp.tsr` entry is invalid.

The manifest is an intermediate verification value and need not be stored in
the package. An implementation recomputes it after extraction, verifies the
RFC 3161 CMS signature and timestamping certificate against the host's trusted
certificate store, verifies that the timestamp token's message imprint matches
it, and only then evaluates `run.js`. ZIP ordering, compression, and file
timestamps are not covered.

## Version suffixes

A non-empty `@VERSION` suffix on the locator, before any query or fragment, is removed before local lookup or URL resolution and exposed as `context.ver`. For example, `run example.com/tool@1.2.3` resolves `example.com/tool/run.zip/run.js` and evaluates the package with `context.ver == "1.2.3"`. Without a version suffix, `context.ver == null`.

## Security

JavaScript is a general-purpose language. A conforming implementation must configure its engine so the package receives only the context and `urls` capability described above. Resource exhaustion limits and stronger process isolation depend on the host platform and JavaScript engine; implementations should apply them where available. GraalVM users should consult its [sandboxing documentation](https://www.graalvm.org/latest/security-guide/sandboxing/) when evaluating packages that are not trusted.
