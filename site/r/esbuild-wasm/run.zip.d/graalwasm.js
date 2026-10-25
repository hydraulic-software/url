const version = "25.0.0";
const targets = {
  linux: { x86_64: "linux-amd64", arm64: "linux-aarch64" },
  macos: { x86_64: "macos-amd64", arm64: "macos-aarch64" },
  windows: { x86_64: "windows-amd64" },
};

/** Starts resolving the platform-specific GraalWasm standalone launcher. */
export function graalwasm() {
  const target = targets[context.os]?.[context.arch];
  if (!target) throw new Error(`GraalWasm does not support ${context.os}-${context.arch}`);

  const archive = target.startsWith("windows-") ? "zip" : "tar.gz";
  return url(
    `https://gds.oracle.com/download/wasm/archive/graalwasm-${version}-${target}.${archive}/graalwasm-${version}-${target}/bin/wasm${context.os === "windows" ? ".exe" : ""}`,
  );
}
