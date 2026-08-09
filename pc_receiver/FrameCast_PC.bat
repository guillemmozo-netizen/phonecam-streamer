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

rem -m pc_receiver.control_server from the parent folder, not a bare
rem control_server.py path: control_server does absolute package imports
rem (`from pc_receiver.obs_manager import ObsManager`) inside its OBS handlers,
rem which only resolve when pc_receiver/ is a package on sys.path - i.e. when
rem the working directory is its parent. Launched as a plain script the server
rem still came up and still spawned its children correctly (those already used
rem -m), but every OBS status/sync call failed with ModuleNotFoundError, so
rem "Sync OBS settings" silently did nothing on this path only.
rem FrameCast_Service.vbs has always launched it this way.
set "FC_PYTHON=%~dp0.venv\Scripts\python.exe"
if not exist "%FC_PYTHON%" set "FC_PYTHON=python"

pushd "%~dp0.."
"%FC_PYTHON%" -m pc_receiver.control_server
popd
pause
