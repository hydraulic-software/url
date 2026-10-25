# Sandboxing plan

`run` packages can declare the permissions their process needs, computed dynamically in `run.js` from the arguments,
working directory, platform, and version. The specification defines how permissions are expressed and what they mean;
how a host enforces them is an implementation detail. The draft is in `SPEC-RUN.md` under "Permissions", "Semantics
revisions", and "Dry runs", plus `context.cwd` and a paragraph in "Security".

## Decisions so far

- A launch plan without `permissions` runs unrestricted, as today. With one, even an empty one, the process gets only
  what it declares plus a baseline: the package directory and every cache entry from the evaluation, system libraries,
  stdio and the terminal, a private temporary directory, and `PATH`, `HOME`, `TERM`, `LANG`, `LC_*`.
- Permission keys are namespaced. `std.` is reserved for the specification: `std.files.read`, `std.files.write`,
  `std.network.client`, `std.network.server`, `std.environment` (names with `*` globs), and `std.semantics`. Other keys
  are free, preferably reverse-DNS.
- Enforcement is best effort. Unknown keys are not granted and nothing warns about them, or about permissions the host
  cannot enforce. Package authors and implementers evolve loosely without talking to each other.
- Semantics revisions are named by the date they apply from. The package's verified RFC 3161 timestamp date, or the
  current date if it has none, selects the latest revision the host knows on or before that date. `std.semantics` can
  only move a package to a later revision. Later revisions never change the type of an existing key, so validation in an
  older host cannot reject a newer package.
- Write grants for paths that do not exist yet may be widened to the nearest existing ancestor; a dry run shows this.
- The UI is not interactive. `run --dry` / `run -d` evaluates the package, prints the plan and permissions to stdout,
  and does not launch anything.
- macOS: Seatbelt for simple stdin/stdout tools, with a profile that denies Launch Services and most Mach services.
  Anything more complex, especially `.app` bundles, should be delegated to App Sandbox and its signed entitlements.
  That delegation, including checking that entitlements fit the declared permissions, is deferred.
- MXC is a candidate Windows backend, not the cross-platform abstraction. Linux will probably use Landlock directly,
  because Bubblewrap needs unprivileged user namespaces, which Ubuntu restricts.

## Implementation

One commit per step. Each step adds tests (`RunTest.kt` unless noted) and runs `./gradlew test`.

### 1. `context.cwd`

- Add `cwd: Path` to `RunContext`, filled from `Path.of("").toAbsolutePath()` in `Run.call()`, and emit it in
  `contextJavaScript`.
- Add `cwd: string` to `site/r/context.d.ts` and mention it in `.agents/skills/run-tutorial/SKILL.md` and `README.md`.
- Test: a package that exports `context.cwd` as an argument sees an absolute path.

### 2. Parse and validate `permissions`

- Add `permissions: RequestedPermissions?` to `LaunchPlan`, with `null` meaning unrestricted.
- `RequestedPermissions` holds typed `std.` values (path lists, booleans, environment patterns, semantics date) and
  keeps the names of unrecognized keys for the dry run. Vendor and unknown `std.` keys are not errors.
- Validation in `launchPlan()`, reusing `stringArray`: `permissions` must be an object; known keys must have the right
  type; array elements must be non-empty strings; `std.semantics` must be a valid `YYYY-MM-DD` date.
- Tests: absent vs empty object, each type error, an unknown `std.` key, a vendor key, an invalid date.

### 3. Semantics revisions

- `verifyRunPackage` returns the verified `genTime`, or `null` for an untimestamped package.
- A `SemanticsRevision` list in the host, initially only `2026-10-10` (a placeholder; set the real date when it ships),
  and `selectRevision(timestamp, declared, today)` implementing the spec's rule. Inject the clock for tests.
- The revision is an input to building the grant, so later revisions can change the baseline or key meanings in one place.
- Tests: no timestamp, timestamp before and after a revision, opt-in to a later or unknown revision, attempted
  downgrade, a date before the earliest revision. Use a test-only revision list.

### 4. Effective grant

- `SandboxGrant`, computed after evaluation from the plan, the selected revision, `context.cwd`, and the opened
  `ResolvedURL`s (their `entryDirectory`, so a path inside an archive grants the whole extraction):
  - resolve relative paths against `cwd` and resolve symbolic links in the existing prefix;
  - record which write grants refer to paths that do not exist yet;
  - the baseline for the revision.
- Environment filtering is portable and needs no OS support, so apply it now: `runResolvedPath` receives the
  filtered map. `*` globs; names compare case-insensitively on Windows. Unrestricted plans keep the full environment.
- Create the private temporary directory, set `TMPDIR` (`TEMP`/`TMP` on Windows), and delete it after the child exits.
- Tests: path resolution, symbolic links, missing paths, glob matching, Windows case-insensitivity via the injected
  `windows` flag, an unrestricted plan's environment unchanged.

### 5. `--dry` / `-d`

