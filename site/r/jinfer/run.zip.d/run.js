import { graalvm } from "./graalvm.js";
import { hasPathSeparator, isAbsolutePath } from "./utils.js";

// Hash locking for extra security against supply chain attacks: this will catch the case where the uploaded JAR changes unexpectedly.
const ver = context.ver ?? "0.3.1";
const hashes = {
  "0.3.1": "ca0dd45cf6d49c474663d2e802d69b2c756d301c3d64e956a458f7db29b43c18",
};
const lock = hashes[ver] ? `#sha256=${hashes[ver]}` : "";

// Sandboxing! We _could_ grant very broad permissions, but we can do better by looking at the arguments.
// Also, let's relocate jinfer's models and temporary lock files under its own reverse-DNS cache directory
// so it doesn't need read/write access to /tmp
const cache = `${context.cacheDir}/ai.qxotic.jinfer`;

const args = context.args;
const read = new Set();
const write = new Set([cache]);
const commands = new Set(["instruct", "prompt", "chat", "server", "serve", "speak", "transcribe"]);
// Global options such as --model may come before or after the subcommand.
const commandIndex = args.findIndex(argument => commands.has(argument));
const command = args[commandIndex];

function hasFilenameExtension(pathParts) {
  const filename = pathParts[pathParts.length - 1];
  const dot = filename.lastIndexOf(".");
  return dot > 0 && dot < filename.length - 1;
}

function isLocalPath(value) {
  if (!value || value === "-" || value.includes("://")) return false;
  // run.js cannot check whether a path exists, so recognize explicit paths and common filenames.
  // Use ./ or an absolute path when a nested path could also look like a hub reference.
  if (isAbsolutePath(value)) return true;
  if (["./", "../", ".\\", "..\\"].some(prefix => value.startsWith(prefix))) return true;

  const pathParts = value.split(/[\\/]/);
  return hasFilenameExtension(pathParts) && pathParts.length <= 2;
}

function addLocalRead(value) {
  // Hub references contain a slash; a bare model or companion name is a local file.
  if (isLocalPath(value) || (value && !hasPathSeparator(value))) read.add(value);
}

function optionValue(argument, longName, shortName) {
  if (argument.startsWith(`${longName}=`)) return argument.slice(longName.length + 1);
  if (shortName && argument.startsWith(`${shortName}=`)) return argument.slice(shortName.length + 1);
  return null;
}

for (let i = 0; i < args.length; i++) {
  const argument = args[i];
  // Scan the whole argument list because jinfer allows these options before or after the command.
  if (argument === "-m" || argument === "--model") {
    // It might be a HuggingFace reference or similar, but that doesn't matter. Permissions to files that don't exist
    // are fine and don't break anything.
    addLocalRead(args[++i]);
  } else if (argument.startsWith("--model=") || argument.startsWith("-m=")) {
    addLocalRead(optionValue(argument, "--model", "-m"));
  } else if (argument === "--with") {
    const companion = args[++i]?.split("=", 2)[1];
    addLocalRead(companion);
  } else if (argument.startsWith("--with=")) {
    addLocalRead(argument.slice("--with=".length).split("=", 2)[1]);
  } else if (argument === "--cache") {
    // Jinfer loads and appends this cache, and write access also includes read access.
    if (args[i + 1] && args[i + 1] !== "-") write.add(args[++i]);
  } else if (argument.startsWith("--cache=")) {
    const path = optionValue(argument, "--cache");
    if (path !== "-") write.add(path);
  } else if (argument === "--cache-ro") {
    // This option never modifies the saved prompt state.
    if (args[i + 1] && args[i + 1] !== "-") read.add(args[++i]);
  } else if (argument.startsWith("--cache-ro=")) {
    const path = optionValue(argument, "--cache-ro");
    if (path !== "-") read.add(path);
  } else if (command === "speak" && (argument === "-o" || argument === "--output")) {
    // '-' writes to stdout, which is already part of the baseline permissions.
    if (args[i + 1] && args[i + 1] !== "-") write.add(args[++i]);
  } else if (command === "speak" && (argument.startsWith("--output=") || argument.startsWith("-o="))) {
    const path = optionValue(argument, "--output", "-o");
    if (path !== "-") write.add(path);
  }
}

if (command === "transcribe") {
  // The transcription input is positional. Skip option values to find it, while '-' uses baseline stdin.
  const optionsWithValues = new Set(["-m", "--model", "--with", "-t", "--threads", "--theme", "--color"]);
  for (let i = commandIndex + 1; i < args.length; i++) {
    const argument = args[i];
    if (argument === "--") {
      if (args[i + 1] && args[i + 1] !== "-") read.add(args[i + 1]);
      break;
    }
    if (optionsWithValues.has(argument)) {
      i++;
    } else if (argument.startsWith("-")) {
      continue;
    } else {
      if (argument !== "-") read.add(argument);
      break;
    }
  }
}

const java = graalvm();
const jinfer = url(`https://repo.maven.apache.org/maven2/com/qxotic/jinfer-cli/${ver}/jinfer-cli-${ver}.jar${lock}`);

export default {
  executable: await java,
  arguments: [
    `-Djinfer.models=${cache}/models`,
    `-Djava.io.tmpdir=${cache}`,
    "--add-modules", "jdk.incubator.vector,jdk.httpserver",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", await jinfer,
    ...args,
  ],
  permissions: {
    ...(read.size ? { "std.files.read": [...read] } : {}),
    // Write grants include read access, which jinfer needs for its own cache.
    "std.files.write": [...write],
    "std.network.client": true,
    // Only server commands need inbound connections; other commands keep this denied.
    "std.network.server": command === "server" || command === "serve",
  },
};
