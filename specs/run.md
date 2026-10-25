# `run` command specification

This document defines run-package evaluation and process launching. Resource
fetching follows the [shared resolution contract](shared-resolution.md).
The [`url` command-line interface](url.md) is specified separately.

Requirements using **must**, **must not**, **should**, and **may** are normative.
Examples and implementation notes are informative.

The `run` command takes as its first argument either:

1. An HTTP or HTTPS URL. If the scheme is missing, `https://` is inferred. `/run.zip/` is appended to a directory-like URL, including one without a trailing slash when its final path component has no filename suffix, and the resulting resource is resolved using the algorithm defined in the [shared resolution specification](shared-resolution.md).
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

const resolved = await urls({
  java: `https://example.com/jdk-${platform}.tar.gz/bin/java`,
  verifier: `https://example.com/verifier-${ver}.jar`,
});

export default {
  executable: resolved.java,
  arguments: ["-jar", resolved.verifier, ...context.args],
};
```

The package must export its launch plan as the default export. The exported value must be an object with a non-empty string `executable` property and an `arguments` array containing only strings. It may also have a `permissions` object, described under [Permissions](#permissions). The host starts the resulting process directly in the current working directory and passes the arguments unchanged.

The package may import other JavaScript modules with relative paths, for example `import { select } from "./platform.js"`. Imports are resolved from the package directory and may only read files within that package. Node.js builtins and non-local module sources are unavailable. Modules may use top-level `await`.

## JavaScript environment

The implementation supplies one immutable `context` object:

* `context.os`: `linux`, `macos`, `android`, `freebsd`, or `windows` on those systems.
* `context.arch`: `x86_64` on x86-64 systems and `arm64` on AArch64 systems.
* `context.ver`: the non-empty version suffix without the `@`, or `null` when none was given.
* `context.args`: arguments passed after the locator.
* `context.packageDir`: the absolute path to the directory containing `run.js`.
* `context.cwd`: the absolute path of the working directory `run` was started in.

The host supplies the asynchronous `url`, `urls`, and `compose` functions. They
may be called any number of times. Each returns a promise and starts its work
immediately, so operations run concurrently with each other and with the rest
of the evaluation. The host may limit how many resolutions run at once.

The `url` function accepts one URL string and fulfills with an absolute local
path. The `urls` function accepts either an array of URL strings, fulfilling
with an array of absolute local paths, or an object whose property values are
URL strings or arrays of URL strings. Object properties containing arrays
fulfill with arrays of paths under the same property name. The host resolves
all entries in each call concurrently, and the promise rejects as soon as one
entry fails. Empty arrays and objects are valid.

```js
const tool = await url("https://example.com/tool");
const files = await urls([
  "https://example.com/tool",
  "https://example.com/config",
]);
const platforms = await urls({
  linux: "https://example.com/tool-linux",
  macos: ["https://example.com/tool-macos", "https://example.com/helper-macos"],
});
```

Because resolution is asynchronous, independent modules resolve concurrently
without coordinating. Modules imported by the same module are evaluated as
siblings, so while one awaits a resolution the others proceed:

```js
// jdk.js
export const javaHome = await url(`https://example.com/jdk-${context.os}-${context.arch}.tar.gz/`);

