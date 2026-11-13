@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-roomscan.ps1"
if errorlevel 1 pause
