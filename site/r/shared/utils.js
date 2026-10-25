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

/** Normalizes a host path against a base path, preserving the platform's separator. */
export function normalizePath(path, basePath = context.cwd, windows = isWindows) {
  const slashPath = String(path).replaceAll("\\", "/");
  const base = String(basePath).replaceAll("\\", "/");
  const absolute = isAbsolutePath(slashPath) ? slashPath : `${base}/${slashPath}`;
  const unc = windows && absolute.startsWith("//");
  const prefix = absolute.match(/^[A-Za-z]:\//)?.[0] ?? (unc ? "//" : absolute.startsWith("/") ? "/" : "");
  const parts = absolute.slice(prefix.length).split("/");
  const normalized = [];
  for (const part of parts) {
    if (!part || part === ".") continue;
    // A UNC server and share form the root; `..` cannot climb above the share.
    if (part === "..") {
      if (normalized.length > (unc ? 2 : 0)) normalized.pop();
    } else normalized.push(part);
  }
  const result = `${prefix}${normalized.join("/")}` || "/";
  return windows ? result.replaceAll("/", "\\") : result;
}

/** Returns the parent directory of a normalized host path. */
export function parentPath(path) {
  const normalized = path.replaceAll("\\", "/").replace(/\/$/, "");
  const slash = normalized.lastIndexOf("/");
  if (slash < 0) return ".";
  if (slash === 0) return "/";
  if (slash === 2 && /^[A-Za-z]:/.test(normalized)) return normalized.slice(0, 3);
  return normalized.slice(0, slash);
}

/** Tests whether a normalized path is equal to or below a directory. */
export function isWithinPath(path, directory, windows = isWindows) {
  const comparable = value => {
    const normalized = value.replaceAll("\\", "/");
    return windows ? normalized.toLowerCase() : normalized;
  };
  const child = comparable(path).replace(/\/$/, "");
  const root = comparable(directory).replace(/\/$/, "");
  return child === root || child.startsWith(`${root}/`);
}

/** Returns a normalized path relative to a normalized directory. */
export function relativePath(path, directory, windows = isWindows) {
  const child = path.replaceAll("\\", "/");
  const root = directory.replaceAll("\\", "/").replace(/\/$/, "");
  const samePath = windows ? child.toLowerCase() === root.toLowerCase() : child === root;
  return samePath ? "" : child.slice(root.length + 1);
}

/** Maps `context.arch` to the names most download sites use. */
export function mapArch(arch = context.arch) {
  switch (arch) {
    case "x86_64": return "x64";
    case "arm64": return "aarch64";
    default: return arch;
  }
}
