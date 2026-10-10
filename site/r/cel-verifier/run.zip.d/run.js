import { graalvm } from "./graalvm.js";

const ver = context.ver ?? "0.14.0";
const verifierHashes = {
  "0.14.0": "25dae07dedab8b5997c3f08b798ce432ed8115bd8e782630c2db4dfaff064c2b",
};
const lock = verifierHashes[ver] ? `#sha256=${verifierHashes[ver]}` : "";

// Start every download before awaiting any of them, so they run concurrently.
const java = graalvm();
const verifier = url(`https://repo1.maven.org/maven2/dev/cel/verifier-cli/${ver}/verifier-cli-${ver}.jar${lock}`);

export default {
  executable: await java,
  arguments: [
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", await verifier,
    ...context.args,
  ],
};
