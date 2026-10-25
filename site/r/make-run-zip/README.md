# make-run-zip

`make-run-zip` packages a directory as a Hydraulic `run.zip`:

```shell
run https://example.com/make-run-zip -- ./my-tool run.zip
```

The source directory is archived with its contents at the archive root. Files
named `context.d.ts` are omitted. The output path defaults to `run.zip` in the
current working directory and must be outside the source directory.

When the source has a root `run.js` and an installed `node_modules` directory,
the builder first bundles `run.js` and its dependencies with esbuild, then
removes `node_modules`, `package.json`, and `package-lock.json` from the archive.
This lets the source use npm packages, including CommonJS packages, while the
resulting launcher stays a single ECMAScript module without npm build metadata.
Run `npm install` in the source directory first so esbuild can resolve those
packages. The source directory is left unchanged. The pinned esbuild executable
is resolved through `run` on every invocation and reused from the cache; the
builder only invokes it when `node_modules` is present.
Packages without a root `run.js` or `node_modules` are archived as-is.

The command adds a top-level `timestamp.tsr` entry containing a binary RFC 3161
timestamp response. The response covers a canonical manifest of the other
files; the manifest is generated temporarily and is not included in the archive.
The `run` command verifies the timestamp signature, the timestamping
certificate, and the manifest before evaluating `run.js`.
The builder's `context.d.ts` exclusion is only a convenience. If another
builder includes that file, verification includes it in the timestamped
manifest like any other package file.
The manifest sorts UTF-8 relative paths and records each file as
`<sha256>  <path>`. File path components may not contain literal backslashes
or newlines; the Unix builder rejects them before requesting a timestamp. The
default timestamp authority is DigiCert; a third argument can select another
RFC 3161 HTTP endpoint:

```shell
run https://example.com/make-run-zip -- ./my-tool run.zip http://tsa.example/
```

On Unix, the launcher requires `openssl` from `PATH` and uses `zip` from `PATH`
when available, falling back to the pinned 7-Zip download otherwise. Unix
hosts also need the system `curl` command to submit the timestamp request;
Windows uses its PowerShell HTTP client and a pinned OpenSSL fallback because
Windows does not provide an `openssl` command as a standard system tool.