// run.js: the JDK and application downloads overlap.
import {javaHome} from "./jdk.js";
import {appJar} from "./app.js";
export default {executable: `${javaHome}/bin/java`, arguments: ["-jar", appJar]};
```

Invalid arguments, such as a non-string URL, reject the returned promise
rather than throwing synchronously. A package may handle a rejection, for
example to fall back to a mirror. As in other JavaScript hosts, a promise that
is rejected without a handler fails the run, even if the package never awaits
it. A rejection is unhandled if it has no handler once pending promise jobs
have run, so a package that starts an operation early and awaits it later
should attach its handler when it starts the operation:

```js
const tool = url("https://example.com/tool");
// Attaching the handler now covers a failure that arrives while `tool` is awaited.
const config = url("https://example.com/config").catch(() => url("https://mirror.example.com/config"));
export default {executable: await tool, arguments: [await config]};
```

Evaluation completes when the module's evaluation has completed and every
operation it started has settled; the host does not launch a process while an
operation is outstanding, even one whose result the package never uses. If
evaluation fails, the host abandons outstanding operations and waits for them
to stop before releasing the cache entries they use. An implementation may stop
waiting after a grace period, so that an operation which cannot be interrupted
does not prevent `run` from failing. Evaluation that can never complete is an
error: for example, a module that awaits a promise which no outstanding host
operation can settle, or a `compose` call, awaited or not, with a `from`
promise that never settles.

URL values are passed to the [shared URL resolver](shared-resolution.md) unchanged, including archive members and `#sha256=` fragments. On Windows, an executable URL may omit its final `.exe` suffix; resolution retries with `.exe` when the original resource or archive member is missing.

The JavaScript package cannot access host classes, arbitrary files, sockets, environment variables, native APIs, processes, or threads. The module loader may read imported JavaScript files from within the package. Implementations may use a JavaScript engine embedded in the host or a separate JavaScript subprocess, but must preserve this capability boundary and must not grant the package a direct process-execution function.

Uncaught JavaScript errors, invalid launch plans, and unhandled rejections, including those from invalid URL maps, invalid composition recipes, composition failures, and resolver errors, cause `run` to fail without launching a process. Diagnostics go to stderr. The command may provide a verbose mode that includes exception stack traces; this implementation enables it with `--verbose` or `URL_VERBOSE=1`.

A package may use files in its directory as executable or argument data. A package that needs more complicated behavior should include a separate executable or script and return it in the launch plan.

## Directory composition

The `compose` function assembles files and directory trees from resolved cache
entries into a single cached directory. It returns a promise that fulfills with
that directory's absolute path as a string. It accepts a non-empty array of
ordered operations. Each operation must be either a copy object with properties
`from`, `select`, `to`, and `replace`, or a removal object with only a `remove`
property. Mixing copy and removal properties, unknown properties, and
incorrect property types are errors.

