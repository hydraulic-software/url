import { graalvm } from "./graalvm.js";
import { parseArgs } from "./parse-args.js";
import { hasPathSeparator, looksLikeLocalPath } from "./utils.js";

// Most of this file is concerned with really making things very secure. A simpler version of this script is easy.

// Step 1. Hash locking to block simple hacks that swap out an already released version for a modified file.
// If we don't recognize the version the user requested, we'll skip this check.
const ver = context.ver ?? "0.3.1";
const hashes = {
  "0.3.1": "ca0dd45cf6d49c474663d2e802d69b2c756d301c3d64e956a458f7db29b43c18",
};
const lock = hashes[ver] ? `#sha256=${hashes[ver]}` : "";

// Kick off the downloads.
const java = graalvm();
const jinfer = url(`https://repo.maven.apache.org/maven2/com/qxotic/jinfer-cli/${ver}/jinfer-cli-${ver}.jar${lock}`);

// Step 2. Relocate the model cache and use of tmp so jinfer doesn't need read/write permissions to the global /tmp directory.
const cache = `${context.cacheDir}/ai.qxotic.jinfer`;
const cacheArguments = [
  `-Djinfer.models=${cache}/models`,
  `-Djava.io.tmpdir=${cache}`,
];

// Step 3. Sandboxing: parse the command line arguments and figure out the truly minimal set of permissions needed for this run.
const read = new Set();
const write = new Set([cache]);
const commands = new Set(["instruct", "prompt", "chat", "server", "serve", "speak", "transcribe"]);
const parsed = parseArgs(context.args, {
  strict: false,
  allowPositionals: true,
  options: {
    model: { type: "string", short: "m", multiple: true },
    with: { type: "string", multiple: true },
    cache: { type: "string" },
    "cache-ro": { type: "string" },
    output: { type: "string", short: "o" },
    threads: { type: "string", short: "t" },
    theme: { type: "string" },
    color: { type: "string" },
    port: { type: "string" },
  },
});
const command = parsed.positionals.find(argument => commands.has(argument));
const commandIndex = parsed.positionals.indexOf(command);

function optionValues(value) {
  if (typeof value === "string") return value ? [value] : [];
  if (Array.isArray(value)) return value.filter(item => typeof item === "string" && item.length > 0);
  return [];
}

function maybeAddLocalRead(value) {
  // Hub references contain a slash; a bare model or companion name is a local file.
  if (looksLikeLocalPath(value) || (value && !hasPathSeparator(value))) read.add(value);
}

// Jinfer accepts model and companion values in either --name value or --name=value form.
for (const model of optionValues(parsed.values.model)) maybeAddLocalRead(model);
for (const companionOption of optionValues(parsed.values.with)) {
  const companion = companionOption.split("=", 2)[1];
  maybeAddLocalRead(companion);
}

// Prompt caches are read and written; read-only caches only need read permission.
for (const path of optionValues(parsed.values.cache)) if (path !== "-") write.add(path);
for (const path of optionValues(parsed.values["cache-ro"])) if (path !== "-") read.add(path);

// '-' writes to stdout, which is already part of the baseline permissions.
if (command === "speak") {
  for (const path of optionValues(parsed.values.output)) if (path !== "-") write.add(path);
}

if (command === "transcribe") {
  // The first positional after the subcommand is the audio input; '-' means stdin.
  const input = parsed.positionals[commandIndex + 1];
  if (input && input !== "-") read.add(input);
}

// Keep the fixed JVM flags together here, then append the cache settings and caller's unchanged CLI.
export default {
  executable: await java,
  arguments: [
    ...cacheArguments,
    "--add-modules", "jdk.incubator.vector,jdk.httpserver",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", await jinfer,
    ...context.args,
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
