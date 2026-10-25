import { graalvm } from "./graalvm.js";

const ver = context.ver ?? "0.3.1";
const hashes = {
  "0.3.1": "ca0dd45cf6d49c474663d2e802d69b2c756d301c3d64e956a458f7db29b43c18",
};
const lock = hashes[ver] ? `#sha256=${hashes[ver]}` : "";

const java = graalvm();
const jinfer = url(`https://repo.maven.apache.org/maven2/com/qxotic/jinfer-cli/${ver}/jinfer-cli-${ver}.jar${lock}`);

export default {
  executable: await java,
  arguments: [
    "--add-modules", "jdk.incubator.vector,jdk.httpserver",
    "--enable-native-access=ALL-UNNAMED",
    "-jar", await jinfer,
    ...context.args,
  ],
};
