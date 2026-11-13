[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$python = Join-Path $project '.venv/Scripts/python.exe'
$state = Join-Path $project 'data/server'
if (-not (Test-Path -LiteralPath $python)) { throw 'Create .venv and install backend/requirements.txt first; see README.md.' }
New-Item -ItemType Directory -Force -Path $state | Out-Null
$listener = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    try { $health = Invoke-RestMethod 'http://127.0.0.1:8765/api/health' -TimeoutSec 3 }
    catch { throw 'Port 8765 is in use by an unresponsive service. No process was stopped.' }
    if ($health.service -ne 'roomscan') { throw 'Port 8765 belongs to a different service. No process was stopped.' }
    Write-Output 'RoomScan service is already running.'
    exit 0
}
$process = Start-Process -FilePath $python -ArgumentList @('-m','uvicorn','backend.app:app','--host','127.0.0.1','--port','8765','--no-access-log') -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state 'service.stdout.log') -RedirectStandardError (Join-Path $state 'service.stderr.log')
@{pid=$process.Id;started_at=$process.StartTime.ToUniversalTime().ToString('o');project=$project} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $state 'service-process.json')
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8765/api/health' -TimeoutSec 2
        if ($health.service -eq 'roomscan') { Write-Output 'RoomScan service ready at http://127.0.0.1:8765'; exit 0 }
    } catch { }
    $process.Refresh()
    if ($process.HasExited) { throw "RoomScan service exited. Read $state/service.stderr.log" }
    Start-Sleep -Milliseconds 300
}
throw "RoomScan did not become ready. Read $state/service.stderr.log"
