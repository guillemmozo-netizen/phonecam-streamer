@echo off
rem Legacy/manual entry point - prefer Install_FrameCast.vbs (in the project
rem root), which does this plus the venv/dependency setup, silently, in one
rem click. This is kept for people who already have a working Python
rem environment and just want the Windows-startup registration by itself.
echo Adding FrameCast to Windows startup...

set "SHORTCUT=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\FrameCast.lnk"
set "TARGET=wscript.exe"
set "ARGS=""%~dp0FrameCast_Service.vbs"""
set "ICON=%SystemRoot%\System32\shell32.dll"

powershell -NoProfile -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUT%'); $s.TargetPath = '%TARGET%'; $s.Arguments = '%ARGS%'; $s.WorkingDirectory = '%~dp0'; $s.IconLocation = '%ICON%,175'; $s.WindowStyle = 7; $s.Save()"

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
