' Always-hidden lifecycle watcher for control_server.py: waits until OBS is
' running before starting PhoneCam, and stops it again once OBS closes, so
' nothing sits bound to network ports or polling `adb devices` unless OBS
' is actually open. Used by the Windows Startup shortcut and by the
' installer's "launch now" step - never shows a console window itself.
Option Explicit

Dim fso, shell, wmi, appDir, projectRoot, pyExe, controlServerPid

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
Set wmi = GetObject("winmgmts:\\.\root\cimv2")

appDir = fso.GetParentFolderName(WScript.ScriptFullName)
projectRoot = fso.GetParentFolderName(appDir)

If fso.FileExists(appDir & "\.venv\Scripts\pythonw.exe") Then
    pyExe = appDir & "\.venv\Scripts\pythonw.exe"
ElseIf fso.FileExists(appDir & "\.venv\Scripts\python.exe") Then
    pyExe = appDir & "\.venv\Scripts\python.exe"
Else
    pyExe = "pythonw.exe"
End If

Function IsObsRunning()
    Dim procs
    Set procs = wmi.ExecQuery("SELECT Name FROM Win32_Process WHERE Name='obs64.exe' OR Name='obs32.exe'")
    IsObsRunning = (procs.Count > 0)
End Function

' Launches control_server.py hidden (as before) and hands back its PID by
' matching it back up via WMI right after - shell.Run itself doesn't expose
' a PID, so this is the simplest way to get one for StopControlServer later.
Function StartControlServer()
    Dim before, after, p, attempt
    Set before = CreateObject("Scripting.Dictionary")
    Dim existing
    Set existing = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE CommandLine LIKE '%pc_receiver.control_server%'")
    For Each p In existing
        before(p.ProcessId) = True
    Next

    ' -m pc_receiver.control_server (not a bare script path): control_server.py
    ' spawns receiver.py etc. the same way, and those do absolute package
    ' imports (`from pc_receiver.protocol import ...`) that only resolve when
    ' the parent of pc_receiver/ is the working directory.
    shell.CurrentDirectory = projectRoot
    shell.Run """" & pyExe & """ -m pc_receiver.control_server", 0, False

    For attempt = 1 To 20
        WScript.Sleep 250
        Set after = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE CommandLine LIKE '%pc_receiver.control_server%'")
        For Each p In after
            If Not before.Exists(p.ProcessId) Then
                StartControlServer = p.ProcessId
                Exit Function
            End If
        Next
    Next
    StartControlServer = 0
End Function

' /T kills the whole process tree (control_server + the discovery/speed_test/
' receiver children it spawned), not just the top-level pid.
Sub StopControlServer(pid)
    If pid <> 0 Then
        shell.Run "taskkill /PID " & pid & " /T /F", 0, True
    End If
End Sub

controlServerPid = 0
Do While True
    If IsObsRunning() And controlServerPid = 0 Then
        controlServerPid = StartControlServer()
    ElseIf (Not IsObsRunning()) And controlServerPid <> 0 Then
        StopControlServer controlServerPid
        controlServerPid = 0
    End If
    WScript.Sleep 3000
Loop
