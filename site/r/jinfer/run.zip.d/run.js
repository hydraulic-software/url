import { graalvm } from "./graalvm.js";
import mri from "./mri.js";
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

// Parse once so permission logic can work with named options and positional arguments.
// Keep args untouched below: jinfer receives exactly the arguments the caller supplied.
const parsed = mri(args, {
  alias: { m: "model", o: "output", t: "threads" },
  string: ["model", "with", "cache", "cache-ro", "output", "threads", "theme", "color", "port"],
});
const command = parsed._.find(argument => commands.has(argument));
const commandIndex = parsed._.indexOf(command);

function optionValues(value) {
  if (typeof value === "string") return value ? [value] : [];
  if (Array.isArray(value)) return value.filter(item => typeof item === "string" && item.length > 0);
  return [];
}

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

// Jinfer accepts model and companion values in either --name value or --name=value form.
for (const model of optionValues(parsed.model)) addLocalRead(model);
for (const companionOption of optionValues(parsed.with)) {
  const companion = companionOption.split("=", 2)[1];
  addLocalRead(companion);
}

// Prompt caches are read and written; read-only caches only need read permission.
for (const path of optionValues(parsed.cache)) if (path !== "-") write.add(path);
for (const path of optionValues(parsed["cache-ro"])) if (path !== "-") read.add(path);

// '-' writes to stdout, which is already part of the baseline permissions.
if (command === "speak") {
  for (const path of optionValues(parsed.output)) if (path !== "-") write.add(path);
}

if (command === "transcribe") {
  // The first positional after the subcommand is the audio input; '-' means stdin.
  const input = parsed._[commandIndex + 1];
  if (input && input !== "-") read.add(input);
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
