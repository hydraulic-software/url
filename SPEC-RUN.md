# Spec for the `run` command

The `run` command takes as its first argument either:

1. An HTTP or HTTPS URL. If the URL scheme is missing `https://` is inferred. `/run.zip/` is appended to determine the actual URL to resolve using the algorithm defined in the [URL spec](SPEC.md) (i.e. the zip is unpacked to the cache).
2. A directory, which should contain the same files the `run.zip` file would.

The URL or directory name can have a version string after an `@` character. The version string can be anything that matches the following regex: TODO.

Inside the zip/directory there must be at least a `run.pkl` file, written in the [Pkl language](https://pkl-lang.org/). Pkl is a simple expression language created by Apple for evaluating configuration. You can treat it as hardly different to JSON, but its features often simplify things.

The evaluated Pkl defines two things:

- A set of files to resolve into the cache using the `url` algorithm.
- A launch plan containing a path to an executable, set of arguments and (in future versions of this spec) things like environment variables, resource limits, and sandbox permissions.

Why Pkl? Because:

- It is safe to evaluate potentially malicious Pkl files. Although they can do resource exhaustion attacks the interpreter can impose execution time limits. Pkl files can't execute code.
- It makes pure functional calculations easy.
- It has lots of features for string manipulation.

This may seem like overkill, but in later versions of this spec it will be useful for things like computing permissions based on command line arguments or even config files.

## Example `run.pkl`

This moderately complex launch plan downloads a fat JAR and runs it on the GraalVM JVM:

```pkl
import "run:context" as c

ver = c.ver ?? "0.14.0"

jdkArch =
  if (c.arch == "x86_64") "x64"
  else if (c.arch == "arm64") "aarch64"
  else throw("Unsupported architecture: \(c.arch)")

jdkPlatform = c.os + "-" + jdkArch

jdkHashes = new Mapping<String, String> {
  ["linux-x64"] = "76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276"
  ["linux-aarch64"] = "7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de"
  ["macos-x64"] = "a762ca1d9a163e32790b9286f3af4c16369729ff27999d8dbab60d7be16cff2f"
  ["macos-aarch64"] = "0b79e23c133facbad2f7aa55a3b76d17bd59d2fa15e2735bb63391ace223fd13"
  ["windows-x64"] = "5198b29a6448cb72b9a3cef10866723e2d1e2ec4799561a344e032bb7c61e555"
}

verifierHashes = new Mapping<String, String> {
  ["0.14.0"] = "25dae07dedab8b5997c3f08b798ce432ed8115bd8e782630c2db4dfaff064c2b"
}
verifierLock = if (verifierHashes.containsKey(ver)) "#sha256=\(verifierHashes[ver])" else ""

archive = if (c.os == "windows") "zip" else "tar.gz"
bundle = if (c.os == "macos") "Contents/Home/" else ""

urls {
  java = "https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_\(jdkPlatform)_bin.\(archive)/\(bundle)bin/java#sha256=\(jdkHashes[jdkPlatform])"
  verifier = "https://repo1.maven.org/maven2/dev/cel/verifier-cli/\(ver)/verifier-cli-\(ver).jar\(verifierLock)"
}

executable = c.resolved.java
arguments = List(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", c.resolved.verifier,
) + c.args
```

Most of this should be self-explanatory.

## Launch plan evaluation

The Pkl module is evaluated in two phases. The first phase evaluates its
`urls` property. `urls` must be an object whose properties have string values;
the property names are the names used to refer to the resolved paths. Each
value is passed to the ordinary URL resolver unchanged, including archive
members and hash fragments. The URL properties are resolved in parallel.

The second phase evaluates the complete module with the resolved paths
available through the `run:context` module. A package imports this module once:

```pkl
import "run:context" as c
```

The context provides these properties:

* `c.os`: `linux`, `macos`, `android`, `freebsd`, or `windows` on those systems.
* `c.arch`: `x86_64` on x86-64 systems and `arm64` on AArch64 systems.
* `c.ver`: the non-empty `@VERSION` suffix without the `@`, or `null` when none was given.
* `c.args`: the arguments passed after the locator.
* `c.packageDir`: the absolute path to the directory containing the `run.pkl` file.
* `c.resolved`: an object whose properties correspond to `urls` and contain
  absolute resolved paths.

The module must provide `executable` as a string and `arguments` as a list of
strings. A bare executable name is resolved using the host's normal command
search path. Other relative executable paths are resolved against
`c.packageDir`. `run` starts the resulting process directly and passes its
arguments unchanged. A package can therefore point `executable` at a bundled
executable or script if Pkl is insufficient or unwanted.

On Windows a URL in `urls` may omit a final `.exe` suffix. `run` first
resolves the URL exactly as written and, if the resource or archive member does
not exist, retries with `.exe` appended to the URL path. Query strings and hash
fragments are preserved.

A non-empty `@VERSION` suffix on the locator, before any query or fragment,
must be removed before local lookup or URL resolution and exposed as `c.ver`.
For example, `run example.com/tool@1.2.3` resolves `example.com/tool/run.zip/run.pkl` and
evaluates the package with `c.ver == "1.2.3"`.
Without a version suffix, `c.ver == null`.
