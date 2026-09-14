const javaHashes = {
  "25.0.4+7.1": {
    "linux-x64": "76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276",
    "linux-aarch64": "7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de",
    "macos-x64": "a762ca1d9a163e32790b9286f3af4c16369729ff27999d8dbab60d7be16cff2f",
    "macos-aarch64": "0b79e23c133facbad2f7aa55a3b76d17bd59d2fa15e2735bb63391ace223fd13",
    "windows-x64": "5198b29a6448cb72b9a3cef10866723e2d1e2ec4799561a344e032bb7c61e555",
  },
};

function graalArch(arch) {
  switch (arch) {
    case "x86_64": return "x64";
    case "arm64": return "aarch64";
    default: return arch;
  }
}

function graalOs(os) {
  switch (os) {
    case "linux":
    case "macos":
    case "windows":
      return os;
    default:
      throw new Error(`Unsupported operating system for GraalVM: ${os}`);
  }
}

export function graalvm(version) {
  const platformOs = graalOs(context.os);
  const platform = `${platformOs}-${graalArch(context.arch)}`;
  const archive = platformOs === "windows" ? "zip" : "tar.gz";
  const bundle = platformOs === "macos" ? "Contents/Home/" : "";
  const hash = javaHashes[version]?.[platform];

  if (javaHashes[version] !== undefined && hash === undefined) {
    throw new Error(`Unsupported GraalVM platform: ${platform}`);
  }

  const majorVersion = version.split(".")[0];
  const lock = hash === undefined ? "" : `#sha256=${hash}`;
  return `https://download.oracle.com/graalvm/${majorVersion}/latest/graalvm-jdk-${majorVersion}_${platform}_bin.${archive}/graalvm-jdk-${version}/${bundle}bin/java${lock}`;
}
