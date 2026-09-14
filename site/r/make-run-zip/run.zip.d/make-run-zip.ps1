Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# The JavaScript launch plan supplies the downloaded tools first, followed by
# the user's source directory, output path, and optional TSA URL.
$openssl = $args[0]
$zip = $args[1]
$remaining = @($args | Select-Object -Skip 2)

$source = if ($remaining.Count -ge 1) { $remaining[0] } else { "." }
$output = if ($remaining.Count -ge 2) { $remaining[1] } else { "run.zip" }
$tsa = if ($remaining.Count -ge 3) { $remaining[2] } else { "http://timestamp.digicert.com" }
$source = [IO.Path]::GetFullPath($source)
$output = [IO.Path]::GetFullPath($output)

if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "Source directory does not exist: $source"
}

$sourceWithSeparator = $source.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($output.Equals($source, [StringComparison]::OrdinalIgnoreCase) -or
    $output.StartsWith($sourceWithSeparator, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Output archive must be outside the source directory: $output"
}

$opensslBinDir = Split-Path -Parent $openssl
$env:OPENSSL_CONF = Join-Path $opensslBinDir "..\ssl\openssl.cnf"
$env:OPENSSL_MODULES = Join-Path $opensslBinDir "..\lib\ossl-modules"

# Reparse points include Windows symbolic links and junctions. Reject them
# before enumeration so hashing and archiving cannot follow paths outside the
# source tree. context.d.ts is an intentionally excluded compatibility file.
$contextDeclaration = Join-Path $source "context.d.ts"
$reparsePoint = Get-ChildItem -LiteralPath $source -Recurse -Force | Where-Object {
    ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -and
    $_.FullName -ne $contextDeclaration
} | Select-Object -First 1
if ($null -ne $reparsePoint) {
    throw "Source directory contains unsupported symbolic links: $($reparsePoint.FullName)"
}

$temporaryZip = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
$timestampDirectory = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $timestampDirectory | Out-Null
$manifest = Join-Path $timestampDirectory "manifest"
$request = Join-Path $timestampDirectory "timestamp.tsq"
$response = Join-Path $timestampDirectory "timestamp.tsr"
$temporaryOutput = "$output.tmp"
try {
    # Build the same canonical manifest used by verifiers. It is temporary and
    # is intentionally not added to the package.
    $files = Get-ChildItem -LiteralPath $source -File -Recurse -Force | Where-Object {
        $_.FullName -ne (Join-Path $source "context.d.ts") -and
        $_.Name -ne "timestamp.tsr"
    }
    if (Get-ChildItem -LiteralPath $source -File -Recurse -Force | Where-Object Name -eq "timestamp.tsr") {
        throw "Source directory may not contain a file named timestamp.tsr"
    }
    $files = $files | Sort-Object {
        $relative = $_.FullName.Substring($source.Length + 1).Replace('\', '/')
        [String]::Join("", ([Text.Encoding]::UTF8.GetBytes($relative) | ForEach-Object { $_.ToString("X2") }))
    }
    $lines = foreach ($file in $files) {
        $relative = $file.FullName.Substring($source.Length + 1).Replace('\', '/')
        if ($relative.Contains("`n") -or $relative.Contains("`r")) {
            throw "Source file names may not contain newlines: $relative"
        }
        $digest = (& $openssl dgst -sha256 $file.FullName | Select-Object -Last 1) -replace '^.*= ', ''
        "$digest  $relative"
    }
    [IO.File]::WriteAllLines($manifest, $lines, [Text.UTF8Encoding]::new($false))

    # Create the ordinary ZIP before adding the timestamp entry. The timestamp
    # is over the manifest, so changing ZIP metadata does not invalidate it.
    Push-Location $source
    try {
        & $zip -X -r $temporaryZip . -x context.d.ts */context.d.ts timestamp.tsr
        if ($LASTEXITCODE -ne 0) { throw "zip failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    # Request the signer certificate so verifiers can validate the CMS token
    # without needing a separate certificate download.
    & $openssl ts -query -data $manifest -sha256 -no_nonce -cert -out $request
    if ($LASTEXITCODE -ne 0) { throw "openssl ts query failed with exit code $LASTEXITCODE" }

    Invoke-WebRequest -Uri $tsa -Method Post -ContentType "application/timestamp-query" `
        -Headers @{ Accept = "application/timestamp-reply" } -InFile $request -OutFile $response

    & $openssl ts -reply -in $response -text | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "timestamp authority returned an invalid response" }

    # Add the response under its reserved top-level name, then publish the
    # completed archive atomically.
    Push-Location $timestampDirectory
    try {
        & $zip -X $temporaryZip timestamp.tsr
        if ($LASTEXITCODE -ne 0) { throw "zip failed while adding timestamp.tsr" }
    } finally {
        Pop-Location
    }
    Move-Item -LiteralPath $temporaryZip -Destination $temporaryOutput -Force
    Move-Item -LiteralPath $temporaryOutput -Destination $output -Force
} finally {
    Remove-Item -LiteralPath $temporaryZip, $timestampDirectory, $temporaryOutput -Force -Recurse -ErrorAction SilentlyContinue
}

[Console]::WriteLine($output)
[Console]::Error.WriteLine("Added RFC 3161 timestamp from $tsa")
