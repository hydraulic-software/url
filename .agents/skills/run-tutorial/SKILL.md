---
name: run-tutorial
description: Explain Hydraulic's run.zip format and guide authors creating or testing portable run launchers. Use when working with run.zip.d, run.sh, run.ps1, metadata.cel, runscript platform selection, permissions, or timestamped run.zip archives.
---

# Using `run`

The `run` command implements a simple specification, the `run.zip` spec. Given a URL it downloads a file called `run.zip` and uses the files inside to launch the intended program.

## Exploring cowsay

The `cowsay` demo can be used to learn how it works. Let's take a look:

```shell
$ hydraulic-software.github.io/url/cowsay "Hello, field!"
 ______________
< Hello, field >
 --------------
        \   ^__^
         \  (oo)\_______
            (__)\       )\/\
                ||----w |
                ||     ||

$ cd `url hydraulic-software.github.io/url/cowsay/run.zip/`
$ ls
run.sh   run.ps1   metadata.cel
```

A `run.zip` must contain at least `metadata.cel` and one or both of `run.sh` and `run.ps1`. The zip file itself is a bit special: it has some extra data prepended to it. We'll get to that later.  `run.ps1` is a PowerShell script for Windows and we'll get to that later too.

Inside source repositories we suggest these files are placed in a directory at the root called `run.zip.d`, but it's not required. 

## `run.sh`

Let's see what's inside:

```shell
$ cat run.sh
VER=${VER:-2.0.4}
case $OS in
  linux) p=$(url https://github.com/Code-Hex/Neo-cowsay/releases/download/v$VER/cowsay_$VER_Linux_$ARCH.tar.gz/cowsay) && exec "$p" $@
  macos) p=$(url https://github.com/Code-Hex/Neo-cowsay/releases/download/v$VER/cowsay_$VER_macOS_$ARCH.tar.gz/cowsay) && exec "$p" $@
  *) echo "Unsupported OS $OS"; exit 1
fi
```

There's no shebang line because this script isn't executed directly. It is sourced into a pre-prepared POSIX shell with `errexit` enabled. The wrapper defines unexported runscript variables and a `url` function that inherits resolver flags passed to `run`; they do not leak into the final program unless the runscript exports them. Scripts can be tested by passing a directory to `run` instead of a URL: `run ./run.zip.d --help`.

The `VER` variable might be set if the user invoked the program using `@1.2.3` syntax, like this: `run example.com@1.2.3` or `run example.com/someapp@v7-beta`. If it's not, the first line sets it to the current version.

The `OS` variable is defined by the runscript contract. It can be:

- `linux`
- `macos`
- `android`
- `freebsd`

and the set may expand in future versions of this spec, so the script should handle unrecognized values. The `ARCH` variable can be `x86_64`, `arm64` and the set may expand, so ditto.

_Any_ Linux distribution is _always_ considered to be Linux and not a unique OS. It's OK to do further tests against the filesystem or environment after reading `$OS == linux` to determine what distribution the user is on, if you'd like to change behaviors.

By contract `url` is available and on the PATH, so the `cowsay` script uses it to grab the right binaries off GitHub. Note the pattern:

- Ask `url` to resolve a local path into the disk cache of the `cowsay` binary itself, store that to a variable.
- If and only if that was successful, replace the currently running process with the target program.
- Otherwise return the exit code of `url` in the shell.

This ensures that missing releases or binaries yield a reasonable output and exit code.

The `run.zip` spec tries to be unopinionated, but `run.zip` should be small, and thus not contain the actual program itself unless the program is so trivial it's literally just a shell script. 

There are many possibilities for what you can do in a `run.zip`:

* You don't have to use `url` to fetch binaries. 
* You could install packages from the user's distribution repositories if you recognize the distro they're on, know the requested version is available, and know users of that Linux flavour would prefer it. 
* You could delegate to homebrew. 
* You could try those things and _then_ use `url` if they don't work. 

There is though a living document called [CONVENTIONS.md](references/CONVENTIONS.md) that defines the contract and expectations for a runscript, which you are strongly encouraged to follow. Read it before creating or modifying a runscript.

## Security

`run` doesn't immediately execute `run.sh`. That would be convenient for sure, but we live in an ever more dangerous world. Supply chain attacks are now common, especially on developers, so `run` tries to protect you from them with support for sandboxing.

Operating systems and even individual Linux distributions vary radically in how they support sandboxing, so `run` doesn't mandate any specific system or approach. Instead it lets programs advertise a set of abstract permissions which implementations of `run` can then enforce by delegating to OS specific APIs like Apple's Seatbelt, AppArmor, Landlock or Win32.

Let's have a look:

```shell
$ cat metadata.cel
{
  'permissions': {
  }
}
```

Cowsay declares no permissions!

## Windows

TODO

## Extra header data

We mentioned earlier that `run.zip` has extra data prepended to the front of the file. Zips are read from the end so this doesn't stop regular zip tools from reading them, but does make creating a valid `run.zip` slightly trickier. 

The extra data is a binary CMS timestamp that cryptographically proves when the zip was made. The purpose of having an unforgeable timestamp in the file format is to allow for versioned evolution in an environment that may contain adversaries. If someone discovers there's a need to change the spec for security reasons those changes can be tied to when the `run.zip` was made rather than a self-declared version number. For example:

- In January it is decided to tighten the default sandbox rules by revoking a permission that had previously always been granted. It's announced the change will come into force in July.
- The `run.zip` creator tool is updated to start warning developers that they must declare the new permission if they need it.
- In July `run` is updated to tighten the sandbox, but only for `run.zip` files that are timestamped after July 1st.

In other words the sandbox gets stricter and developers can't just lazily ignore it, but they only need to do something once someone rebuilds their `run.zip` and thus opts in to the new rules. Existing programs are left unaffected by the rule change. This technique can be used to mandate changes in a dynamic environment without breaking old unmaintained entry points.

Note that a CMS timestamp is not a digital signature. You don't need to manage private keys and there are many timestamping servers on the internet that are trusted out of the box.
