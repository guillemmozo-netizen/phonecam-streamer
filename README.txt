FrameCast - PC Setup
====================

1. Double-click "FrameCast Setup".
2. That's it. No console window ever appears; you'll just get a
   confirmation popup once it's done.

(Install_FrameCast.vbs next to it is the installer itself - "FrameCast
Setup" only starts it, and exists so the entry point carries the FrameCast
icon. Running either one does exactly the same thing.)

From then on, FrameCast runs from the moment the PC starts - completely
invisibly, no window ever - always ready to receive the phone instantly:

  - USB:  plug the phone in, that's all.
  - WiFi: open the FrameCast app on the same network, that's all.
    (WiFi needs one USB connection first - that's the pairing step.)

(A tiny keeper registers at login to make this possible; it restarts the
service if it ever dies.)

Works no matter where you put this folder, and no matter where OBS is
installed - nothing here is tied to a specific location.

Requirements:
  - Python 3.10+ installed, with "Add python.exe to PATH" checked during
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
pc_receiver folder and run FrameCast_PC.bat directly instead of the
installer.

To undo the auto-start: delete the "FrameCast" shortcut from
  %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup

Everything in the pc_receiver\ folder is plain, readable Python - it's
open source, nothing hidden.

Full manual (Spanish + English): README.md
Release notes: RELEASE_NOTES.md
