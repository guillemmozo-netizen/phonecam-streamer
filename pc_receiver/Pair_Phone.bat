@echo off
title FrameCast - Pair a phone over Wi-Fi
echo.
echo   ==========================================
echo      FrameCast - pair a phone over Wi-Fi
echo   ==========================================
echo.
echo   Only needed once per phone, and only if you
echo   want to stream over Wi-Fi. Over USB there is
echo   nothing to pair - just plug the phone in.
echo.

rem Opening the window is a loopback-only request, which is the whole security
rem model: only something running on this PC can reach 127.0.0.1, so running
rem this file *is* the proof of physical access. The window then lets exactly
rem one phone collect the streaming token, and closes the moment it does.
set "FC_PYTHON=%~dp0.venv\Scripts\python.exe"
if not exist "%FC_PYTHON%" set "FC_PYTHON=python"

pushd "%~dp0.."
"%FC_PYTHON%" -m pc_receiver.pair_phone
set "FC_RESULT=%ERRORLEVEL%"
popd

echo.
if "%FC_RESULT%"=="0" (
    echo   Now open FrameCast on the phone, go to
    echo   Settings and tap "Find PC".
) else (
    echo   Pairing could not be started. FrameCast has to
    echo   be running for this to work, which it is while
    echo   OBS is open - so open OBS, then run this again.
)
echo.
pause
