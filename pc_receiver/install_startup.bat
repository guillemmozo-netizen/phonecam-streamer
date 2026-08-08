@echo off
rem Legacy/manual entry point - prefer Install_FrameCast.vbs (in the project
rem root), which does this plus the venv/dependency setup, silently, in one
rem click. This is kept for people who already have a working Python
rem environment and just want the Windows-startup registration by itself.
echo Adding FrameCast to Windows startup...

set "SHORTCUT=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\FrameCast.lnk"
set "TARGET=wscript.exe"
set "ARGS=""%~dp0FrameCast_Service.vbs"""
rem FrameCast.ico lives one level up, next to Install_FrameCast.vbs. Anyone
rem running this .bat straight out of a source checkout won't have it (it's a
rem build output), so fall back to a stock shell32 icon rather than leaving a
rem broken-icon shortcut behind.
set "ICON=%~dp0..\FrameCast.ico,0"
if not exist "%~dp0..\FrameCast.ico" set "ICON=%SystemRoot%\System32\shell32.dll,175"

powershell -NoProfile -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUT%'); $s.TargetPath = '%TARGET%'; $s.Arguments = '%ARGS%'; $s.WorkingDirectory = '%~dp0'; $s.IconLocation = '%ICON%'; $s.WindowStyle = 7; $s.Save()"

if %ERRORLEVEL% equ 0 (
    echo.
    echo Done! FrameCast_Service.vbs now registers at login, but it stays
    echo idle and shows no window until OBS is actually running - see
    echo logs\ if you need to check on it once OBS is open.
    echo To undo, delete the shortcut from:
    echo   %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\
) else (
    echo Failed to create shortcut.
)
echo.
pause
