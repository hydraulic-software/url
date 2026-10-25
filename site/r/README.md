# Sample run packages

Each sample has a `run.zip.d` source directory. Reusable JavaScript helpers
live in [`shared`](shared). Each sample's `shared-files.txt` lists the helpers
to include, including their dependencies. Edit the shared file to update every
consumer. Each source package also
links `context.d.ts` to the declarations in this directory for editor support.

Packaging is implemented by the `hydraulic.url.run-samples` precompiled
convention plugin in `build-logic`, included through `settings.gradle.kts`.
When using this module in the larger Hydraulic build, include its `build-logic`
directory in that build's `pluginManagement` block as well. Both build definitions
apply the same plugin.

Build all samples from the repository root:

```shell
./gradlew packageRunSamples
```

Or select a sample:

```shell
./gradlew packageJinferRunSample
run ./build/run-packages/jinfer/run.zip.d --help
```

Gradle produces a runnable `run.zip.d` directory and a `run.zip` beside it
under `build/run-packages/<sample>/`. It copies the declared shared helpers,
preserves executable bits, and excludes `context.d.ts` and stale timestamps.
Use the generated directory for local launches: the source directory contains
only the sample's own files. Published ZIPs contain ordinary files and need no
shared server files.

Use `assembleRunSamples` (or `assembleJinferRunSample`) to build only the
runnable directories. Gradle tracks the sample sources, helper lists and shared
files, removes obsolete files during assembly, and creates reproducible ZIPs.
`./gradlew test` also builds and checks the packages.

The website workflow uses the same Gradle tasks and publishes each ZIP beside its
source directory. To create a timestamped archive, pass the generated directory
to [make-run-zip](make-run-zip/README.md).
