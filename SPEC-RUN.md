# Spec for the `run` command

The `run` command takes as its first argument either:

1. An HTTP or HTTPS URL. If the scheme is missing, `https://` is inferred. `/run.zip/` is appended to a directory-like URL, including one without a trailing slash when its final path component has no filename suffix, and the resulting resource is resolved using the algorithm defined in the [URL spec](SPEC.md).
2. A directory containing the same files that a `run.zip` package would contain.

An explicitly relative path beginning with `./` or `../`, or an absolute path,
is always treated as a local directory. A missing explicit local directory is
an error and is not converted into a URL.

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

The host supplies the `urls` and `compose` functions. They may be called any number of times, and calls complete in script order. The `urls` function accepts either an array of URL strings, returning an array of absolute local paths, or an object whose property values are URL strings or arrays of URL strings. Object properties containing arrays return arrays of paths under the same property name. The host resolves all entries in each call concurrently. Empty arrays and objects are valid.

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

JavaScript errors, invalid URL maps, invalid composition recipes, composition failures, invalid launch plans, and resolver errors cause `run` to fail without launching a process. Diagnostics go to stderr. The command may provide a verbose mode that includes exception stack traces; this implementation enables it with `--verbose` or `URL_VERBOSE=1`.

A package may use files in its directory as executable or argument data. A package that needs more complicated behavior should include a separate executable or script and return it in the launch plan.

## Directory composition

The `compose` function assembles files and directory trees from resolved cache
entries into a single cached directory. It synchronously returns that
directory's absolute path as a string. It accepts a non-empty array of ordered
operations. Each operation must be either a copy object with properties
`from`, `select`, `to`, and `replace`, or a removal object with only a `remove`
property. Mixing copy and removal properties, unknown properties, and
incorrect property types are errors.

```js
const {base, patch} = urls({
  base: "https://example.com/app.zip/",
  patch: "https://example.com/replacement.zip/",
});

const app = compose([
  {from: base, to: "."},
  {remove: "plugins/legacy"},
  {remove: "bin/unwanted-tool"},
  {from: patch, select: "bin/tool", to: "bin/tool"},
  {from: patch, select: "plugins", to: "plugins", replace: true},
]);

export default {
  executable: `${app}/bin/tool`,
  arguments: [...context.args],
};
```

### Copy operations

`from` and `to` are required non-empty strings. `from` must exactly match a
path returned by `urls` or an earlier `compose` call during this evaluation.
Arbitrary local paths, including package files, are not composition sources.
`select` is an optional non-empty string defaulting to `"."`; it selects an
exact file or directory tree relative to `from`. There are no glob patterns.
When `from` is a file, only `select: "."` is allowed. `replace` is an optional
boolean defaulting to `false`.

For a directory selection, its contents are placed at `to`, recursively,
including empty directories. `to: "."` places those contents at the assembled
root. For a file or symbolic-link selection, `to` is the exact destination
filename and must not be `"."`. Missing selections and filesystem objects
other than regular files, directories, and symbolic links are errors.

Operations are applied in array order. Later regular files and symbolic links
replace earlier objects at the same destination, recursively removing an
earlier directory if necessary. Directories merge with existing directories;
an earlier file or symbolic link at a directory's destination is removed
before the directory is created. Missing parent directories are created.
An existing regular file in a destination's ancestor path is an error.

With `replace: true`, the destination subtree is removed before copying the
selection. At `to: "."`, this clears the assembled root's contents. Directory
merging retains earlier files absent from the later selection; subtree
replacement removes them. Neither operation modifies the source.

### Removal operations

`remove` is a required non-empty string naming a path relative to the
assembled root. The operation removes that path, recursively for directories.
A missing path is a no-op. Removing a symbolic link removes only the link,
never its target. `remove: "."` clears the root's contents without removing
the root directory itself.

Removal affects only the assembly, not any source entry. Later operations may
recreate removed content. For example:

```js
const app = compose([
  {from: base, to: "."},
  {remove: "bin/tool"},
  {from: patch, select: "bin/tool", to: "bin/tool"},
]);
```

### Paths, links, and materialization

`select`, `to`, and `remove` use portable `/`-separated relative paths.
Redundant separators and `.` components are normalized; `"."` denotes the
root. Absolute paths, drive prefixes, backslashes, and `..` components are
invalid. Source selections must remain within their leased cache entry both
lexically and when resolving symbolic links.

Copy and removal operations must not traverse symbolic links in destination
ancestor paths. Such an ancestor is an error, even when the link points
within the assembly. Operations may replace or remove a link at the exact
destination. Removal through a non-directory ancestor is a no-op because the
selected path does not exist.

