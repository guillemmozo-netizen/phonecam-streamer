"""Turns on OBS's WebSocket server so "Sync OBS settings" can work.

obs-websocket ships disabled by default, which is why the phone's OBS-sync
toggle silently does nothing on a fresh machine: obs_sync.py connects to
127.0.0.1:4455 and gets refused. This flips `server_enabled` on while leaving
authentication alone — obs_sync.py already implements the v5 handshake and
reads the password out of this very file, so enabling auth costs nothing and
turning it off would expose a remote-control socket for no benefit.

Run by Install_PhoneCam.vbs. Safe to run repeatedly.

Exit codes (the installer turns them into a message):
    0  enabled, or already enabled
    2  OBS config not found (OBS not installed, or never launched)
    3  enabled, but OBS is running and will overwrite it on exit
    4  could not read/write the config
"""

from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import tempfile

CONFIG_RELATIVE = r"obs-studio\plugin_config\obs-websocket\config.json"


def config_path() -> str:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return ""
    return os.path.join(appdata, CONFIG_RELATIVE)


def obs_is_running() -> bool:
    """tasklist rather than psutil: this runs from the installer, and psutil is
    not guaranteed to be installed yet at that point.

    One filter per call, on purpose: tasklist combines multiple /FI IMAGENAME
    filters with AND, so asking for obs64 *and* obs32 in a single call matches
    nothing at all and always reports OBS as closed — which silently skips the
    "restart OBS" warning and lets the setting revert on exit.
    """
    for image in ("obs64.exe", "obs32.exe"):
        try:
            out = subprocess.run(
                ["tasklist", "/FI", f"IMAGENAME eq {image}"],
                capture_output=True, text=True, timeout=10,
            ).stdout.lower()
        except Exception:
            continue
        if image in out:
            return True
    return False


def main() -> int:
    path = config_path()
    if not path or not os.path.isfile(path):
        print(f"OBS WebSocket config not found at {path or '%APPDATA%'}")
        return 2

    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as e:
        print(f"could not read {path}: {e}")
        return 4

    already = bool(cfg.get("server_enabled"))
    cfg["server_enabled"] = True

    # Keep authentication on. OBS normally generates the password itself; if
    # it is somehow missing, generate one here rather than disabling auth —
    # obs_sync.py reads the password from this same file, so the two stay in
    # sync automatically and nothing needs to be typed anywhere.
    if cfg.get("auth_required", True) and not cfg.get("server_password"):
        cfg["server_password"] = secrets.token_urlsafe(16)
        print("generated a WebSocket password (auth left enabled)")

    running = obs_is_running()

    if already:
        print("OBS WebSocket server was already enabled")
        # Still worth warning: if OBS was started before the setting was
        # written, its in-memory copy still says disabled and it will write
        # that back over this file on exit.
        return 3 if running else 0

    # Atomic replace: a half-written config.json would leave OBS unable to
    # parse its own plugin settings.
    try:
        directory = os.path.dirname(path)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=directory,
                                         delete=False, suffix=".tmp") as tmp:
            json.dump(cfg, tmp, indent=2)
            temp_name = tmp.name
        os.replace(temp_name, path)
    except Exception as e:
        print(f"could not write {path}: {e}")
        return 4

    print(f"enabled OBS WebSocket server on port {cfg.get('server_port', 4455)}")

    # OBS rewrites this file from memory when it exits, so a change made while
    # it is running is discarded on close — the user has to be told, or the
    # setting silently reverts and the sync "mysteriously" stops working.
    if running:
        print("OBS is running: it must be restarted (or closed before installing)")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
