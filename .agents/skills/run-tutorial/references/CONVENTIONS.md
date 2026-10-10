# Conventions for writing `run.js` packages

## Rules

The following are mandatory requirements of `run.js` packages:

1. Respect `context.ver` if it is set. It contains the user's requested version of the app.
2. Don't do any user interaction.

## Suggestions

The following are guidelines designed to make your users happy. Ignore them at your peril!

1. Don't use `curl` to download files, use `url` to benefit from its features like automatic cleanup of the cache.
2. Don't install files or do OS integration.
3. On macOS, don't override Gatekeeper with `--no-gatekeeper` or ship unsigned binaries. Although you _can_ do that, users who have chosen to use macOS expect apps to follow its security conventions. If you _really_ are so impoverished you can't afford an Apple Developer account, ask a user who has one to do it for you.
4. Start every download before awaiting any of them, so they run in parallel.
5. Throw an error for operating systems and architectures you don't support, rather than guessing.
6. Hash-lock releases whose content you know, with a `#sha256=` fragment.

## Code style

Packages are often built from the same pieces, like a JDK or a common tool. These
conventions keep those pieces consistent, so a helper written for one package can
be copied into another.

### Reusable helpers are functions that return promises

Put each reusable dependency in its own module, named after it (`graalvm.js`,
`sevenzip.js`). The module exports a plain function that starts the download and
returns the promise from `url()`, `urls()` or `compose()`:

```js
// graalvm.js
import {isMac, isWindows, mapArch} from "./utils.js";

const javaHashes = {
  "25.0.4+7.1": {"linux-x64": "76007c30...", /* one entry per supported platform */},
};

/** Starts resolving the `java` launcher of an Oracle GraalVM release, returning a promise of its path. */
export function graalvm(version = "25.0.4+7.1") {
  const platform = `${context.os}-${mapArch()}`;
  const hash = javaHashes[version]?.[platform];
  if (javaHashes[version] !== undefined && hash === undefined)
    throw new Error(`Unsupported GraalVM platform: ${platform}`);
  const lock = hash === undefined ? "" : `#sha256=${hash}`;
  return url(`https://download.oracle.com/.../bin/java${lock}`);
}
```

* **Don't use top-level `await`, or call `url()` at module scope, in helper modules.**
  Importing a helper should never start a download. Functions can take parameters,
  where modules can't, and concurrency shouldn't depend on how modules happen to
  import each other.
* **Use plain functions, not `async` ones.** Throw synchronously for unsupported
  platforms or bad arguments. The error then points at the call that caused it, and
  fails before any download starts.
* **Keep pins with the helper.** The helper owns its version → SHA-256 table and its
  default version. It hash-locks versions it knows and leaves others unlocked, so a
  caller can still ask for a newer release.
* **Return paths, not launch plans.** A helper fulfills with a path, or an object of
  paths when one artifact has several useful parts. Only `run.js` builds
  `executable` and `arguments`.
* **Share platform logic.** Put OS checks and architecture name mapping in `utils.js`
  (`isMac`, `isWindows`, `mapArch()`), not in each helper.

URLs specific to one package, like the application's own jar, can stay in `run.js`.

### Only `run.js` awaits

Call every helper first, binding the promises to constants. Then await them where
the launch plan uses them:

```js
import { graalvm } from "./graalvm.js";

const java = graalvm();
const app = url(`https://example.com/app-${context.ver ?? "1.0"}.jar`);

export default { executable: await java, arguments: ["-jar", await app, ...context.args] };
```

Awaiting each call as you make it (`const java = await graalvm();`) works, but it
downloads one thing at a time.

### Attach fallbacks where you call

A rejection with no handler fails the run as soon as pending promise jobs have run,
even if a later line would have handled it. Attach mirrors and other fallbacks to
the call itself:

```js
const tool = url(primary).catch(() => url(mirror));
```

### Don't write `.exe` in shared paths

On Windows, `run` adds a missing `.exe` suffix to executable URLs and to launch-plan
executable paths. Write paths the same way on every platform, for example
`` `${home}/bin/java` `` or `.../bin/java` in a URL template shared by all platforms.
A URL used only on Windows may name the real `.exe` file, which saves a failed
lookup.

## Checking a package

Evaluate the package from its directory with `run ./run.zip.d [arguments]`. Try
`context.ver` values with and without hash locks, and if possible, run it on each
operating system you support.