Relative source symbolic links are preserved verbatim. After all operations,
every surviving link must resolve to an existing object inside the assembled
root. Absolute, escaping, dangling, and cyclic links are errors. Validation
occurs after overlays and removals, so a later operation may supply a link's
target or remove a link that would otherwise be invalid.

Regular files are independent copies. Filesystem copy-on-write cloning is
permitted, but hard links sharing writable file content with sources are not.
Copies preserve regular-file attributes supported by the filesystem,
including executable permissions on POSIX systems, and retain the host's
Gatekeeper policy. Created directories use the host's normal directory
permissions. Publication is atomic: a failed composition must not publish a
partial cache entry.

### Composition cache identity

The cache key is a deterministic, versioned plain-text representation of the
normalized ordered operations and their source identities. Its exact format
is implementation-specific, but it must unambiguously distinguish recipes,
including strings containing separators or newlines. Absolute cache paths
must not be part of the identity.

A hash-locked source is identified by its normalized effective URL, including
the `#sha256=` lock and archive-member selection. Existing URL resolution and
hash-lock rules still apply; composition does not make otherwise invalid
hash-locked directory requests valid. An unlocked source is identified by
its normalized effective URL and a persistent content revision. The effective
URL includes any Windows `.exe` resolution fallback.

A content revision changes whenever a new response body is accepted,
including during refresh. Fresh cache hits and `304 Not Modified` preserve
the revision, even when response metadata is updated. Evicted or damaged
entries reconstructed from a new response must not accidentally reuse an
obsolete revision. An HTTP response timestamp or filesystem modification time
alone is insufficient unless it provides these guarantees.

Extracted selections inherit the revision of the resource supplying their
content, including nested archive selections. A source returned by `compose`
contributes that composition's cache identity instead of a URL. Keys include
copy selections, destinations, replacement flags, removal paths, operation
order, and materialization policy. Equivalent normalized paths and omitted
default values have the same identity.

Changing an unlocked source revision invalidates dependent compositions even
when the selected subtree is unchanged. Computing the composition key does
not require hashing selected files or trees. A completed assembly must remain
usable independently of later source-entry eviction.

## Launch plan paths

A bare `executable` name is resolved using the host's normal command search path. Other relative executable paths are resolved against `context.packageDir`. Absolute paths are used as supplied after normalization.

The host keeps resolved source and composition cache entries open until the child process exits, and releases them if evaluation or launch fails. This allows future implementations to expose exactly those paths to a process sandbox.

## Timestamped packages

A timestamped `run.zip` may contain a top-level `timestamp.tsr` entry holding a
DER encoded RFC 3161 timestamp response. The response covers a canonical
manifest of every other package entry. The manifest uses UTF-8 relative paths
with `/` separators, sorts records by path bytes, and records each regular
file's SHA-256 digest as `<digest>  <path>` followed by LF. Package paths may
not contain newline characters, duplicate entries are invalid, and symbolic
links are not part of this format.

Package builders may omit files named `context.d.ts` as a convenience. If such
a file is present in a package, it is included in the manifest and validated
like any other file. Only the package-root `timestamp.tsr` entry is excluded
from its own manifest; another `timestamp.tsr` entry is an ordinary manifest
entry.

The manifest is an intermediate verification value and need not be stored in
the package. An implementation recomputes it after extraction, verifies the
RFC 3161 CMS signature and timestamping certificate against the host's trusted
certificate store, verifies that the timestamp token's message imprint matches
it, and only then evaluates `run.js`. ZIP ordering, compression, and file
timestamps are not covered.

## Version suffixes

A non-empty `@VERSION` suffix on the locator, before any query or fragment, is removed before local lookup or URL resolution and exposed as `context.ver`. For example, `run example.com/tool@1.2.3` resolves `example.com/tool/run.zip/run.js` and evaluates the package with `context.ver == "1.2.3"`. Without a version suffix, `context.ver == null`.

## Security

JavaScript is a general-purpose language. A conforming implementation must configure its engine so the package receives only the context, `urls`, and `compose` capabilities described above. Composition grants access only to paths returned during the current evaluation and does not permit arbitrary filesystem reads or writes. Resource exhaustion limits and stronger process isolation depend on the host platform and JavaScript engine; implementations should apply them where available. GraalVM users should consult its [sandboxing documentation](https://www.graalvm.org/latest/security-guide/sandboxing/) when evaluating packages that are not trusted.
