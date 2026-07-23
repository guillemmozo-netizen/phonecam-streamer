"""
Control Server — lightweight HTTP API that lets the phone manage PC services.

Runs on port 8790. The phone app can:
  GET  /status          → {"running": true/false, "services": [...]}
  POST /start           → starts discovery + speed_test + receiver
  POST /stop            → stops all services
  POST /adb-reverse     → sets up adb reverse port forwarding

This is the only thing that needs to be running on the PC — and with
install_startup.bat it launches automatically at login, so in practice
nothing ever needs to be run manually again:
  - Wi-Fi: discovery/speed_test/receiver are started eagerly at boot (see
    main()), so the phone can find and stream to this PC the moment it's on
    the same network — no /start call required for the common case.
  - USB: a background thread (see watch_usb_devices) polls `adb devices`
    and sets up the reverse tunnel itself the moment a phone is plugged in,
    without the phone ever needing to ask.
"""

import http.server
import json
import os
import shutil
import subprocess
import sys
import threading
import time

# This process normally runs headless under pythonw.exe (no console
# attached at all). Any subprocess call targeting a console-subsystem
# executable (adb.exe, or python.exe as opposed to pythonw.exe) then has
# nothing to attach to, so Windows pops a brand new, briefly-visible console
# window for it — every single time. watch_usb_devices() calls `adb devices`
# every couple seconds forever, so without suppressing this that window kept
# flashing in a loop indefinitely, not just once.
#
# CREATE_NO_WINDOW alone (the usual textbook fix) turned out NOT to be
# enough for this platform-tools adb.exe on this machine — confirmed by
# direct testing, still flashed a console on every single call. Adding
# DETACHED_PROCESS (fully detaches the child from any console, rather than
# just asking Windows not to allocate a new one) plus explicit stdin
# redirection is what actually suppressed it in testing (0 flashes across
# repeated calls, vs. CREATE_NO_WINDOW alone still flashing one per call).
NO_CONSOLE_FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)

HOST = "0.0.0.0"
PORT = 8790

SERVICES = [
    ("discovery", "pc_receiver.discovery_server"),
    ("speed_test", "pc_receiver.speed_test_server"),
    ("receiver", "pc_receiver.receiver"),
]

# Includes this server's own port (8790): over USB the phone can only ever
# reach the control server via `adb reverse`, so if 8790 isn't forwarded too
# the phone has no way to ask for anything — the PC side has to push this
# proactively (see watch_usb_devices) rather than wait to be asked.
ADB_PORTS = [8787, 8788, 8789, 8790]
USB_POLL_INTERVAL_SECONDS = 2

script_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(script_dir)
logs_dir = os.path.join(script_dir, "logs")
running_procs: dict[str, subprocess.Popen] = {}
lock = threading.Lock()


def redirect_own_output_to_log():
    """Point this process's own stdout/stderr at a log file.

    Needed because this now normally runs hidden via pythonw.exe (launched
    from PhoneCam_Service.vbs, no console attached at all) — under pythonw,
    sys.stdout/sys.stderr are None, so the plain print() calls throughout
    this file would raise AttributeError on the very first line logged.
    Redirecting first makes every print() below work the same way whether
    this is running in a visible console or completely hidden.
    """
    os.makedirs(logs_dir, exist_ok=True)
    log_file = open(os.path.join(logs_dir, "control_server.log"), "a", buffering=1, encoding="utf-8")
    sys.stdout = log_file
    sys.stderr = log_file


def service_log_file(name: str):
    os.makedirs(logs_dir, exist_ok=True)
    return open(os.path.join(logs_dir, f"{name}.log"), "a", buffering=1, encoding="utf-8")


def find_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    local = os.path.join(
        os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk",
        "platform-tools", "adb.exe"
    )
    return local if os.path.exists(local) else None


