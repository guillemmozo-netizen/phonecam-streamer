' FrameCast PC - one-click, silent installer. Double-click this file (it runs
' via wscript.exe, which never shows a console window). Sets up a private
' Python environment and registers a watcher that starts FrameCast whenever
' OBS is running - nothing else to run, ever again. Works from any folder:
' every path below is derived from this script's own location, never
' hardcoded, so it doesn't matter where the zip was extracted to.
Option Explicit

Dim fso, shell, rootDir, appDir, venvPy, helperBat, logPath, iconPath
Dim pyCheck, setupResult, startupFolder, shortcutPath, shortcut
Dim requiredFiles, missingFiles, requiredFile

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

rootDir = fso.GetParentFolderName(WScript.ScriptFullName)
appDir = rootDir & "\pc_receiver"
venvPy = appDir & "\.venv\Scripts\python.exe"
helperBat = appDir & "\_setup_helper.bat"
logPath = appDir & "\setup_log.txt"
iconPath = rootDir & "\FrameCast.ico"

' Every file the PC side needs to actually run, relative to this script. Kept
' in sync with what tools/build_pc_zip.py puts in the zip by a test
' (tools/tests/test_pc_zip.py) that parses this very array — so a module added
' to the package can't quietly stop being checked for here.
requiredFiles = Array( _
    "pc_receiver\__init__.py", _
    "pc_receiver\_setup_helper.bat", _
    "pc_receiver\requirements.txt", _
    "pc_receiver\FrameCast_Service.vbs", _
    "pc_receiver\FrameCast_PC.bat", _
    "pc_receiver\control_server.py", _
    "pc_receiver\receiver.py", _
    "pc_receiver\discovery_server.py", _
    "pc_receiver\speed_test_server.py", _
    "pc_receiver\start_services.py", _
    "pc_receiver\protocol.py", _
    "pc_receiver\sinks.py", _
    "pc_receiver\h264_decoder.py", _
    "pc_receiver\audio_sink.py", _
    "pc_receiver\obs_manager.py", _
    "pc_receiver\obs_sync.py", _
    "pc_receiver\enable_obs_websocket.py", _
    "pc_receiver\demo_sender.py", _
    "pc_receiver\install_startup.bat", _
    "reward_engine\__init__.py", _
    "reward_engine\reward_manager.py")

' 0. The pc_receiver folder must sit right next to this script, with all its
' files intact - if antivirus, an incomplete copy, or a half-finished
' extraction stripped something out, fail clearly here instead of hitting a
' confusing "file not found" a few lines down that looks like a network or
' pip problem.
If Not fso.FolderExists(appDir) Then
    MsgBox "Could not find the pc_receiver folder next to this installer." & vbCrLf & vbCrLf & _
           "Expected: " & appDir & vbCrLf & vbCrLf & _
           "Re-download/re-extract FrameCast_PC_Setup.zip and make sure " & _
           "Install_FrameCast.vbs and the pc_receiver folder stay together " & _
           "(don't move one without the other), then run this installer " & _
           "again.", vbCritical, "FrameCast Setup - Error"
    WScript.Quit 1
End If
missingFiles = ""
For Each requiredFile In requiredFiles
    If Not fso.FileExists(rootDir & "\" & requiredFile) Then
        missingFiles = missingFiles & "  " & requiredFile & vbCrLf
    End If
Next
If missingFiles <> "" Then
    ' Checking the whole set up front, rather than only _setup_helper.bat,
    ' because a partial extraction is the common failure and it used to get
    ' through this gate: setup then "succeeded" and the missing module only
    ' surfaced later as a silent service that never started, with nothing in
    ' the popup to connect it back to the extraction.
    MsgBox "This FrameCast folder is incomplete - these files are missing:" & vbCrLf & vbCrLf & _
           missingFiles & vbCrLf & _
           "That normally means the zip was only partly extracted, or " & _
           "antivirus removed something. Re-download FrameCast_PC_Setup.zip " & _
           "and extract the whole thing to one folder (don't copy files out " & _
           "of it individually), then run this installer again.", _
           vbCritical, "FrameCast Setup - Error"
    WScript.Quit 1
End If

' 1. Python must already be installed - nothing here can safely install it
' silently on the user's behalf.
pyCheck = shell.Run("cmd /c where python >nul 2>nul", 0, True)
If pyCheck <> 0 Then
    MsgBox "Python was not found on this PC." & vbCrLf & vbCrLf & _
           "Install it from https://www.python.org/downloads/" & vbCrLf & _
           "(tick ""Add python.exe to PATH"" during setup), then run " & _
           "this installer again.", vbExclamation, "FrameCast Setup"
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
    MsgBox "FrameCast setup failed (exit code " & setupResult & ")." & vbCrLf & vbCrLf & _
           "Last lines of " & logPath & ":" & vbCrLf & vbCrLf & detail & vbCrLf & _
           "Fix the issue above (often a missing internet connection for " & _
           "the first-time dependency download) and run this installer " & _
           "again.", vbCritical, "FrameCast Setup - Error"
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

' 4. Register Windows startup - points at FrameCast_Service.vbs, a lightweight
' watcher (not the full FrameCast services) that waits for OBS to be running
' before starting anything, and stops everything again once OBS closes. So
' this registers a login-time watcher, not "run all the time" - the actual
' services (network ports, adb polling, etc.) only exist while OBS is open.
startupFolder = shell.SpecialFolders("Startup")
shortcutPath = startupFolder & "\FrameCast.lnk"
Set shortcut = shell.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & appDir & "\FrameCast_Service.vbs"""
shortcut.WorkingDirectory = appDir
shortcut.WindowStyle = 7
' FrameCast.ico ships next to this installer. Fall back to a generic shell32
' icon if it isn't there rather than leaving the shortcut with a broken-icon
' placeholder - the shortcut still works either way.
If fso.FileExists(iconPath) Then
    shortcut.IconLocation = iconPath & ",0"
Else
    shortcut.IconLocation = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\shell32.dll,175"
End If
shortcut.Save

' 5. Start the watcher now - no need to log out/in first. It stays idle
' (near-zero resource use) until it sees obs64.exe/obs32.exe running,
' wherever OBS itself happens to be installed - it matches by process name,
' not by location, so that doesn't matter either.
shell.Run "wscript.exe """ & appDir & "\FrameCast_Service.vbs""", 0, False

MsgBox "FrameCast is set up." & vbCrLf & vbCrLf & _
       "It now starts automatically whenever you open OBS, with no window " & _
       "ever showing up, and stops again when you close OBS. From now on, " & _
       "whenever OBS is running:" & vbCrLf & vbCrLf & _
       "  - USB: just plug the phone in." & vbCrLf & _
       "  - WiFi: just open the app on the same network." & vbCrLf & vbCrLf & _
       "Nothing else to run, ever again." & vbCrLf & vbCrLf & _
       "(OBS with its Virtual Camera must be installed for the phone to " & _
       "show up as a webcam.)" & obsNote, vbInformation, "FrameCast Setup - Done"
