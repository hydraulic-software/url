import {
  isWindows,
  normalizePath,
  parentPath,
  isWithinPath,
  relativePath,
} from "./utils.js";

const knownValueOptions = new Set([
  "abs-paths", "alias", "asset-names", "banner", "charset", "conditions", "drop",
  "entry-names", "external", "footer", "format", "global-name", "jsx", "jsx-factory",
  "jsx-fragment", "jsx-import-source", "legal-comments", "line-limit", "loader",
  "log-level", "log-limit", "log-override", "main-fields", "mangle-cache", "mangle-props",
  "metafile", "node-paths", "outbase", "out-extension", "outfile", "outdir", "packages",
  "platform", "public-path", "pure", "reserve-props", "resolve-extensions", "source-root",
  "sources-content", "supported", "target", "tsconfig", "tsconfig-raw",
]);
const readFileOptions = new Set(["tsconfig"]);
const readDirectoryOptions = new Set(["outbase"]);
const writeFileOptions = new Set(["metafile", "outfile"]);
const writeDirectoryOptions = new Set(["outdir"]);

/**
 * Rewrites esbuild's path arguments to WASI guest paths and derives the matching
 * host-side directory mappings and file grants. Other arguments are
 * left as supplied so esbuild keeps control of its full CLI surface.
 */
export function mapEsbuildArguments(args, workingDirectory) {
  const cwd = normalizePath(workingDirectory, workingDirectory);
  const mounts = [{ host: cwd, guest: "/work" }];
  const reads = new Set([cwd]);
  const writes = new Set();
  const outputFiles = new Set();
  const rewrittenArgs = [...args];
  let afterOptions = false;
  let sourcemap = false;
  let externalLegalComments = false;

  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === "--") {
      afterOptions = true;
      continue;
    }
    if (afterOptions) {
      if (argument !== "-") rewrittenArgs[index] = mapEntryArgument(argument);
      continue;
    }
    if (argument === "--sourcemap" || argument.startsWith("--sourcemap=")) {
      sourcemap = argument === "--sourcemap" || !argument.endsWith("=inline");
      continue;
    }
    if (argument.startsWith("--legal-comments=")) {
      externalLegalComments = argument.endsWith("=external");
      continue;
    }
    if (argument.startsWith("--inject:")) {
      const value = argument.slice("--inject:".length);
      if (value) rewrittenArgs[index] = `--inject:${mapFile(value)}`;
      continue;
    }
    if (argument.startsWith("--inject=")) {
      rewrittenArgs[index] = `--inject:${mapFile(argument.slice("--inject=".length))}`;
      continue;
    }
    if (argument.startsWith("--")) {
      const equals = argument.indexOf("=");
      const name = argument.slice(2, equals < 0 ? undefined : equals);
      if (equals < 0) continue;
      const value = argument.slice(equals + 1);
      if (readFileOptions.has(name)) {
        rewrittenArgs[index] = `--${name}=${mapFile(value)}`;
      } else if (readDirectoryOptions.has(name)) {
        rewrittenArgs[index] = `--${name}=${mapDirectory(value)}`;
      } else if (writeFileOptions.has(name)) {
        rewrittenArgs[index] = `--${name}=${mapOutputFile(value, name === "outfile")}`;
      } else if (writeDirectoryOptions.has(name)) {
        rewrittenArgs[index] = `--${name}=${mapOutputDirectory(value)}`;
      } else if (knownValueOptions.has(name)) {
        continue;
      }
      continue;
    }
    if (argument === "-o") {
      const value = args[index + 1];
      if (value !== undefined) {
        rewrittenArgs[index] = `--outfile=${mapOutputFile(value)}`;
        rewrittenArgs[index + 1] = "";
        index++;
      }
      continue;
    }
    if (argument.startsWith("-o") && argument.length > 2) {
      const value = argument.slice(2).replace(/^=/, "");
      rewrittenArgs[index] = `--outfile=${mapOutputFile(value)}`;
      continue;
    }
    if (argument.startsWith("-") || argument === "-") continue;

    rewrittenArgs[index] = mapEntryArgument(argument);
  }

  // These optional sidecar files are derived from the primary output path.
  for (const output of outputFiles) {
    if (sourcemap) writes.add(`${output}.map`);
    if (externalLegalComments) writes.add(`${output}.LEGAL.txt`);
  }

  // GraalWasm preopens directories, so grant the parent of input files and
  // mount the parent of an output directory that may not exist yet.
  function mapFile(path) {
    if (!path || path === "-") return path;
    const absolute = normalizePath(path, cwd);
    reads.add(parentPath(absolute));
    return guestPath(absolute);
  }

  function mapEntryArgument(argument) {
    // esbuild's `output=entrypoint` form has an input path after the equals sign.
    const equals = argument.indexOf("=");
    return equals > 0
      ? `${argument.slice(0, equals)}=${mapFile(argument.slice(equals + 1))}`
      : mapFile(argument);
  }

  function mapOutputFile(path, includeSidecars = true) {
    if (!path || path === "-") return path;
    const absolute = normalizePath(path, cwd);
    writes.add(absolute);
    if (includeSidecars) outputFiles.add(absolute);
    return guestPath(absolute);
  }

  function mapOutputDirectory(path) {
    if (!path || path === "-") return path;
    const absolute = normalizePath(path, cwd);
    writes.add(absolute);
    return guestPath(absolute);
  }

  function mapDirectory(path) {
    if (!path) return path;
    const absolute = normalizePath(path, cwd);
    reads.add(absolute);
    return guestPath(absolute);
  }

  function guestPath(absolute) {
    // Prefer the most specific existing mount to avoid ambiguous guest paths.
    let mount = mounts
      .filter(candidate => isWithinPath(absolute, candidate.host))
      .sort((left, right) => right.host.length - left.host.length)[0];
    if (!mount) {
      const host = parentPath(absolute);
      mount = mounts.find(candidate => candidate.host === host);
      if (!mount) {
        mount = { host, guest: `/mount${mounts.length}` };
        mounts.push(mount);
      }
    }
    const relative = relativePath(absolute, mount.host);
    return relative ? `${mount.guest}/${relative.replaceAll("\\", "/")}` : mount.guest;
  }

  for (const { host } of mounts) {
    if (host.includes(",") || host.includes("::")) {
      throw new Error(`WASI directory mappings cannot represent this path: ${host}`);
    }
  }

  return {
    arguments: rewrittenArgs.filter(argument => argument !== ""),
    wasiMapDirs: mounts.map(({ host, guest }) => `${guest}::${host}`).join(","),
    permissions: {
      "std.files.read": [...reads],
      "std.files.write": [...writes],
      "std.network.client": false,
      "std.network.server": false,
    },
  };
}