```js
const base = url("https://example.com/app.zip/");
const patch = url("https://example.com/replacement.zip/");

const app = await compose([
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

`to` is a required non-empty string. `from` is required and is either a
non-empty string or a promise fulfilling with one; `compose` waits for every
`from` promise before validating the recipe, and rejects if any of them
rejects. The resulting string must exactly match a path produced by `url`,
`urls`, or an earlier `compose` call during this evaluation.
If the same path was returned for more than one source identity, as defined
under composition cache identity, using it as `from` is an error. Arbitrary
local paths, including package files, are not composition sources.
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
An existing regular file in a destination's ancestor path is an error rather
than being replaced, because a recipe that writes beneath a file is likely to
be mistaken.

With `replace: true`, the destination subtree is removed before copying the
selection. At `to: "."`, this clears the assembled root's contents. Directory
merging retains earlier files absent from the later selection; subtree
replacement removes them. For a file or symbolic-link selection, `replace`
has no additional effect. Neither operation modifies the source.

### Removal operations

`remove` is a required non-empty string naming a path relative to the
assembled root. The operation removes that path, recursively for directories.
A missing path is a no-op. Removing a symbolic link removes only the link,
never its target. `remove: "."` clears the root's contents without removing
the root directory itself.

Removal affects only the assembly, not any source entry. Later operations may
recreate removed content. For example:

```js
const app = await compose([
  {from: base, to: "."},
  {remove: "bin/tool"},
  {from: patch, select: "bin/tool", to: "bin/tool"},
]);
```

### Paths, links, and materialization

`select`, `to`, and `remove` use portable `/`-separated relative paths.
Redundant separators and `.` components are normalized; `"."` denotes the
root. Absolute paths, drive prefixes, backslashes, and `..` components are
invalid.

Symbolic links in intermediate components of a `select` path are followed,
and each resolved component must remain within the source's leased cache
entry. The final selected component is not followed: selecting a symbolic
link copies the link itself. Links inside a selected directory tree are
likewise copied as links.

Copy and removal operations must not traverse symbolic links in destination
ancestor paths. Such an ancestor is an error, even when the link points
within the assembly, because whether the path exists would otherwise depend
on the link's target. Operations may replace or remove a link at the exact
destination. Removal through a regular-file ancestor is a no-op because the
selected path does not exist.

Two destination paths name the same entry only when they are byte-identical
after the normalization above. The result must not depend on the host
filesystem's case sensitivity or Unicode normalization: an operation fails if it would place an entry beside an
existing sibling whose name differs but is equal under Unicode default case
folding and NFC normalization. This includes such collisions within one
selected tree.

Relative source symbolic links are preserved verbatim. After all operations,
every surviving link must resolve to an existing object inside the assembled
root. Absolute, escaping, dangling, and cyclic links are errors. Validation
occurs after overlays and removals, so a later operation may supply a link's
target or remove a link that would otherwise be invalid. If the host cannot
create a symbolic link, for example on Windows without the required
privilege, composition fails.

Regular files are independent copies. Implementations should use filesystem
copy-on-write cloning where available. Hard links sharing writable file
content with sources are not permitted. Copies preserve regular-file
attributes supported by the filesystem, including executable permissions on
POSIX systems. Source directory permissions are not copied; created
directories use the host's normal directory permissions.

On macOS, an assembly receives the same Gatekeeper quarantine treatment as an
extracted archive under the host's current setting: by default every file and
directory in it is quarantined, and with Gatekeeper disabled none is.
Quarantine metadata on source files is not copied. This setting is part of the
materialization policy.

Composition entries are ordinary cache entries, subject to the same eviction,
free-space guard, and damaged-entry handling as resolver entries. Publication
is atomic: a failed composition must not publish a partial cache entry.
Concurrent evaluations building the same composition must both succeed, with
each one using a completed entry.

### Composition cache identity

The cache key is a deterministic, versioned plain-text representation of the
normalized ordered operations and their source identities. Its exact format
is implementation-specific, but it must unambiguously distinguish recipes,
including strings containing separators or newlines. Absolute cache paths
must not be part of the identity.

A hash-locked source is identified by its normalized effective URL, including
the `#sha256=` lock and archive-member selection. Existing URL resolution and
hash-lock rules apply, so a lock on an archive root or directory member, such
as `app.zip/#sha256=…`, authenticates the innermost archive and makes the
whole selected tree a locked source. An unlocked source is identified by
its normalized effective URL and a persistent content revision. The effective
URL includes any Windows `.exe` resolution fallback.

A content revision changes whenever a new response body is accepted,
including during refresh. Fresh cache hits and `304 Not Modified` preserve
the revision, even when response metadata is updated. Evicted or damaged
entries reconstructed from a new response must not reuse a revision that
identified different content. An HTTP response timestamp, entity tag, or
filesystem modification time alone is insufficient unless it provides these
guarantees. A SHA-256 digest of the accepted response body satisfies them and
can be computed while the body is received; an entry rebuilt from an
identical body then keeps its revision, so dependent compositions stay valid.

Extracted selections inherit the revision of the resource supplying their
content, including nested archive selections. A source returned by `compose`
contributes that composition's cache identity instead of a URL. Keys include
copy selections, destinations, replacement flags, removal paths, operation
order, and materialization policy. The materialization policy covers every
host setting that changes the assembled files, such as the Gatekeeper
setting. Equivalent normalized paths and omitted default values have the same
identity.

Changing an unlocked source revision invalidates dependent compositions even
when the selected subtree is unchanged. Computing the composition key does
not require hashing selected files or trees. A completed assembly must remain
usable independently of later source-entry eviction.

## Launch plan paths

