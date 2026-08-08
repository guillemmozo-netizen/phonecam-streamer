@echo off
rem Does the actual venv-create + pip-install work for Install_FrameCast.vbs.
rem Kept as a separate plain .bat, not inline VBS commands, since cmd.exe's
rem quoting for multiple quoted segments on one line is unreliable.
setlocal
cd /d "%~dp0"

rem pyvenv.cfg, not Scripts\python.exe, is the definitive marker of a
rem complete venv: python.exe can be sitting there as a leftover from an
rem interrupted `python -m venv` (e.g. this process got killed mid-setup)
rem without pyvenv.cfg ever having been written, and then fails immediately
rem with "No pyvenv.cfg file" on every single invocation, which previously
rem showed up as a confusing "check your internet connection" pip failure
rem instead of the real cause. Wipe and recreate whenever it's missing.
if exist ".venv\pyvenv.cfg" goto venv_ok
if exist ".venv" rmdir /s /q ".venv" >> setup_log.txt 2>&1
python -m venv .venv > setup_log.txt 2>&1

:venv_ok
if not exist ".venv\Scripts\python.exe" (
    echo VENV_FAILED >> setup_log.txt
    exit /b 1
)

".venv\Scripts\python.exe" -m pip install --quiet --upgrade pip >> setup_log.txt 2>&1
".venv\Scripts\python.exe" -m pip install --quiet -r requirements.txt >> setup_log.txt 2>&1
if errorlevel 1 (
    echo PIP_FAILED >> setup_log.txt
    exit /b 1
)

echo SETUP_OK >> setup_log.txt
exit /b 0
