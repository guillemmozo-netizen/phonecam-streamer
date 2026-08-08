FrameCast - PC Setup
====================

1. Extract this whole zip to one folder, keeping everything together.
2. Double-click Install_FrameCast.vbs.
3. That's it. No console window ever appears; you'll just get a
   confirmation popup once it's done.

(Step 1 matters: the installer checks every file it needs before doing
anything, and stops with a list if the extraction was partial. Opening the
zip and running the installer from inside it is the usual way that happens.)

From then on, FrameCast starts automatically whenever you open OBS -
completely invisibly, no window ever - and stops again when you close OBS.
While OBS is running:

  - USB:  plug the phone in, that's all.
  - WiFi: open the FrameCast app on the same network, that's all.

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
pc_receiver folder and run FrameCast_PC.bat directly instead of the
installer.

To undo the auto-start: delete the "FrameCast" shortcut from
  %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup

Everything in the pc_receiver\ folder is plain, readable Python - it's
open source, nothing hidden. reward_engine\ is the same code the phone uses
to work out how much rewarded time an ad is worth; the PC side only needs it
for demo_sender.py, which streams a test pattern so you can check the virtual
camera works without a phone in hand.

FrameCast.ico and FrameCast.png are the app's icon - the installer uses the
.ico for the Startup shortcut.
