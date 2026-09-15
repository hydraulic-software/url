// These are pinned fallback binaries. Unix requires a local openssl because
// portable OpenSSL archives are not published from a stable upstream source;
// it only needs a downloaded zip tool when the host has no zip command.
const assets = {
  linux: {
    x86_64: {
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-x64.tar.xz/7zz#sha256=dc99eff5008f1ab79bd7084c68513701547a808a89502bf4133683535ab3c695",
    },
    arm64: {
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-arm64.tar.xz/7zz#sha256=2389ba20e4d8295e8709c20b6263b69bd1ec4972fe38a04ad7a1badbf595b996",
    },
  },
  macos: {
    x86_64: {
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-mac.tar.xz/7zz#sha256=5ca87677072c59f5602e5c49baa27d4694bacd2259b4e507f0094249d4281480",
    },
    arm64: {
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-mac.tar.xz/7zz#sha256=5ca87677072c59f5602e5c49baa27d4694bacd2259b4e507f0094249d4281480",
    },
  },
  windows: {
    x86_64: {
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-windows-x86_64-fc411d.tar.zst/openssl/bin/openssl.exe#sha256=8ff33c6e0261747b7212d61c77cc424578c24ca2395cf7036479c3d9e89b090e",
      zip: "https://raw.githubusercontent.com/ScoopInstaller/Binary/master/zip/x64/zip300xn-x64.zip/zip.exe#sha256=63f32f1f797680d9c68379d36cff4373674d405527d7fa2b4bbd34edcc3370d2",
    },
  },
};

const platformAssets = assets[context.os]?.[context.arch];
if (platformAssets === undefined)
  throw new Error(`make-run-zip does not support ${context.os}-${context.arch}`);

const resolved = urls(platformAssets);
const script = `${context.packageDir}/make-run-zip.${context.os === "windows" ? "ps1" : "sh"}`;
const argumentsFor = context.os === "windows"
  ? ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, resolved.openssl, resolved.zip, ...context.args]
  : [resolved.zip, ...context.args];

export default {
  executable: context.os === "windows" ? "powershell.exe" : script,
  arguments: argumentsFor,
};
