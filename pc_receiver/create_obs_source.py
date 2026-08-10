"""Creates FrameCast's own capture source in OBS, so the user never has to.

The auto-fit in obs_sync is deliberately name-exact — it only ever reshapes a
source called FrameCast, which is what guarantees it can never rewrite the
transform of somebody's webcam or capture card. The cost of that rule is a
setup step ("name it exactly FrameCast"), and setup steps get done wrong.
This removes the step instead of relaxing the rule.

Run by Install_FrameCast.vbs. Safe to run repeatedly: it never creates a
second copy, and it never touches any source but its own.

This can only work while OBS is actually running with its WebSocket server
on, which at install time is often not the case (the installer may have just
enabled the server, which needs an OBS restart). That is not a failure worth
stopping for: the receiver creates the source itself the first time it syncs
with OBS — see obs_sync.ensure_source — so the outcome is the same, just
later.

Exit codes (the installer turns them into a message):
    0  the source exists now (created, or already there)
    2  OBS's WebSocket server is off, or OBS was never installed
    3  OBS is not running / not answering — it will be created on first use
    4  connected to OBS, but the source could not be created
"""

from __future__ import annotations

import logging
import sys

from pc_receiver.obs_sync import (
    _current_scene,
    _find_scene_item,
    ensure_source,
    open_connection,
    read_config,
    source_names,
)


def main() -> int:
    # The module logs its reasoning at INFO; without a handler this prints
    # nothing at all and the installer log would just say "exit 4".
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    cfg = read_config()
    if not cfg:
        print("OBS not found (never installed or never launched)")
        return 2
    if not cfg.get("server_enabled"):
        print("OBS WebSocket server is off — nothing to talk to yet")
        return 2

    try:
        ws = open_connection(int(cfg.get("server_port", 4455)),
                             str(cfg.get("server_password", "")))
    except Exception as e:
        print(f"OBS is not answering ({e}); the source will be created on first use")
        return 3

    try:
        wanted = source_names()[0]
        scene = _current_scene(ws)
        if not scene:
            print("OBS did not report a current scene")
            return 4
        if _find_scene_item(ws, scene, source_names()) is not None:
            print(f"'{wanted}' is already in scene '{scene}'")
            return 0
        if ensure_source(ws, scene) is None:
            print(f"could not create '{wanted}' in scene '{scene}'")
            return 4
        print(f"created the '{wanted}' source in scene '{scene}'")
        return 0
    finally:
        try:
            ws.close()
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
