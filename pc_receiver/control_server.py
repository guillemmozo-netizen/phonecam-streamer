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
import secrets
import subprocess
import sys
import threading
import time
from typing import Optional

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
    from FrameCast_Service.vbs, no console attached at all) — under pythonw,
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

# Where the phone's microphone is played on this PC. Nothing sets this by
# default, which means the system's default output device — i.e. audible
# monitoring. Setting it to "cable" (or any unique part of an output device's
# name, or its index) routes the audio into VB-CABLE instead, which is what
# makes the phone's mic selectable as an input in Zoom/Meet/Teams/OBS.
#
# An environment variable rather than a setting in the app: this is a property
# of the PC's audio hardware, not of the phone, and it has to be readable by
# the service that starts the receiver without a phone being connected at all.
_audio_device = os.environ.get("FRAMECAST_AUDIO_DEVICE", "").strip()
if _audio_device:
    SERVICE_ARGS["receiver"] = SERVICE_ARGS["receiver"] + ["--audio-device", _audio_device]


AUTH_TOKEN_PATH = os.path.join(script_dir, ".control_token")
_auth_token: str = ""

# ---- Wi-Fi pairing ----------------------------------------------------------
#
# The token is what lets a phone stream over Wi-Fi, and until now the only way
# to get it was GET /token over the USB tunnel. That makes "cable-free" false
# for a phone that has never been plugged in: discovery finds the PC, the
# socket opens, and the receiver rejects the Hello as unauthorised.
#
# A pairing window closes that gap without handing the token to the whole LAN.
# The user runs Pair_Phone.bat once, which POSTs to this server over loopback -
# something only a process on this PC can do - and opens a short window during
# which one LAN device may collect the token. It is consumed by the first
# successful pairing, so the window is not a door left ajar for its full
# duration: the race is to exactly one device, not to everyone for two minutes.
#
# Deliberately not a passwordless permanent opening, and deliberately not a
# typed code either: a code needs a PC display and a phone keyboard, and the
# physical act of running the pairing tool already proves what a code would.
PAIRING_WINDOW_SECONDS = 120
_pairing_opens_until: float = 0.0
_pairing_lock = threading.Lock()


def open_pairing_window(seconds: int = PAIRING_WINDOW_SECONDS) -> float:
    """Allow one LAN device to collect the token. Returns the deadline."""
    global _pairing_opens_until
    with _pairing_lock:
        _pairing_opens_until = time.time() + seconds
        return _pairing_opens_until


def pairing_seconds_left(now: Optional[float] = None) -> int:
    now = time.time() if now is None else now
    with _pairing_lock:
        return max(0, int(_pairing_opens_until - now))


def claim_pairing(now: Optional[float] = None) -> bool:
    """Consume the window. True exactly once per opening."""
    global _pairing_opens_until
    now = time.time() if now is None else now
    with _pairing_lock:
        if now >= _pairing_opens_until:
            return False
        _pairing_opens_until = 0.0
        return True


_obs_manager = None


def obs_manager_snapshot() -> dict:
    """Live OBS reachability for the phone's status UI.

    Started lazily so importing this module (as the tests do) never spawns a
    background thread.
    """
    global _obs_manager
    if _obs_manager is None:
        try:
            from pc_receiver.obs_manager import ObsManager
            from pc_receiver.obs_sync import open_connection, read_config

            _obs_manager = ObsManager(connector=open_connection, config_reader=read_config)
            _obs_manager.start()
        except Exception as e:
            return {"state": "offline", "connected": False, "hint": f"unavailable: {e}"}
    return _obs_manager.snapshot()


