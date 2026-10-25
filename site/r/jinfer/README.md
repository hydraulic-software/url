# Quixotic AI / jinfer

A sample `run.zip` for [Quixotic AI's jinfer CLI](https://github.com/qxoticai/qxotic/tree/main/jinfer/jinfer-cli).
The published executable jar includes the model providers and JAM backends, so
one launcher covers chat, one-shot generation, HTTP serving, speech synthesis,
and transcription. It needs neither a preinstalled JDK nor JBang.

Build the sample from the repository root, then launch the generated directory:

```shell
./gradlew packageJinferRunSample
run ./build/run-packages/jinfer/run.zip.d --help
run ./build/run-packages/jinfer/run.zip.d --version
run ./build/run-packages/jinfer/run.zip.d instruct -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0 "Explain virtual threads in one sentence."
run ./build/run-packages/jinfer/run.zip.d chat -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0
run ./build/run-packages/jinfer/run.zip.d server -m hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0 --port 54154
run ./build/run-packages/jinfer/run.zip.d speak -m ./inflect.gguf -o hello.wav "Hello world."
run ./build/run-packages/jinfer/run.zip.d transcribe -m ./parakeet.gguf recording.wav
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

`--help` and `--version` do not download models. Inference with a hub reference
downloads models on first use into jinfer's own cache; use local model paths
for offline inference. This sample runs with the user's normal permissions,
allowing jinfer to manage its model cache, read input files, write audio and
serve HTTP. Inspect the launch plan without starting Java:

```shell
run -d ./build/run-packages/jinfer/run.zip.d instruct -m ./model.gguf "Hello."
```

The build command also creates `build/run-packages/jinfer/run.zip`, with
shared helpers assembled alongside the launcher. The site's deployment
workflow uses the same Gradle tasks to publish `site/r/jinfer/run.zip`. See the
[sample authoring guide](../README.md) for the shared helper layout.

Host that archive as `https://example.com/jinfer/run.zip`, then launch it with
`run example.com/jinfer --help`. See [make-run-zip](../make-run-zip/README.md)
to create a timestamped package instead.
