# Sample run packages

Each sample has a `run.zip.d` source directory. Reusable JavaScript helpers
live in [`shared`](shared). Each sample's `shared-files.txt` lists the helpers
to include, including their dependencies. Edit the shared file to update every
consumer. Each source package also
links `context.d.ts` to the declarations in this directory for editor support.

Source assembly is implemented by the `hydraulic.url.run-samples` precompiled
convention plugin in `build-logic`, included through `settings.gradle.kts`.
When using this module in the larger Hydraulic build, include its `build-logic`
directory in that build's `pluginManagement` block as well. Both build definitions
apply the same plugin.

Assemble all samples from the repository root:

```shell
./gradlew assembleRunSamples
```

Or select a sample:

```shell
./gradlew packageJinferRunSample
run ./build/run-packages/jinfer/run.zip --help
```

`packageRunSamples` packages every sample. Each `package<Name>RunSample` task
assembles its source, installs locked npm dependencies with `npm ci` when both
`package.json` and `package-lock.json` are present, then invokes the actual
`make-run-zip` launcher to bundle and timestamp the archive. Source directories
and ZIPs are written under `build/run-packages/<sample>/`; shared helpers are
copied into the assembled source, preserving executable bits and excluding
`context.d.ts` and stale timestamps. Published ZIPs contain ordinary files
and need no shared server files.

The website workflow uses `publishRunSamples`, which packages the samples and
copies each ZIP beside its source directory under `site/r/`.

Use `assembleRunSamples` (or `assembleJinferRunSample`) to build only the
runnable directories. Gradle tracks the sample sources, helper lists and shared
files and removes obsolete files during assembly. `./gradlew test` assembles
these directories for launcher tests; package tests create archives with
`make-run-zip`.

The website workflow uses the assembled source directories. To create a
timestamped archive, pass one to [make-run-zip](make-run-zip/README.md).
