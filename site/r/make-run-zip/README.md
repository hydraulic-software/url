# make-run-zip

`make-run-zip` packages a directory as a Hydraulic `run.zip`:

```shell
run https://example.com/make-run-zip -- ./my-tool run.zip
```

The source directory is archived with its contents at the archive root. Files
named `context.d.ts` are omitted. The output path defaults to `run.zip` in the
current working directory and must be outside the source directory.

The command adds a top-level `timestamp.tsr` entry containing a binary RFC 3161
timestamp response. The response covers a canonical manifest of the other
files; the manifest is generated temporarily and is not included in the archive.
The `run` command verifies the timestamp signature, the timestamping
certificate, and the manifest before evaluating `run.js`.
The builder's `context.d.ts` exclusion is only a convenience. If another
builder includes that file, verification includes it in the timestamped
manifest like any other package file.
The manifest sorts UTF-8 relative paths and records each file as
`<sha256>  <path>`. The
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