SERVICE_ARGS = {
    # 0.0.0.0 so both a real Wi-Fi client AND a USB client tunneled in via
    # `adb reverse` (which lands on loopback) can reach it on the same
    # socket — the previous default (127.0.0.1) silently refused every
    # Wi-Fi connection. --serve-forever so it survives across sessions
    # instead of exiting after the first phone disconnects, since this is
    # now started eagerly at boot rather than per-session.
    "receiver": ["--host", "0.0.0.0", "--sink", "virtualcam", "--serve-forever"],
}


service_log_files: dict[str, "object"] = {}


def start_services():
    with lock:
        started = []
        for name, module in SERVICES:
            if name in running_procs and running_procs[name].poll() is None:
                started.append(name)
                continue
            # receiver.py does `from pc_receiver.protocol import ...` etc — an
            # absolute package import that only resolves when this runs as
            # `python -m pc_receiver.receiver` with the project root (the
            # parent of pc_receiver/) as the cwd. Launching it as a bare
            # script (`python receiver.py`, cwd=pc_receiver/) used to fail
            # with ModuleNotFoundError on the very first line — invisible
            # before there was any log file, so start_services() kept
            # reporting "started" for a process that had already died,
            # produced the "started receiver (pid ...)" churn on every
            # /start or /status poll.
            source_file = os.path.join(script_dir, module.rsplit(".", 1)[-1] + ".py")
            if not os.path.exists(source_file):
                continue
            # Real files, not DEVNULL: this process itself may be running
            # hidden under pythonw.exe (no console), and sys.executable is
            # then pythonw.exe too — a child that inherits None stdout would
            # crash on its first print()/log line. A real file avoids that
            # regardless of how control_server itself was launched, and
            # doubles as the only way to see what a hidden service is doing.
            old_log = service_log_files.get(name)
            if old_log:
                try:
                    old_log.close()
                except Exception:
                    pass
            log_file = service_log_file(name)
            service_log_files[name] = log_file
            proc = subprocess.Popen(
                [sys.executable, "-m", module, *SERVICE_ARGS.get(name, [])],
                cwd=project_root,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                creationflags=NO_CONSOLE_FLAGS,
            )
            running_procs[name] = proc
            started.append(name)
            print(f"[control] started {name} (pid {proc.pid})")
        return started


def stop_services():
    with lock:
        stopped = []
        for name, proc in list(running_procs.items()):
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                    print(f"[control] stopped {name}")
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
                    print(f"[control] {name} didn't exit in time, killed")
            stopped.append(name)
        running_procs.clear()
        for log_file in service_log_files.values():
            try:
                log_file.close()
            except Exception:
                pass
        service_log_files.clear()
        return stopped


def get_status():
    with lock:
        active = []
        for name, proc in list(running_procs.items()):
            if proc.poll() is None:
                active.append(name)
            else:
                del running_procs[name]
        return active


def _adb_reverse_for(adb: str, serial):
    cmd_prefix = [adb] if serial is None else [adb, "-s", serial]
    results = []
    for port in ADB_PORTS:
        try:
            r = subprocess.run(
                cmd_prefix + ["reverse", f"tcp:{port}", f"tcp:{port}"],
                capture_output=True, text=True, timeout=5,
                stdin=subprocess.DEVNULL, creationflags=NO_CONSOLE_FLAGS,
            )
            entry = {"port": port, "ok": r.returncode == 0}
            if r.returncode != 0 and r.stderr.strip():
                entry["error"] = r.stderr.strip()
            results.append(entry)
        except Exception as e:
            results.append({"port": port, "ok": False, "error": str(e)})
    return {"ok": all(r["ok"] for r in results), "ports": results}


def setup_adb_reverse(serial: str = None):
    """Sets up the adb reverse tunnels for one device (serial), or — when no
    serial is given, e.g. the manual "Setup USB" button in the app, which
    doesn't know one — for every currently authorized device.

    Plain `adb reverse` (no -s) fails outright with "more than one device/
    emulator" the instant more than one is attached — and that reliably
    happens for a single phone that has USB connected AND wireless
    debugging paired at the same time, since `adb devices` then lists the
    same phone twice under two different serials (confirmed in the wild:
    RFCWA1SN4NB and adb-RFCWA1SN4NB-...-tls-connect._tcp both "device").
    So a specific -s per device is required here, not just a nice-to-have.
    """
    adb = find_adb()
    if not adb:
        return {"ok": False, "error": "adb not found"}

    if serial is not None:
        return _adb_reverse_for(adb, serial)

    serials = list_authorized_serials(adb)
    if not serials:
        return {"ok": False, "error": "no authorized device found"}
    per_device = {s: _adb_reverse_for(adb, s) for s in serials}
    return {"ok": any(r["ok"] for r in per_device.values()), "devices": per_device}


