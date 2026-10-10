export const isMac = context.os === "macos";
export const isWindows = context.os === "windows";
export const isLinux = context.os === "linux";

/** Maps `context.arch` to the names most download sites use. */
export function mapArch(arch = context.arch) {
  switch (arch) {
    case "x86_64": return "x64";
    case "arm64": return "aarch64";
    default: return arch;
  }
}
