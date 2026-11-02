@echo off
rem One-time setup: downloads the portable build tools into tools\ (about 550 MB). See BUILDING.md.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
pause
