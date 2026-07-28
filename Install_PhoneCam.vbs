' PhoneCam PC - one-click, silent installer. Double-click this file (it runs
' via wscript.exe, which never shows a console window). Sets up a private
' Python environment and registers a watcher that starts PhoneCam whenever
' OBS is running - nothing else to run, ever again. Works from any folder:
' every path below is derived from this script's own location, never
' hardcoded, so it doesn't matter where the zip was extracted to.
Option Explicit

Dim fso, shell, rootDir, appDir, venvPy, helperBat, logPath
Dim pyCheck, setupResult, startupFolder, shortcutPath, shortcut

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

rootDir = fso.GetParentFolderName(WScript.ScriptFullName)
appDir = rootDir & "\pc_receiver"
venvPy = appDir & "\.venv\Scripts\python.exe"
helperBat = appDir & "\_setup_helper.bat"
logPath = appDir & "\setup_log.txt"

' 0. The pc_receiver folder must sit right next to this script, with all its
' files intact - if antivirus, an incomplete copy, or a half-finished
' extraction stripped something out, fail clearly here instead of hitting a
' confusing "file not found" a few lines down that looks like a network or
' pip problem.
If Not fso.FolderExists(appDir) Then
    MsgBox "Could not find the pc_receiver folder next to this installer." & vbCrLf & vbCrLf & _
           "Expected: " & appDir & vbCrLf & vbCrLf & _
           "Re-download/re-extract PhoneCam_PC_Setup.zip and make sure " & _
           "Install_PhoneCam.vbs and the pc_receiver folder stay together " & _
           "(don't move one without the other), then run this installer " & _
           "again.", vbCritical, "PhoneCam Setup - Error"
    WScript.Quit 1
End If
If Not fso.FileExists(helperBat) Then
    MsgBox "pc_receiver is missing _setup_helper.bat." & vbCrLf & vbCrLf & _
           "Expected: " & helperBat & vbCrLf & vbCrLf & _
           "The pc_receiver folder looks incomplete - re-download/" & _
           "re-extract PhoneCam_PC_Setup.zip (don't copy files out of it " & _
           "individually) and run this installer again.", _
           vbCritical, "PhoneCam Setup - Error"
    WScript.Quit 1
End If

' 1. Python must already be installed - nothing here can safely install it
' silently on the user's behalf.
pyCheck = shell.Run("cmd /c where python >nul 2>nul", 0, True)
If pyCheck <> 0 Then
    MsgBox "Python was not found on this PC." & vbCrLf & vbCrLf & _
           "Install it from https://www.python.org/downloads/" & vbCrLf & _
           "(tick ""Add python.exe to PATH"" during setup), then run " & _
           "this installer again.", vbExclamation, "PhoneCam Setup"
    WScript.Quit 1
End If

