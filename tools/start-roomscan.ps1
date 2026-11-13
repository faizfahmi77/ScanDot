[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'start-server.ps1')
if ($LASTEXITCODE -ne 0) { throw 'RoomScan service did not start.' }
$devices = @((& adb devices) | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($devices.Count -eq 1) {
    & adb -s $devices[0] reverse tcp:8765 tcp:8765
    if ($LASTEXITCODE -ne 0) { throw 'USB forwarding failed.' }
    & adb -s $devices[0] shell am start -n com.roomscan.app/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'RoomScan could not launch. Install the APK first.' }
    Write-Output 'RoomScan is ready on your phone.'
} else {
    Write-Output 'Laptop service is ready. Connect one authorized phone and run this script again to connect the app.'
}
