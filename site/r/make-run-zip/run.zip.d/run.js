// These are pinned fallback binaries. The Unix launcher first tries the host's
// openssl and zip commands, so these URLs are only needed on minimal systems.
const assets = {
  linux: {
    x86_64: {
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-linux-x86_64-1d7aeb.tar.gz/openssl/bin/openssl#sha256=b773c841d89fc18dbdea76eb821882fda405a25e19bfe027d07ef04c863ac16",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-x64.tar.xz/7zz#sha256=dc99eff5008f1ab79bd7084c68513701547a808a89502bf4133683535ab3c695",
    },
    arm64: {
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-linux-aarch64-d734ba.tar.gz/openssl/bin/openssl#sha256=0fa809c139a872d51815d03c2587063047e97c0377217c16952825cdb8a379d8",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-linux-arm64.tar.xz/7zz#sha256=2389ba20e4d8295e8709c20b6263b69bd1ec4972fe38a04ad7a1badbf595b996",
    },
  },
  macos: {
    x86_64: {
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-macos-x86_64-1fbb0c.tar.zst/openssl/bin/openssl#sha256=38b16284e19bf5141c3e3319195fc57f8d47d5e9c0d2e7a6cb67e7e36fb90117",
      zip: "https://github.com/ip7z/7zip/releases/download/26.03/7z2603-mac.tar.xz/7zz#sha256=5ca87677072c59f5602e5c49baa27d4694bacd2259b4e507f0094249d4281480",
    },
    arm64: {
      openssl: "https://tb-build-03.torproject.org/~pierov/out/openssl/openssl-3.5.6-macos-aarch64-6f6807.tar.zst/openssl/bin/openssl#sha256=4bfd98415d8d364686a6c52e399288ecd230f7c8e86110ad6b5c0cbf42db512e",
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
  : [resolved.openssl, resolved.zip, ...context.args];

export default {
  executable: context.os === "windows" ? "powershell.exe" : script,
  arguments: argumentsFor,
};
