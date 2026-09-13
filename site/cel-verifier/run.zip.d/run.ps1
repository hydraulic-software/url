$ErrorActionPreference = "Stop"

$version = if ($VER) { $VER } else { "0.14.0" }
$arch = $ARCH

if ($arch -notin @("x86_64", "x64")) {
    Write-Error "cel-verifier: unsupported Windows architecture $arch"
    exit 1
}

$jdkUrl = "https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_windows-x64_bin.zip/"
$verifierUrl = "https://repo1.maven.org/maven2/dev/cel/verifier-cli/$version/verifier-cli-$version.jar"
$resolved = @(& url $jdkUrl $verifierUrl)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($resolved.Count -ne 2) {
    Write-Error "cel-verifier: expected url to resolve two paths, got $($resolved.Count)"
    exit 1
}
$jdkHome = $resolved[0]
$verifier = $resolved[1]

& (Join-Path $jdkHome "bin/java.exe") --enable-native-access=ALL-UNNAMED -jar $verifier repl @args
exit $LASTEXITCODE
