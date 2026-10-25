# esbuild on GraalWasm

A sample `run.zip` that runs esbuild's WASI Preview 1 build with GraalWasm.
This uses `@esbuild/wasi-preview1/esbuild.wasm`, not the separate `esbuild-wasm`
npm package, which uses JavaScript host glue.

Build the sample archive from the repository root:

```shell
./gradlew assembleEsbuildWasmRunSample
run ./build/run-packages/esbuild-wasm/run.zip.d --version
run ./build/run-packages/esbuild-wasm/run.zip.d src/index.js --bundle --outfile=dist/bundle.js
run -d ./build/run-packages/esbuild-wasm/run.zip.d src/index.js --bundle --outfile=dist/bundle.js
```

The package defaults to esbuild **0.28.2**. Select another version with a
versioned locator such as `./build/run-packages/esbuild-wasm/run.zip.d@0.28.1`.
GraalWasm is pinned to **25.0.0** because that is the release with standalone
archives linked by its repository README. GraalWasm has archives for Linux
x64/ARM64, macOS x64/Apple Silicon and Windows x64; other platforms fail before
downloads start.

The launcher maps the working directory to `/work` inside WASI and rewrites
file-valued CLI arguments to their guest paths. It derives Hydraulic's host
file permissions from entry points and `--inject`, `--tsconfig`, `--outfile`,
`--outdir`, and `--metafile`. Project files under the current directory are
readable; requested output files or directories are writable. Files outside
the working directory get their own WASI mount and matching host permission.
Network access is denied. A WASI preopen maps a directory and does not make it
read-only; the exec plan separately declares which paths need writes. On macOS,
Seatbelt enforces those grants. Other platforms currently enforce only
environment filtering, so the WASI mappings still expose mapped directories to
the guest with the rights GraalWasm provides.

The esbuild WASI build is experimental and upstream says it is untested and
unsupported. WASI Preview 1 does not provide sockets, so esbuild's development
server is unavailable. Keep project dependencies, `tsconfig.json` files and
other imported assets within the working directory or pass their paths
explicitly. Use `run -d` to inspect the derived permissions without launching
the tool.