A bare `executable` name is resolved using the host's normal command search path. Other relative executable paths are resolved against `context.packageDir`. Absolute paths are used as supplied after normalization.

On Windows, a non-bare `executable` path may omit its final `.exe` suffix, as executable URLs may. If no file exists at the path but one exists with `.exe` appended, the host launches that file. Packages can therefore build executable paths, such as `${home}/bin/java`, the same way on every platform.

The host keeps resolved source and composition cache entries open until the child process exits, and releases them if evaluation or launch fails. Those entries are the ones a sandboxed process may read, as described under [Permissions](#permissions).

## Permissions

A launch plan may declare what the process needs with a `permissions` object.
A plan without one runs unrestricted, with all of the user's privileges. A
plan with one, even an empty one, entitles the process only to the
permissions it lists and to the baseline below.

Because the plan is computed when the package is evaluated, its permissions
can depend on the arguments, the working directory, the platform, and the
version. A package for a compiler might grant writes only to the output its
user names:

```js
const args = context.args;
const o = args.indexOf("-o");
const output = o >= 0 && o + 1 < args.length ? args[o + 1] : "build";

export default {
  executable: await url(`https://example.com/cc-${context.os}-${context.arch}.tar.gz/bin/cc`),
  arguments: args,
  permissions: {
    "std.files.read": [context.cwd],
    "std.files.write": [output],
    "std.environment": ["CC_*"],
  },
};
```

Permission names are namespaced keys. Keys beginning with `std.` are reserved
for this specification. Other keys may be defined by implementations or by
convention, preferably under a reverse-DNS prefix such as `com.example.gpu`.
This specification defines:

* `std.files.read`: an array of paths that may be read, recursively for
  directories.
* `std.files.write`: an array of paths that may be created, modified, renamed,
  and deleted, recursively for directories. Write permission includes read
  permission for the same paths.
* `std.network.client`: a boolean allowing outgoing network connections,
  including name lookups.
* `std.network.server`: a boolean allowing the process to accept incoming
  network connections.
* `std.environment`: an array of environment variable names passed to the
  process in addition to the baseline variables. In a name, `*` matches any
  sequence of characters, including none. Names are compared
  case-insensitively on Windows and case-sensitively elsewhere.
* `std.semantics`: a semantics revision, described below.

`false` and empty arrays grant nothing. A `permissions` value that is not an
object, a value of the wrong type for a key defined by this specification,
or an empty or non-string array element makes the plan invalid, and `run`
fails without launching a process. Later revisions of this specification
do not change the type of an existing key, so this check cannot reject a
package written against a later revision. Keys the host does not recognize
are not granted, and the host does not warn about them.

Relative paths are resolved against `context.cwd`; `~` is not expanded.
Symbolic links in the existing part of a path are resolved when the process
is launched, and the permission applies to the resolved location. A write
permission for a path that does not exist yet allows the process to create
it as a file or directory. A host that cannot grant permission for a path
that does not exist may grant it for the nearest existing ancestor instead,
and a dry run shows the wider grant.

A process with a `permissions` object may always:

* read and execute files in the package directory and in every cache entry
  resolved or composed during evaluation, including whole archive
  extractions that a returned path points inside;
* read and execute the system libraries, frameworks, configuration, and
  programs needed to start ordinary programs, as defined by the host;
* use its standard input, output, error, and controlling terminal;
* read and write a private temporary directory, named by `TMPDIR` on POSIX
  systems and `TEMP` and `TMP` on Windows, which the host may delete when the
  process exits;
* receive `PATH`, `HOME`, `TERM`, `LANG`, and `LC_*` from the environment of
  `run`, together with the variables matched by `std.environment`. Other
  variables are not passed.

Child processes inherit the restrictions of their parent.

Permissions are enforced on a best-effort basis. How they are enforced is up
to the implementation and depends on the platform. A host may be unable to
enforce some or all of them, for example on an older operating system, and
then runs the process with weaker restrictions or none, without warning. A
host may refuse to launch a program it cannot confine to the declared
permissions, for example a program whose own platform declarations would
give it broader access.

### Semantics revisions

Package authors and implementers do not necessarily coordinate, so the rules
above must be able to change without breaking existing packages. A later
version of this specification may, for example, remove an ambient
permission from the baseline that should not have been granted. Each set of
rules for standard keys and the baseline is a semantics revision, named by
the date in `YYYY-MM-DD` form from which it applies. This specification
defines revision `2026-10-10`, which is the earliest.

A package's revision is chosen using the date of its verified
[timestamp](#timestamped-packages), or for a package without a timestamp, such
as a local directory being developed, the current date. A package can opt in
to a revision before its date by naming it in `std.semantics`; when that is
later than the timestamp date, it is used instead. A package cannot choose an
earlier revision than its timestamp date selects, so republishing a package
moves it to the rules in force at the time.

The host applies the latest revision it implements whose date is on or
before the chosen date, or the earliest revision it implements if there is
none. A host therefore treats a package written for a revision it does not
know as best it can, using the rules it has. A `std.semantics` value that is
not a valid date makes the plan invalid. A dry run shows the revision the
host applied.

## Dry runs

`run --dry` or `run -d`, followed by a run target and its arguments,
evaluates the package as a normal run would, including resolving and
caching its URLs, and then prints the launch plan to stdout instead of
starting the process. Like other `run` options, the flag must come before
the target; after the target, it is an argument for the package. A dry run
exits with status 0 when the plan is valid. An invalid plan fails as it
would in a normal run.

The output is meant for people and its format is not specified. It includes
the executable, the arguments, and whether the process would run
unrestricted or sandboxed. For a sandboxed process it lists the semantics
revision, each declared permission with its resolved paths, any grants the
host widened, the baseline grants, and any keys the host does not recognize.
For example:

```
$ run -d example.com/cc -o out/app main.c
Executable:  /Users/alice/Library/Caches/url/…/bin/cc
Arguments:   -o out/app main.c
Sandbox:     2026-10-10 rules
Permissions:
  std.files.read   /Users/alice/src/app
  std.files.write  /Users/alice/src/app/out/app
                   (widened to /Users/alice/src/app/out)
  std.environment  CC_* (matches CC_FLAGS)
  network          none
