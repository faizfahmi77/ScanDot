[CmdletBinding()]
param([string]$Serial, [switch]$Workstation)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $project 'android-app/app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build android-app with gradlew.bat assembleDebug first.' }
if (-not $Serial) {
    $devices = @((& adb devices) | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect exactly one authorized phone or pass -Serial.' }
    $Serial = $devices[0]
}
& adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
if ($Workstation) {
    & adb -s $Serial reverse tcp:8765 tcp:8765
    if ($LASTEXITCODE -ne 0) { throw 'USB connection forwarding failed.' }
}
& adb -s $Serial shell am start -n com.roomscan.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Output 'RoomScan installed and launched. Choose Scan on this phone for local 3D scanning.'
if ($Workstation) { Write-Output 'Workstation forwarding enabled. Use tools/start-server.ps1 for video reconstruction.' }
