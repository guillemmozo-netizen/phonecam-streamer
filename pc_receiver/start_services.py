"""
Launches all PC-side services for FrameCast:
  - Discovery server  (UDP 8789)  — lets the phone find this PC on WiFi
  - Speed test server  (TCP 8788)  — handles upload/download speed tests
  - Main receiver      (TCP 8787)  — receives the camera stream

Also sets up adb reverse port forwarding so USB connections work.

Run:  python start_services.py
"""

import subprocess
import sys
import os
import shutil
import signal

# No-op unless this ever runs under a console-less parent (e.g. pythonw) —
# see the same flag in control_server.py for why it matters there (and why
# CREATE_NO_WINDOW alone wasn't enough for adb.exe specifically).
NO_CONSOLE_FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)

SCRIPTS = [
    ("Discovery", "pc_receiver.discovery_server"),
    ("Speed Test", "pc_receiver.speed_test_server"),
    ("Receiver", "pc_receiver.receiver"),
]

# receiver.py defaults to --host 127.0.0.1, which only accepts the USB
# (adb reverse) path — a Wi-Fi phone connecting to the PC's real LAN IP
# would be refused. 0.0.0.0 accepts both on the same socket. --serve-forever
# so it keeps accepting reconnects instead of exiting after the first one.
SCRIPT_ARGS = {
    "pc_receiver.receiver": ["--host", "0.0.0.0", "--sink", "virtualcam", "--serve-forever"],
}

# 8790 isn't opened here since this launcher has no control server listening
# on it — only control_server.py's ADB_PORTS needs that entry.
ADB_REVERSE_PORTS = [8787, 8788, 8789]


def setup_adb_reverse():
    adb = shutil.which("adb")
    if not adb:
        local_sdk = os.path.join(
            os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk",
            "platform-tools", "adb.exe"
        )
        if os.path.exists(local_sdk):
            adb = local_sdk

    if not adb:
        print("[launcher] adb not found — USB mode won't work (WiFi still OK)")
        return

    try:
        result = subprocess.run(
            [adb, "devices"], capture_output=True, text=True, timeout=5,
            stdin=subprocess.DEVNULL, creationflags=NO_CONSOLE_FLAGS,
        )
        lines = [l for l in result.stdout.strip().split("\n")[1:] if l.strip() and "device" in l]
        if not lines:
            print("[launcher] no USB device connected — skipping adb reverse (WiFi still OK)")
            return
    except Exception:
        print("[launcher] adb check failed — skipping USB setup")
        return

    for port in ADB_REVERSE_PORTS:
        try:
            subprocess.run(
                [adb, "reverse", f"tcp:{port}", f"tcp:{port}"],
                capture_output=True, text=True, timeout=5,
                stdin=subprocess.DEVNULL, creationflags=NO_CONSOLE_FLAGS,
            )
            print(f"[launcher] adb reverse tcp:{port} → tcp:{port} ✓")
        except Exception as e:
            print(f"[launcher] adb reverse tcp:{port} failed: {e}")


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    processes = []

    setup_adb_reverse()
    print()

    for name, module in SCRIPTS:
        # -m pc_receiver.<module>, not a bare script path: receiver.py does
        # `from pc_receiver.protocol import ...`, an absolute package import
        # that only resolves when run this way with project_root (the parent
        # of pc_receiver/) as cwd — running it as a plain script here used to
        # fail with ModuleNotFoundError on the first line.
        source_file = os.path.join(script_dir, module.rsplit(".", 1)[-1] + ".py")
        if not os.path.exists(source_file):
            print(f"[launcher] skipping {name}: {source_file} not found")
            continue
        print(f"[launcher] starting {name} ({module})")
        proc = subprocess.Popen(
            [sys.executable, "-m", module, *SCRIPT_ARGS.get(module, [])],
            cwd=project_root,
            creationflags=NO_CONSOLE_FLAGS,
        )
        processes.append((name, proc))

    print(f"\n[launcher] all services running — press Ctrl+C to stop\n")

    try:
        for name, proc in processes:
            proc.wait()
    except KeyboardInterrupt:
        print("\n[launcher] shutting down...")
        for name, proc in processes:
            proc.terminate()
        for name, proc in processes:
            proc.wait()
        print("[launcher] done")


if __name__ == "__main__":
    main()
