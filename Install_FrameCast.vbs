' FrameCast PC - one-click, silent installer. Double-click this file (it runs
' via wscript.exe, which never shows a console window). Sets up a private
' Python environment and registers a watcher that starts FrameCast whenever
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
           "Re-download/re-extract FrameCast_PC_Setup.zip and make sure " & _
           "Install_FrameCast.vbs and the pc_receiver folder stay together " & _
           "(don't move one without the other), then run this installer " & _
           "again.", vbCritical, "FrameCast Setup - Error"
    WScript.Quit 1
End If
If Not fso.FileExists(helperBat) Then
    MsgBox "pc_receiver is missing _setup_helper.bat." & vbCrLf & vbCrLf & _
           "Expected: " & helperBat & vbCrLf & vbCrLf & _
           "The pc_receiver folder looks incomplete - re-download/" & _
           "re-extract FrameCast_PC_Setup.zip (don't copy files out of it " & _
           "individually) and run this installer again.", _
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

' 3a-bis. Create FrameCast's own capture source in OBS, so the user never has
' to. The auto-fit only ever reshapes a source named exactly FrameCast (that
' exactness is what stops it rewriting a webcam's transform), which otherwise
' turns into a manual "name it exactly right" step.
'
' Only possible while OBS is running and answering, which at install time it
' usually is not - the WebSocket server may have just been switched on above
' and needs an OBS restart. That is fine and deliberately silent: the receiver
' creates the same source itself the first time it syncs (obs_sync.
' ensure_source), so the only difference is when it appears. Exit code 4 is
' the one case worth mentioning, since it means OBS answered and still said no.
Dim sourceResult
sourceResult = shell.Run("""" & venvPy & """ -m pc_receiver.create_obs_source", 0, True)
If sourceResult = 4 Then
    obsNote = obsNote & vbCrLf & vbCrLf & "Note: the ""FrameCast"" source could " & _
              "not be added to OBS automatically. It will be created the first " & _
              "time you stream with OBS open."
End If

' 3b. Check the one part of the audio path that cannot be shipped with this
' app. Video reaches other programs through pyvirtualcam's system webcam, but
' nothing here can register a system *microphone* - that is a kernel-mode
' driver. So the phone's audio is played into a virtual audio cable the user
' installs once, and their call picks that cable as its microphone.
'
' Checked at install time because getting it wrong is silent in the worst
' way: audio arrives, decodes and plays perfectly - out of the PC's speakers,
' where the call cannot hear it and the user gets an echo instead. Nothing
' fails, so nothing would prompt them to look.
Dim audioResult, audioNote
audioNote = ""
audioResult = shell.Run("""" & venvPy & """ -m pc_receiver.check_audio_setup", 0, True)
If audioResult = 2 Then
    audioNote = vbCrLf & vbCrLf & "About the phone's microphone: video is ready, " & _
                "but sending the phone's audio into a call needs a virtual audio " & _
                "cable, which Windows does not come with. Install VB-CABLE (free) " & _
                "from https://vb-audio.com/Cable/ and it will be picked up " & _
                "automatically - then set your call's microphone to ""CABLE Output""." & _
                vbCrLf & vbCrLf & "Until then everything still works, except the " & _
                "phone's audio comes out of this PC's speakers instead of reaching " & _
                "the call."
ElseIf audioResult = 3 Then
    audioNote = vbCrLf & vbCrLf & "Note: no audio output device was found on this " & _
                "PC, so the phone's microphone will not be usable. Video is " & _
                "unaffected."
End If

' 3.9. Clean up any pre-rename PhoneCam install: its Startup shortcut would
' relaunch the old watcher at every login, and an old watcher already running
' would keep starting its own control_server alongside the new one - two of
' everything, fighting over the same ports. Best-effort: nothing here may
' stop the install.
Dim oldShortcut, wmi, oldProcs, oldProc
On Error Resume Next
oldShortcut = shell.SpecialFolders("Startup") & "\PhoneCam.lnk"
If fso.FileExists(oldShortcut) Then fso.DeleteFile oldShortcut, True
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set oldProcs = wmi.ExecQuery("SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name='wscript.exe'")
For Each oldProc In oldProcs
    If InStr(1, oldProc.CommandLine & "", "PhoneCam_Service.vbs", vbTextCompare) > 0 Then
        shell.Run "taskkill /PID " & oldProc.ProcessId & " /T /F", 0, True
    End If
Next
On Error GoTo 0

' 4. Register Windows startup - points at FrameCast_Service.vbs, which starts
' control_server the moment the user logs in and restarts it if it ever dies.
' Always-on by design: the phone must be able to connect from the first
' second, without OBS or anything else having been opened first.
startupFolder = shell.SpecialFolders("Startup")
shortcutPath = startupFolder & "\FrameCast.lnk"
Set shortcut = shell.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & appDir & "\FrameCast_Service.vbs"""
shortcut.WorkingDirectory = appDir
shortcut.WindowStyle = 7
' The official FrameCast icon when the branding folder shipped intact; a
' neutral system icon as fallback, never a wrong one.
If fso.FileExists(rootDir & "\branding\framecast.ico") Then
    shortcut.IconLocation = rootDir & "\branding\framecast.ico,0"
Else
    shortcut.IconLocation = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\shell32.dll,175"
End If
shortcut.Save

' 5. Start the service now - no need to log out/in first. From this moment
' the PC is listening and ready for the phone.
shell.Run "wscript.exe """ & appDir & "\FrameCast_Service.vbs""", 0, False

MsgBox "FrameCast is set up." & vbCrLf & vbCrLf & _
       "It now runs from the moment this PC starts - completely invisibly, " & _
       "no window ever - so it is always ready to receive the phone " & _
       "instantly. From now on:" & vbCrLf & vbCrLf & _
       "  - USB: just plug the phone in." & vbCrLf & _
       "  - WiFi: just open the app on the same network." & vbCrLf & vbCrLf & _
       "Nothing else to run, ever again." & vbCrLf & vbCrLf & _
       "(OBS with its Virtual Camera must be installed for the phone to " & _
       "show up as a webcam.)" & obsNote & audioNote, vbInformation, "FrameCast Setup - Done"
