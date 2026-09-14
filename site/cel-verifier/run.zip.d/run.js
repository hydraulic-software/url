import { graalvm } from "./graalvm.js";

const ver = context.ver ?? "0.14.0";
const graalvmVersion = "25.0.4+7.1";

const verifierHashes = {
  "0.14.0": "25dae07dedab8b5997c3f08b798ce432ed8115bd8e782630c2db4dfaff064c2b",
};
const verifierHash = verifierHashes[ver] ?? "";
const lock = verifierHash ? `#sha256=${verifierHash}` : "";
const resolved = urls({
  java: graalvm(graalvmVersion),
  verifier: `https://repo1.maven.org/maven2/dev/cel/verifier-cli/${ver}/verifier-cli-${ver}.jar${lock}`,
});

export default {
  executable: resolved.java,
  arguments: [
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", resolved.verifier,
    ...context.args,
  ],
};
