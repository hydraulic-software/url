# Quixotic AI / jinfer

A sample `run.zip` for [Quixotic AI's jinfer CLI](https://github.com/qxoticai/qxotic/tree/main/jinfer/jinfer-cli).
The published executable jar includes the model providers and JAM backends, so
one launcher covers chat, one-shot generation, HTTP serving, speech synthesis,
and transcription. It needs neither a preinstalled JDK nor JBang.

Build the sample archive from the repository root. Gradle copies shared
helpers, runs `npm ci` from the locked manifest, and invokes `make-run-zip` to
bundle the parser and create the timestamped archive.

```shell
./gradlew packageJinferRunSample
run ./build/run-packages/jinfer/run.zip --help
run ./build/run-packages/jinfer/run.zip --version
run ./build/run-packages/jinfer/run.zip instruct -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0 "Explain virtual threads in one sentence."
run ./build/run-packages/jinfer/run.zip chat -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0
run ./build/run-packages/jinfer/run.zip server -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0 --port 54154
run ./build/run-packages/jinfer/run.zip speak -m ./inflect.gguf -o hello.wav "Hello world."
run ./build/run-packages/jinfer/run.zip transcribe -m ./parakeet.gguf recording.wav
```

Use `speak --help`, `transcribe --help`, or `server --help` for model-specific
options and companion files. Other Quixotic components (such as GGUF,
Safetensors and Tok'n'Roll) are libraries rather than separate commands in this
package.

The default jinfer release is **0.3.1**, locked by SHA-256. Select another
published version with `run ./build/run-packages/jinfer/run.zip.d@0.3.0 --help`; versions
without a known hash resolve without a hash lock. All arguments pass through
unchanged; older releases can have different CLI syntax, so consult their
`--help`. The Java runtime resolves in parallel with the jar, using pinned
Oracle GraalVM 25 archives: 25.0.4 on Linux x64/ARM64, macOS ARM64 and Windows
x64; 25.0.1 (Oracle's last Intel Mac release) on macOS x64. Other platforms fail
before downloading.

The launch plan stores jinfer's models and lock files under
`context.cacheDir/ai.qxotic.jinfer`, using `-Djinfer.models` to keep model files
out of the shared Hugging Face cache and `-Djava.io.tmpdir` for jinfer's locks.
It requests write access to that application-specific directory and outbound
network access, so jinfer can download models itself. macOS uses the
DNS-style `ai.qxotic.jinfer` directory under `~/Library/Caches`; other platforms
use their normal cache root. The host does not create or grant access to
`context.cacheDir` implicitly.

The launcher requests read access for recognizable local model and companion
paths, prompt caches opened with `--cache-ro`, and the audio file passed to
`transcribe`. `--cache` and `speak --output` request write access to their
named paths. Use `./` or an absolute path for nested local models and
companions so they are distinguishable from hub references. The `server` and
`serve` commands also request permission to open a listening socket; other
commands do not. Permissions are derived from the CLI arguments. On macOS,
Seatbelt enforces the file and network grants for this JVM; other platforms
currently enforce environment filtering only. Inspect the launch plan without
starting Java:

```shell
run -d ./build/run-packages/jinfer/run.zip instruct -m ./model.gguf "Hello."
```

The source package imports upstream [`@pkgjs/parseargs`](https://github.com/pkgjs/parseargs)
to demonstrate bundling a CommonJS library for GraalJS. `make-run-zip` bundles
it into `run.js` at package creation time, then removes `node_modules`, leaving
a single ECMAScript module launcher. To package manually, assemble the source
with its shared helpers, install the locked npm dependency, then pass that
directory to the builder:

```shell
./gradlew assembleJinferRunSample assembleMakeRunZipRunSample
npm ci --prefix ./build/run-packages/jinfer/run.zip.d
run ./build/run-packages/make-run-zip/run.zip.d -- \
  ./build/run-packages/jinfer/run.zip.d ./build/run-packages/jinfer/manual.zip
```

See the [sample authoring guide](../README.md) for the shared helper layout.

Host that archive as `https://example.com/jinfer/run.zip`, then launch it with
`run example.com/jinfer --help`. See [make-run-zip](../make-run-zip/README.md)
to create a timestamped package instead.