def load_or_create_token() -> str:
    """A shared secret every control request must carry.

    These endpoints start and stop processes on the machine, and the server has
    to listen on 0.0.0.0 so a Wi-Fi phone can reach it - which also puts it in
    front of every other device on the network. A token file readable only by
    this user account is the smallest thing that turns "anyone on the LAN" into
    "whoever can read this file", without forcing a pairing UI on the user: the
    phone is handed the token over the USB path, which is already implicitly
    trusted (it requires physical access and an authorised adb key).
    """
    global _auth_token
    try:
        if os.path.exists(AUTH_TOKEN_PATH):
            with open(AUTH_TOKEN_PATH, encoding="utf-8") as f:
                token = f.read().strip()
            if token:
                _auth_token = token
                return token
        token = secrets.token_urlsafe(24)
        with open(AUTH_TOKEN_PATH, "w", encoding="utf-8") as f:
            f.write(token)
        _auth_token = token
        return token
    except OSError as e:
        # Never fail closed in a way that bricks the user's setup: without a
        # readable token file the server still runs, but only for loopback.
        print(f"[control] could not persist auth token ({e}); loopback-only mode")
        _auth_token = ""
        return ""


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
        # No Access-Control-Allow-Origin. It used to be "*", which let any web
        # page the user happened to visit POST to these endpoints from their
        # browser and start or stop services on their machine. Nothing here is
        # meant to be called from a browser at all, so the correct header is
        # none.
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self) -> bool:
        """Loopback (the USB tunnel) is trusted; anything else needs the token.

        adb reverse lands on 127.0.0.1 and already required physical access
        plus an authorised adb key, so requiring a token there would break the
        plug-in-and-go path for no security gain. Remote callers - i.e. Wi-Fi,
        i.e. the whole LAN - must present it.
        """
        peer = self.client_address[0] if self.client_address else ""
        if peer in ("127.0.0.1", "::1"):
            return True
        if not _auth_token:
            return False
        supplied = self.headers.get("X-FrameCast-Token", "")
        return secrets.compare_digest(supplied, _auth_token)

    def do_GET(self):
        if not self._authorised():
            self._json(403, {"error": "unauthorised"})
            return
        if self.path == "/obs-status":
            self._json(200, obs_manager_snapshot())
        elif self.path == "/token":
            # Loopback only, and _authorised() already enforced that. This is
            # the pairing step: the phone fetches the token over USB (physical
            # access + an authorised adb key) and stores it, so a later Wi-Fi
            # session can authenticate without the user typing anything.
            peer = self.client_address[0] if self.client_address else ""
            if peer not in ("127.0.0.1", "::1"):
                self._json(403, {"error": "token is only served over USB"})
                return
            self._json(200, {"token": _auth_token})
        elif self.path == "/pair":
            # The Wi-Fi counterpart to /token. Unlike /token this is reachable
            # from the LAN, which is the whole point - so it is gated on a
            # window the user opened from this PC, and consumed on success.
            peer = self.client_address[0] if self.client_address else "?"
            if not _auth_token:
                self._json(503, {"error": "this PC has no token to share"})
                return
            if not claim_pairing():
                self._json(
                    403,
                    {"error": "no pairing window is open",
                     "hint": "run Pair_Phone.bat on the PC, then try again"},
                )
                print(f"[control] pairing refused for {peer}: no window open")
                return
            print(f"[control] paired with {peer}")
            self._json(200, {"token": _auth_token})
        elif self.path == "/pair-status":
            self._json(200, {"seconds_left": pairing_seconds_left()})
        elif self.path == "/status":
            active = get_status()
            self._json(200, {"running": len(active) > 0, "services": active})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorised():
            self._json(403, {"error": "unauthorised"})
            return
        if self.path == "/pair/open":
            # Loopback only: opening the window is the privileged half, and
            # _authorised() exempts loopback precisely because reaching it
            # already means running on this PC. A LAN caller able to open its
            # own pairing window would make the whole gate pointless.
            peer = self.client_address[0] if self.client_address else ""
            if peer not in ("127.0.0.1", "::1"):
                self._json(403, {"error": "pairing can only be opened from this PC"})
                return
            open_pairing_window()
            print(f"[control] pairing window open for {PAIRING_WINDOW_SECONDS}s")
            self._json(200, {"seconds_left": pairing_seconds_left()})
            return
        if self.path == "/start":
            started = start_services()
            self._json(200, {"started": started})
        elif self.path == "/stop":
            stopped = stop_services()
            self._json(200, {"stopped": stopped})
        elif self.path == "/adb-reverse":
            result = setup_adb_reverse()
            self._json(200, result)
        elif self.path == "/obs-sync":
            self._json(200, self._handle_obs_sync())
        else:
            self._json(404, {"error": "not found"})

    def _handle_obs_sync(self) -> dict:
        """Pushes the phone's current settings into OBS without waiting for a
        stream to start.

        obs_sync used to run only on Hello, so changing a setting did nothing
        visible until the next recording - by which point OBS was already
        rejecting SetVideoSettings because an output was active. Doing it from
        Settings, while nothing is running, is when it can actually be applied.
        """
        try:
            length = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(length) or b"{}") if length else {}
        except Exception as e:
            return {"ok": False, "error": f"bad request body: {e}"}

        try:
            from pc_receiver.obs_sync import sync_video_settings
        except Exception as e:
            return {"ok": False, "error": f"obs_sync unavailable: {e}"}

        width = int(payload.get("width", 0))
        height = int(payload.get("height", 0))
        fps = int(payload.get("fps", 0))
        if width <= 0 or height <= 0 or fps <= 0:
            return {"ok": False, "error": "width/height/fps required"}

        # Settings are pushed from the Settings screen, long after OBS was
        # launched, so the scene fit is safe here if the manager agrees.
        allow_scene = False
        try:
            obs_manager_snapshot()
            allow_scene = bool(_obs_manager and _obs_manager.scene_requests_allowed)
        except Exception:
            allow_scene = False

        ok = sync_video_settings(
            width=width,
            height=height,
            fps=fps,
            allow_scene_requests=allow_scene,
            # No settle wait: the manager's verdict already means OBS has been
            # answering for at least a recheck interval, and a user sitting in
            # the Settings screen is waiting for this to take effect.
            source="settings",
            bitrate_bps=int(payload.get("video_bitrate_bps", 0)),
            audio_bitrate_bps=int(payload.get("audio_bitrate_bps", 0)),
            sample_rate=int(payload.get("sample_rate", 0)),
        )
        return {"ok": ok}



def main():
    redirect_own_output_to_log()
    load_or_create_token()
    print(f"[control] auth token at {AUTH_TOKEN_PATH} (loopback exempt)")
    server = http.server.HTTPServer((HOST, PORT), Handler)
    print(f"[control] FrameCast Control Server running on port {PORT}")
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