def list_authorized_serials(adb: str) -> set[str]:
    """Serials from `adb devices` in "device" state — excludes "unauthorized"
    (debugging not yet approved on the phone) and "offline"."""
    try:
        result = subprocess.run(
            [adb, "devices"], capture_output=True, text=True, timeout=5,
            stdin=subprocess.DEVNULL, creationflags=NO_CONSOLE_FLAGS,
        )
    except Exception:
        return set()
    serials = set()
    for line in result.stdout.strip().split("\n")[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            serials.add(parts[0])
    return serials


def watch_usb_devices():
    """Runs forever in a background thread: the moment a phone is plugged in
    (and USB debugging already authorized), push the adb reverse tunnels and
    make sure services are running — no action needed from the phone or a
    human at the PC. Re-forwards on every newly-seen serial rather than once
    at startup, since `adb reverse` is only in effect while a given USB
    session is connected and doesn't survive an unplug/replug.

    Only a serial `setup_adb_reverse()` actually succeeded for is remembered
    as done. A device often shows up in `adb devices` as authorized a beat
    before its on-device adbd is actually ready to accept `reverse` commands
    — that raced here before, and because a failed attempt was still marked
    "handled", the tunnel would then just never come up for the rest of that
    USB session (confirmed in the wild: `adb reverse` failed for all 4 ports
    on first contact, then never got retried, so the phone could never reach
    this PC at all even though everything else was running fine). Now a
    still-connected-but-not-yet-forwarded serial is retried every poll cycle
    until it succeeds.
    """
    ready_serials: set[str] = set()
    while True:
        adb = find_adb()
        if adb:
            current = list_authorized_serials(adb)
            pending = current - ready_serials
            for serial in pending:
                print(f"[control] USB device connected: {serial} — setting up reverse tunnels")
                result = setup_adb_reverse(serial)
                if result["ok"]:
                    print(f"[control] adb reverse ready for {serial}")
                    ready_serials.add(serial)
                    start_services()
                else:
                    print(f"[control] adb reverse failed for {serial}, will retry: {result}")
            ready_serials &= current  # drop anything that disconnected, so a reconnect retries fresh
        time.sleep(USB_POLL_INTERVAL_SECONDS)


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[control] {args[0]}")

    def _json(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/status":
            active = get_status()
            self._json(200, {"running": len(active) > 0, "services": active})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/start":
            started = start_services()
            self._json(200, {"started": started})
        elif self.path == "/stop":
            stopped = stop_services()
            self._json(200, {"stopped": stopped})
        elif self.path == "/adb-reverse":
            result = setup_adb_reverse()
            self._json(200, result)
        else:
            self._json(404, {"error": "not found"})

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()


def main():
    redirect_own_output_to_log()
    server = http.server.HTTPServer((HOST, PORT), Handler)
    print(f"[control] PhoneCam Control Server running on port {PORT}")
    print(f"[control] Endpoints: GET /status, POST /start, POST /stop, POST /adb-reverse")

    # Eager start: discovery/speed_test/receiver come up immediately instead
    # of waiting for the phone to POST /start, so a Wi-Fi phone can find and
    # stream to this PC the instant it's on the same network with zero taps.
    started = start_services()
    print(f"[control] auto-started: {', '.join(started) if started else '(none)'}")

    watcher = threading.Thread(target=watch_usb_devices, daemon=True)
    watcher.start()
    print(f"[control] watching for USB devices every {USB_POLL_INTERVAL_SECONDS}s")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[control] shutting down...")
        stop_services()
        server.server_close()


if __name__ == "__main__":
    main()