Baseline:
  read, execute    package directory and 1 cache entry
  read, write      private temporary directory
  environment      PATH HOME TERM LANG
```

## Timestamped packages

A timestamped `run.zip` may contain a top-level `timestamp.tsr` entry holding a
DER encoded RFC 3161 timestamp response. The response covers a canonical
manifest of every other package entry. The manifest uses UTF-8 relative paths
with `/` separators, sorts records by path bytes, and records each regular
file's SHA-256 digest as `<digest>  <path>` followed by LF. Package paths may
not contain newline characters, duplicate entries are invalid, and symbolic
links are not part of this format.

When verifying a run package timestamp, manifest paths must be constructed
from filesystem path components joined with `/`. Components containing
literal backslashes, carriage returns, or newlines must be rejected, so a
Unix filename cannot authenticate as a different nested path. These rules
also apply to the supplied Unix package builder.

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

JavaScript is a general-purpose language. A conforming implementation must configure its engine so the package receives only the context, `url`, `urls`, and `compose` capabilities described above. Composition grants access only to paths returned during the current evaluation and does not permit arbitrary filesystem reads or writes. Resource exhaustion limits and stronger isolation of the JavaScript evaluation depend on the host platform and JavaScript engine; implementations should apply them where available. Permissions confine the launched process, not the evaluation, and are declared by the package itself: they show what a program will be able to do and limit what it can do if it misbehaves later, but they do not make an untrusted package trustworthy. Users should inspect a dry run before running a package they do not trust. GraalVM users should consult its [sandboxing documentation](https://www.graalvm.org/latest/security-guide/sandboxing/) when evaluating packages that are not trusted.
