import { graalwasm } from "./graalwasm.js";
import { mapEsbuildArguments } from "./esbuild-args.js";

const esbuildVersion = context.ver ?? "0.28.2";

// Start both downloads before resolving either one so they can overlap.
const wasmLauncher = graalwasm();
const esbuildWasm = url(
  `https://registry.npmjs.org/@esbuild/wasi-preview1/-/wasi-preview1-${esbuildVersion}.tgz/package/esbuild.wasm`,
);

// We're going to look at the CLI arguments to figure out two things:
//
// 1. What filesystem permissions to grant, for hosts and run implementations that support kernel sandboxing.
// 2. What arguments to pass to esbuild, which may be different from the CLI arguments as we need to remap them to point
//    at the virtual file system exposed by WASI (files are in /work).
//
// The result is that you can invoke this package like a regular esbuild, but it runs sandboxed by WASM and also
// (sometimes) in a kernel sandbox.
const esbuild = mapEsbuildArguments(context.args, context.cwd);

export default {
  executable: await wasmLauncher,
  arguments: [
    `--wasm.WasiMapDirs=${esbuild.wasiMapDirs}`,
    await esbuildWasm,
    ...esbuild.arguments,
  ],
  permissions: esbuild.permissions,
};
