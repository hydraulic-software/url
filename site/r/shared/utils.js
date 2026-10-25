export const isMac = context.os === "macos";
export const isWindows = context.os === "windows";
export const isLinux = context.os === "linux";

/** Returns whether a path uses either separator recognized by the sample launchers. */
export function hasPathSeparator(value) {
  return value.includes("/") || value.includes("\\");
}

/** Recognizes POSIX, root-relative Windows, and drive-absolute paths without filesystem access. */
export function isAbsolutePath(value) {
  if (value.startsWith("/") || value.startsWith("\\")) return true;
  const drive = value[0]?.toLowerCase();
  const hasDriveLetter = drive >= "a" && drive <= "z";
  return hasDriveLetter && value[1] === ":" && (value[2] === "/" || value[2] === "\\");
}

/** Maps `context.arch` to the names most download sites use. */
export function mapArch(arch = context.arch) {
  switch (arch) {
    case "x86_64": return "x64";
    case "arm64": return "aarch64";
    default: return arch;
  }
}
