[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$version = '4.2.1'
$archive = Join-Path $PSScriptRoot 'colmap-x64-windows-cuda.zip'
$destination = Join-Path $PSScriptRoot "colmap-$version"
$expectedSha256 = 'e9c5cbd84c2ea986d2e970a2473fc2d2e6b34a2cdcf5d3df2765c319a63af881'
$url = "https://github.com/colmap/colmap/releases/download/$version/colmap-x64-windows-cuda.zip"

if (Test-Path -LiteralPath $destination) {
    throw "Destination already exists: $destination. Existing installation has not been changed."
}
if (-not (Test-Path -LiteralPath $archive)) {
    & curl.exe -L --fail --retry 2 --silent --show-error -o $archive $url
    if ($LASTEXITCODE -ne 0) { throw 'COLMAP download failed.' }
}
$actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expectedSha256) { throw 'COLMAP archive failed SHA-256 verification. Nothing extracted.' }
Expand-Archive -LiteralPath $archive -DestinationPath $destination
Write-Output "Installed COLMAP $version in $destination. No system PATH or drivers were changed."
