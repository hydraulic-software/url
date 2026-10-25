import { isWindows } from "./utils.js";

// These are pinned build tools. esbuild is resolved on every invocation, but
// the packaging scripts only run it when the source has installed JS modules.
const assets = {
  linux: {
    x86_64: {
      esbuild: "linux-x64",
      esbuildHash: "9573bb2233aab0f9ea7647d5cca9726113cc1768de61d66b17267f4db84488f6",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-x64.tar.xz/7zz#sha256=dc99eff5008f1ab79bd7084c68513701547a808a89502bf4133683535ab3c695",
    },
    arm64: {
      esbuild: "linux-arm64",
      esbuildHash: "a96dbfa41d3ef5dbd1ef22b1c10d5187be9267e86093a870f06402a7ec931596",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-arm64.tar.xz/7zz#sha256=2389ba20e4d8295e8709c20b6263b69bd1ec4972fe38a04ad7a1badbf595b996",
    },
  },
  macos: {
    x86_64: {
      esbuild: "darwin-x64",
      esbuildHash: "abb6a7a895aaf2cc0df36fecc3bf0042479ffec51a643a056d54e9109f27db55",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-mac.tar.xz/7zz#sha256=5ca87677072c59f5602e5c49baa27d4694bacd2259b4e507f0094249d4281480",
    },
    arm64: {
      esbuild: "darwin-arm64",
      esbuildHash: "1980cde09749094452b20d36ff267585ccb3f72749c7bc97291cd9996ccf5a2a",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-mac.tar.xz/7zz#sha256=5ca87677072c59f5602e5c49baa27d4694bacd2259b4e507f0094249d4281480",
    },
  },
  windows: {
    x86_64: {
      esbuild: "win32-x64",
      esbuildHash: "7286c3611b6f1f4c4d9ec90adcbc478407ff0d28ead96567f361d53e67613e19",
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-windows-x86_64-fc411d.tar.zst/openssl/bin/openssl.exe#sha256=8ff33c6e0261747b7212d61c77cc424578c24ca2395cf7036479c3d9e89b090e",
      zip: "https://raw.githubusercontent.com/ScoopInstaller/Binary/master/zip/x64/zip300xn-x64.zip/zip.exe#sha256=63f32f1f797680d9c68379d36cff4373674d405527d7fa2b4bbd34edcc3370d2",
    },
  },
};

const platformAssets = assets[context.os]?.[context.arch];
if (platformAssets === undefined)
  throw new Error(`make-run-zip does not support ${context.os}-${context.arch}`);

const esbuildVersion = "0.28.2";
const { esbuild: esbuildPackage, esbuildHash, ...toolAssets } = platformAssets;
const esbuildMember = isWindows ? "esbuild.exe" : "bin/esbuild";
const esbuildUrl = `https://registry.npmjs.org/@esbuild/${esbuildPackage}/-/${esbuildPackage}-${esbuildVersion}.tgz/package/${esbuildMember}#sha256=${esbuildHash}`;
const resolved = await urls({
  ...toolAssets,
  esbuild: esbuildUrl,
});
const script = `${context.packageDir}/make-run-zip.${isWindows ? "ps1" : "sh"}`;
const argumentsFor = isWindows
  ? ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, resolved.openssl, resolved.zip, resolved.esbuild, ...context.args]
  : [resolved.zip, resolved.esbuild, ...context.args];

export default {
  executable: isWindows ? "powershell.exe" : script,
  arguments: argumentsFor,
};