' 2 & 3. Create the venv (first run only) and install/update dependencies.
' Done via a single .bat rather than inline commands here: cmd.exe's
' quoting for `cmd /c "..."` gets unreliable once more than one quoted
' segment (an exe path plus a redirection target) is on the line - a
' single quoted .bat invocation avoids that class of bug entirely. The full
' path is required here too: some Windows configurations don't search the
' current directory for a bare filename passed to `cmd /c`, even with
' CurrentDirectory set correctly, and silently report it as not found -
' which is also why this doesn't matter which folder the installer lives in.
shell.CurrentDirectory = appDir
setupResult = shell.Run("cmd /c """ & helperBat & """", 0, True)

If setupResult <> 0 Or Not fso.FileExists(venvPy) Then
    Dim detail, ts, lines, lastLines, i, startIdx
    detail = "(no log file was created - setup may have failed to start at all)"
    If fso.FileExists(logPath) Then
        If fso.GetFile(logPath).Size > 0 Then
            Set ts = fso.OpenTextFile(logPath, 1)
            lines = Split(ts.ReadAll(), vbCrLf)
            ts.Close
            lastLines = ""
            startIdx = UBound(lines) - 9
            If startIdx < 0 Then startIdx = 0
            For i = startIdx To UBound(lines)
                If Trim(lines(i)) <> "" Then lastLines = lastLines & lines(i) & vbCrLf
            Next
            If Trim(lastLines) <> "" Then detail = lastLines
        End If
    End If
    MsgBox "PhoneCam setup failed (exit code " & setupResult & ")." & vbCrLf & vbCrLf & _
           "Last lines of " & logPath & ":" & vbCrLf & vbCrLf & detail & vbCrLf & _
           "Fix the issue above (often a missing internet connection for " & _
           "the first-time dependency download) and run this installer " & _
           "again.", vbCritical, "PhoneCam Setup - Error"
    WScript.Quit 1
End If

' 3.5. Turn on OBS's WebSocket server. It ships disabled, which is why the
' phone's "Sync OBS settings" toggle silently does nothing on a fresh machine -
' obs_sync.py connects to 127.0.0.1:4455 and is refused. Authentication is
' left enabled: obs_sync.py already speaks the v5 handshake and reads the
' password out of OBS's own config file, so nothing has to be typed anywhere.
Dim obsResult, obsNote
obsNote = ""
obsResult = shell.Run("""" & venvPy & """ """ & appDir & "\enable_obs_websocket.py""", 0, True)
If obsResult = 2 Then
    obsNote = vbCrLf & vbCrLf & "Note: OBS was not detected on this PC yet. " & _
              "Install OBS, open it once, then run this installer again so " & _
              "the ""Sync OBS settings"" option can work."
ElseIf obsResult = 3 Then
    ' OBS rewrites its plugin config from memory when it exits, so a change
    ' made while it is open is discarded on close - saying nothing here would
    ' let the setting silently revert.
    obsNote = vbCrLf & vbCrLf & "Important: OBS is running right now. " & _
              "Close OBS and run this installer once more, otherwise OBS " & _
              "will overwrite the WebSocket setting when it exits and " & _
              """Sync OBS settings"" will not work."
ElseIf obsResult = 4 Then
    obsNote = vbCrLf & vbCrLf & "Note: OBS's WebSocket settings could not be " & _
              "updated automatically. You can enable it by hand in OBS: " & _
              "Tools > WebSocket Server Settings > Enable WebSocket server."
End If

' 4. Register Windows startup - points at PhoneCam_Service.vbs, a lightweight
' watcher (not the full PhoneCam services) that waits for OBS to be running
' before starting anything, and stops everything again once OBS closes. So
' this registers a login-time watcher, not "run all the time" - the actual
' services (network ports, adb polling, etc.) only exist while OBS is open.
startupFolder = shell.SpecialFolders("Startup")
shortcutPath = startupFolder & "\PhoneCam.lnk"
Set shortcut = shell.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & appDir & "\PhoneCam_Service.vbs"""
shortcut.WorkingDirectory = appDir
shortcut.WindowStyle = 7
shortcut.IconLocation = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\shell32.dll,175"
shortcut.Save

' 5. Start the watcher now - no need to log out/in first. It stays idle
' (near-zero resource use) until it sees obs64.exe/obs32.exe running,
' wherever OBS itself happens to be installed - it matches by process name,
' not by location, so that doesn't matter either.
shell.Run "wscript.exe """ & appDir & "\PhoneCam_Service.vbs""", 0, False

MsgBox "PhoneCam is set up." & vbCrLf & vbCrLf & _
       "It now starts automatically whenever you open OBS, with no window " & _
       "ever showing up, and stops again when you close OBS. From now on, " & _
       "whenever OBS is running:" & vbCrLf & vbCrLf & _
       "  - USB: just plug the phone in." & vbCrLf & _
       "  - WiFi: just open the app on the same network." & vbCrLf & vbCrLf & _
       "Nothing else to run, ever again." & vbCrLf & vbCrLf & _
       "(OBS with its Virtual Camera must be installed for the phone to " & _
       "show up as a webcam.)" & obsNote, vbInformation, "PhoneCam Setup - Done"
