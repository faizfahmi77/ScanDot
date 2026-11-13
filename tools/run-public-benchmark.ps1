[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_-]{1,80}$')]
    [string]$RunName = ('public-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('room', 'office')]
    [string]$Sample = 'room',
    [ValidateRange(0.1, 30.0)]
    [double]$SampleRate = 5,
    [ValidateSet('sequential', 'exhaustive')]
    [string]$Matcher = 'sequential',
    [switch]$Dense
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$inputDirectory = Join-Path $project 'data/public'
$video = Join-Path $inputDirectory 'tum-freiburg1-room-rgb.avi'
$output = Join-Path $project "data/$RunName"
$colmap = Join-Path $PSScriptRoot 'colmap-4.2.1/bin/colmap.exe'
$url = 'https://cvg.cit.tum.de/rgbd/dataset/freiburg1/rgbd_dataset_freiburg1_room-rgb.avi'
$expectedHash = '904f2c932e82e1aa0acf0682800993803b5089b25e424421074ef4f27df7721a'
if ($Sample -eq 'office') {
    $video = Join-Path $inputDirectory 'tum-freiburg3-office-rgb.avi'
    $url = 'https://cvg.cit.tum.de/rgbd/dataset/freiburg3/rgbd_dataset_freiburg3_long_office_household-rgb.avi'
    $expectedHash = '3200d7a76f5b3307e33863d4b38dbad414527ce14084f4c1f6303a0f69eb794c'
}

if (Test-Path -LiteralPath $output) { throw "Run already exists: $output" }
if (-not (Test-Path -LiteralPath $colmap)) { throw 'Run tools/install-colmap.ps1 first.' }
New-Item -ItemType Directory -Force -Path $inputDirectory | Out-Null
if (-not (Test-Path -LiteralPath $video)) {
    & curl.exe -L --fail --retry 2 --silent --show-error -o $video $url
    if ($LASTEXITCODE -ne 0) { throw 'Public RGB video download failed.' }
}
if ((Get-FileHash -LiteralPath $video -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Sample differs from the recorded benchmark input. Inspect provenance before processing.'
}

Push-Location $project
try {
    $rate = $SampleRate.ToString([Globalization.CultureInfo]::InvariantCulture)
    & python -m processing.benchmark prepare $video --output $output --fps $rate
    if ($LASTEXITCODE -ne 0) { throw 'Video preparation failed; inspect the run manifest/logs.' }
    $reconstructionArgs = @('-m', 'processing.benchmark', 'reconstruct', $output, '--colmap', $colmap, '--matcher', $Matcher)
    if ($Dense) { $reconstructionArgs += '--dense' }
    & python @reconstructionArgs
    if ($LASTEXITCODE -ne 0) { throw 'Reconstruction failed; inspect the retained manifest/logs.' }
} finally {
    Pop-Location
}
