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

' Singleton guard. Two things launch this watcher - the Startup shortcut at
' login, and the installer's own "launch now" step - so running the installer
' while already logged in left two of them polling in parallel. Each one
' starts its own control_server and tracks its own PID, so the machine ended
' up with two control servers, two discovery servers, two speed test servers
' and two receivers, all fighting over the same ports. Worse, each watcher's
' taskkill /T targets only its own tree, so they could kill each other's
' children in a loop.
'
' A lock file plus a process count, because neither alone is enough.
'
' CreateTextFile(path, False) fails if the file already exists, which is the
' closest thing to an atomic create-exclusive available here. But a lock file
' on its own goes stale the moment this process is killed (taskkill, a reboot,
' the crash dialog), and a stale lock would then keep the watcher from ever
' starting again.
'
' So the count breaks the tie, and it can do so without this script needing to
' know its own PID: if the lock is held and *two or more* watcher processes
' exist, the other one is real and this instance stands down. If the lock is
' held but this is the only watcher running, the lock is stale from a previous
' life and is taken over. Both instances evaluate the same rule and exactly one
' survives - the earlier one, which already owns the lock.
Function WatcherCount()
    Dim procs, p, n
    n = 0
    On Error Resume Next
    Set procs = wmi.ExecQuery("SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name='wscript.exe'")
    For Each p In procs
        If InStr(1, p.CommandLine & "", "PhoneCam_Service.vbs", vbTextCompare) > 0 Then
            n = n + 1
        End If
    Next
    On Error GoTo 0
    WatcherCount = n
End Function

Dim lockPath, lockFile, gotLock
lockPath = appDir & "\.watcher.lock"
gotLock = False
On Error Resume Next
Set lockFile = fso.CreateTextFile(lockPath, False)
If Err.Number = 0 Then
    gotLock = True
    lockFile.WriteLine "PhoneCam watcher"
    lockFile.Close
End If
Err.Clear
On Error GoTo 0

If Not gotLock Then
    If WatcherCount() >= 2 Then
        WScript.Quit 0          ' a live watcher already owns this
    End If
    ' Only us running, so the lock is left over from a previous life.
    On Error Resume Next
    fso.DeleteFile lockPath, True
    Set lockFile = fso.CreateTextFile(lockPath, False)
    If Err.Number = 0 Then lockFile.Close
    On Error GoTo 0
End If

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