- Picocli option on `Run`. Evaluate as normal, build the `SandboxGrant`, print it to stdout, exit 0, and do not launch.
- Output as in the spec example: executable, arguments, "unrestricted" or the semantics revision, declared permissions
  with resolved paths, widened grants, glob matches, baseline, unrecognized keys.
- The backend reports whether it would enforce each permission. The dry run may show this; a normal launch never warns.
- Tests: the dry run does not launch (use an executable that would fail); output contains the expected lines; `-d`
  after the target is passed to the package; an invalid plan still fails with a non-zero exit.
- Add a dry-run check to `scripts/run-compliance.py`.

### 6. Zsh `?` prefix

- In `ZshSetup.kt`, rewrite a line starting with `?` to `run -d <rest>`, for URLs, bare domains, and local paths. The
  explicit prefix makes bare domains unambiguous. The existing unprefixed `https?://` rewrite is unchanged.
- `?` is a glob character, but the widget rewrites before zsh parses the line. `!` and `^` would conflict with history
  expansion.
- Test the generated block and the installer's idempotency. If `zsh` is available, run the widget with
  `zsh -f -c` (guarded so the test is skipped elsewhere).

### 7. macOS Seatbelt backend for simple tools

- Applies when the plan has `permissions`, the platform is macOS, and the executable is not inside a `.app` bundle.
  Bundles run unsandboxed until the App Sandbox work in step 8. This rule is an interim placeholder: deciding which
  programs count as simple tools is punted to the next design iteration (see "Deferred design").
- Generate an SBPL profile:
  - `(deny default)`, with `process-fork` and `process-exec` allowed;
  - read and execute for system locations (`/System`, `/usr/lib`, `/usr/bin`, `/bin`, `/private/etc`, dyld cache) and
    the baseline paths;
  - read and write for the temporary directory, `/dev/null`, `/dev/tty*`, and inherited stdio;
  - `std.files.*` mapped to `subpath` or `literal`. Seatbelt can grant literal paths that do not exist yet, so no
    widening is needed here.
- Mach services:
  - Deny by default, including Launch Services, so the tool cannot start other apps outside the sandbox.
  - With `std.network.client`, allow mDNSResponder (`com.apple.dnssd.service`) for name lookup and `com.apple.trustd`
    for TLS certificate evaluation.
  - Consider `opendirectoryd` so `getpwuid` works.
- Launch through `/usr/bin/sandbox-exec -f <profile> -D KEY=VALUE ...`, passing paths as parameters rather than
  splicing them into the profile. `sandbox-exec` is marked deprecated but has shipped unchanged for many years. If it
  is ever removed, replace it with a small trampoline helper resolved through the cache that calls
  `sandbox_init_with_parameters` and then `execve`.
- Integration tests, guarded to macOS, using `/bin/cat` and `/usr/bin/curl` from small local packages:
  allowed read succeeds, read outside the grant fails, write to a missing granted path succeeds, network denied
  without `std.network.client`, `open -a` fails, environment filtered. Use `@TempDir` and the local HTTP server.

### 8. Later, not in this round

- **App Sandbox delegation on macOS:**
  - verify the bundle's code signature;
  - require `com.apple.security.app-sandbox`;
  - check that every nested executable, XPC service, and login item is also sandboxed and that the entitlements fit
    the declared permissions;
  - launch directly, not through `open`.
- **Spikes:**
  - whether a Seatbelt-sandboxed parent can exec an App Sandbox binary;
  - whether `com.apple.security.temporary-exception.sbpl` is honoured under Developer ID without a provisioning
    profile;
  - whether sandbox extensions can pass per-invocation paths to App Sandbox processes.
- **Linux:** Landlock for files (and TCP from ABI 4, kernel 6.7), seccomp to block other socket families, falling back to
  unrestricted on older kernels.
- **Windows:** MXC ProcessContainer through `wxc-exec.exe`, resolved by `run` itself as a hash-locked URL. First
  prototype an interactive TTY tool, a GUI app, exit codes, and Ctrl-C forwarding.

## Documentation

- `SPEC-RUN.md`: drafted. Set the real revision date before release.
- `README.md`: `-d`/`--dry`, the `?` prefix, a permissions example, and which platforms enforce what.
- `site/r/context.d.ts`: `cwd`, plus a `LaunchPlan` type with `permissions` so editors complete `std.` keys.
- `.agents/skills/run-tutorial/SKILL.md`: how to write dynamic permissions.

## Resolved

- `HOME` stays in the baseline environment.
- macOS Seatbelt uses `sandbox-exec`; a cached trampoline helper is the fallback if it is ever removed.

## Deferred design

- **Simple tools vs. App Sandbox:** how to tell a simple stdin/stdout tool, which can run under Seatbelt, from a program
  that must be delegated to App Sandbox. This is explicitly punted to the next design iteration. Until then, step 7
  uses "not inside a `.app` bundle".
