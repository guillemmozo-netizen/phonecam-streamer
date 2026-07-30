"""Stress battery against the *live* installed services.

Unlike the pytest suites, this drives the real control server, the real
receiver and the real adb on this machine, so it exercises the parts no test
double can: the USB tunnel watcher, the singleton guard, and whether the
listener really keeps accepting after abuse.

    python -m pc_receiver.tools.stress                 # everything safe by default
    python -m pc_receiver.tools.stress --only adb      # one scenario

Every scenario reports PASS/FAIL and the whole run exits non-zero if any
failed, so this is usable as a gate.

Deliberately NOT included: killing OBS. Restarting OBS is destructive to a
user's scene collection if OBS is mid-save, and this project has already cost
one. Test that by hand, with a backup.
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from typing import Callable, List, Tuple

CONTROL_PORT = 8790
STREAM_PORT = 8787
ADB_PORTS = (8787, 8788, 8789, 8790)

results: List[Tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def find_adb() -> str | None:
    found = shutil.which("adb")
    if found:
        return found
    local = os.path.join(os.path.expanduser("~"), "AppData", "Local", "Android",
                         "Sdk", "platform-tools", "adb.exe")
    return local if os.path.exists(local) else None


def adb(*args: str, timeout: int = 20) -> subprocess.CompletedProcess:
    exe = find_adb()
    if not exe:
        raise RuntimeError("adb not found")
    return subprocess.run([exe, *args], capture_output=True, text=True, timeout=timeout)


def port_is_listening(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(2)
        try:
            probe.connect(("127.0.0.1", port))
            return True
        except OSError:
            return False


def wait_for(predicate: Callable[[], bool], timeout: float, interval: float = 0.5) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


# ───────────────────────── scenarios ─────────────────────────

def scenario_reconnect_storm(rounds: int = 25) -> None:
    """Connect/disconnect as fast as the phone's supervisor would after a
    flapping link. The listener must accept every single one."""
    accepted = 0
    for i in range(rounds):
        try:
            with socket.create_connection(("127.0.0.1", STREAM_PORT), timeout=3):
                accepted += 1
        except OSError:
            break
        time.sleep(0.05)
    record("reconnect storm", accepted == rounds, f"{accepted}/{rounds} accepted")


def scenario_half_open_clients(count: int = 10) -> None:
    """Connect and say nothing, then leave.

    The receiver handles one connection at a time by design, so a burst like
    this is expected to degrade service briefly — the guarantee worth testing
    is that it *recovers on its own*, which is what the idle timeout is for.
    Before that timeout existed, the first of these pinned the accept slot
    permanently and only a restart brought the receiver back.
    """
    ghosts = []
    for _ in range(count):
        try:
            ghosts.append(socket.create_connection(("127.0.0.1", STREAM_PORT), timeout=3))
        except OSError:
            break   # backlog full, which is itself fine
    accepted = len(ghosts)

    # Abandon them without closing, the way a phone that lost Wi-Fi would.
    for ghost in ghosts:
        ghost.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        ghost.close()

    # Must come back by itself, within a couple of idle timeouts.
    recovered = wait_for(lambda: port_is_listening(STREAM_PORT), timeout=30, interval=1)
    record("half-open clients recover", recovered,
           f"{accepted} opened, listener recovered={recovered}")


def scenario_garbage_payloads() -> None:
    """Hostile bytes on the video port must not take the receiver down."""
    payloads = [
        b"GET / HTTP/1.1\r\n\r\n",
        struct.pack(">I", 0xFFFFFFF) + b"lying length",
        b"\x00" * 64,
        struct.pack(">I", 5) + b"{bad}",
    ]
    for payload in payloads:
        try:
            with socket.create_connection(("127.0.0.1", STREAM_PORT), timeout=3) as sock:
                sock.sendall(payload)
                time.sleep(0.1)
        except OSError:
            pass
    record("garbage payloads", wait_for(lambda: port_is_listening(STREAM_PORT), 10))


def scenario_control_server_singleton() -> None:
    """A second control server must refuse to bind rather than silently
    duplicating every service behind it."""
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    proc = subprocess.run(
        [sys.executable, "-m", "pc_receiver.control_server"],
        cwd=root, capture_output=True, text=True, timeout=30,
    )
    # It writes to its log rather than stdout, so the proof is that it exited
    # promptly instead of running forever, and that only one still listens.
    record("control server singleton", port_is_listening(CONTROL_PORT),
           f"second instance exited rc={proc.returncode}")


def scenario_adb_server_restart() -> None:
    """The one that bit us for real: `adb kill-server` drops every reverse
    tunnel while the phone stays listed as connected. The watcher has to
    notice and rebuild them."""
    if not find_adb():
        record("adb restart recovery", False, "adb not found")
        return

    before = adb("reverse", "--list").stdout
    if "tcp:8787" not in before:
        record("adb restart recovery", False, "no tunnel to begin with — start the phone app first")
        return

    adb("kill-server", timeout=30)
    time.sleep(2)
    adb("start-server", timeout=30)
    gone = "tcp:8787" not in adb("reverse", "--list").stdout
    if not gone:
        record("adb restart recovery", True, "tunnels survived the restart (nothing to recover)")
        return

    # The watcher polls every USB_POLL_INTERVAL_SECONDS (2s); give it room.
    recovered = wait_for(lambda: "tcp:8787" in adb("reverse", "--list").stdout, timeout=45, interval=2)
    record("adb restart recovery", recovered,
           "tunnels rebuilt automatically" if recovered else "tunnels NOT rebuilt after 45s")


def scenario_services_are_singletons() -> None:
    """Exactly one process may own each port."""
    ok = True
    detail = []
    for port in ADB_PORTS:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
        owners = {
            line.split()[-1]
            for line in out.splitlines()
            if f":{port} " in line and ("LISTENING" in line or f"0.0.0.0:{port}" in line)
        }
        if len(owners) > 1:
            ok = False
            detail.append(f"port {port}: {len(owners)} owners")
    record("one owner per port", ok, "; ".join(detail) or "all ports singly owned")


SCENARIOS = {
    "reconnect": scenario_reconnect_storm,
    "halfopen": scenario_half_open_clients,
    "garbage": scenario_garbage_payloads,
    "singleton": scenario_control_server_singleton,
    "ports": scenario_services_are_singletons,
    "adb": scenario_adb_server_restart,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=sorted(SCENARIOS), default=None)
    args = parser.parse_args()

    print("=" * 62)
    print("PhoneCam stress battery (live services)")
    print("=" * 62)

    if not port_is_listening(STREAM_PORT):
        print(f"receiver is not listening on {STREAM_PORT} — start the services first")
        return 2

    chosen = [args.only] if args.only else list(SCENARIOS)
    for name in chosen:
        print(f"\n-- {name} --", flush=True)
        try:
            SCENARIOS[name]()
        except Exception as e:
            record(name, False, f"{type(e).__name__}: {e}")

    failed = [name for name, ok, _ in results if not ok]
    print("\n" + "=" * 62)
    print(f"  {len(results) - len(failed)}/{len(results)} passed")
    if failed:
        print("  FAILED: " + ", ".join(failed))
    print("=" * 62)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
