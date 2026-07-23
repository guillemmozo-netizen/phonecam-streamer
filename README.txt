PhoneCam - PC Setup
====================

1. Double-click Install_PhoneCam.vbs.
2. That's it. No console window ever appears; you'll just get a
   confirmation popup once it's done.

From then on, PhoneCam starts automatically whenever you open OBS -
completely invisibly, no window ever - and stops again when you close OBS.
While OBS is running:

  - USB:  plug the phone in, that's all.
  - WiFi: open the PhoneCam app on the same network, that's all.

(A tiny watcher registers at login to make this possible, but it does
nothing and uses no network ports until it actually sees OBS running.)

Works no matter where you put this folder, and no matter where OBS is
installed - nothing here is tied to a specific location.

Requirements:
  - Python 3.9+ installed, with "Add python.exe to PATH" checked during
    install (https://www.python.org/downloads/). The installer tells you
    clearly if this is missing - it's the only thing it can't do for you.
  - OBS Studio installed (for OBS Virtual Camera) if you want the phone to
    show up as a webcam in Zoom/Meet/etc.

Troubleshooting: nothing shows a window by design, so if something isn't
working, check the logs in pc_receiver\logs\ (control_server.log,
receiver.log, discovery.log, speed_test.log) and pc_receiver\setup_log.txt
for the install step itself.

Prefer to see what's happening in a visible console, or run it for one
session only without installing anything permanently? Open the
pc_receiver folder and run PhoneCam_PC.bat directly instead of the
installer.

To undo the auto-start: delete the "PhoneCam" shortcut from
  %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup

Everything in the pc_receiver\ folder is plain, readable Python - it's
open source, nothing hidden.
