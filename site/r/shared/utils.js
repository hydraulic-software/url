export const isMac = context.os === "macos";
export const isWindows = context.os === "windows";
export const isLinux = context.os === "linux";

/** Returns whether a path uses either separator recognized by the sample launchers. */
export function hasPathSeparator(value) {
  return value.includes("/") || value.includes("\\");
}

/**
 * Heuristically recognizes CLI values that look like local paths without filesystem access.
 * Bare values with a filename extension are accepted up to `maxSegments`; longer slash-separated
 * values are left for tools that use them as remote identifiers.
 */
export function looksLikeLocalPath(value, { maxSegments = 2 } = {}) {
  if (typeof value !== "string" || !value || value === "-" || value.includes("://")) return false;
  if (isAbsolutePath(value)) return true;
  if (["./", "../", ".\\", "..\\"].some(prefix => value.startsWith(prefix))) return true;

  const pathParts = value.split(/[\\/]/);
  const filename = pathParts[pathParts.length - 1];
  const dot = filename.lastIndexOf(".");
  const hasFilenameExtension = dot > 0 && dot < filename.length - 1;
  return hasFilenameExtension && pathParts.length <= maxSegments;
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
