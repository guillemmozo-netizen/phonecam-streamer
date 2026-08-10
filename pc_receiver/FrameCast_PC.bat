@echo off
title FrameCast PC Services
echo.
echo   ==========================================
echo          FrameCast Control Server
echo   ==========================================
echo.
echo   Services start automatically now - no
echo   need to tap anything. Just plug in a
echo   phone (USB) or open the app (WiFi).
echo.
echo   Close this window to stop everything.
echo.
if exist "%~dp0.venv\Scripts\python.exe" (
    "%~dp0.venv\Scripts\python.exe" "%~dp0control_server.py"
) else (
    python "%~dp0control_server.py"
)
pause
