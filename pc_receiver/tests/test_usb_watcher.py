"""Tests for the USB tunnel watcher.

The failure these exist for is invisible from the PC: the receiver is
listening, the phone is streaming, `adb devices` shows the phone as
connected, and nothing reaches the PC — because the `adb reverse` tunnel is
gone and the phone is talking to its own loopback.
"""

import pc_receiver.control_server as control_server


def test_reverse_ports_are_parsed_from_adb_output(monkeypatch):
    class Result:
        stdout = "UsbFfs tcp:8787 tcp:8787\nUsbFfs tcp:8790 tcp:8790\n"

    monkeypatch.setattr(control_server.subprocess, "run", lambda *a, **k: Result())

    assert control_server.list_reverse_ports("adb", "SERIAL") == {8787, 8790}


def test_no_tunnels_parses_as_empty_not_an_error(monkeypatch):
    class Result:
        stdout = "\n"

    monkeypatch.setattr(control_server.subprocess, "run", lambda *a, **k: Result())

    assert control_server.list_reverse_ports("adb", "SERIAL") == set()


def test_adb_failing_is_reported_as_no_tunnels(monkeypatch):
    def explode(*args, **kwargs):
        raise OSError("adb not responding")

    monkeypatch.setattr(control_server.subprocess, "run", explode)

    assert control_server.list_reverse_ports("adb", "SERIAL") == set()


def test_garbage_output_does_not_raise(monkeypatch):
    class Result:
        stdout = "tcp:notanumber\nrubbish\ntcp:8787 tcp:8787\n"

    monkeypatch.setattr(control_server.subprocess, "run", lambda *a, **k: Result())

    assert control_server.list_reverse_ports("adb", "SERIAL") == {8787}


def test_watcher_reforwards_when_tunnels_vanish_under_a_still_connected_phone(monkeypatch):
    """The regression. The phone never disconnects, so the old watcher — which
    only acted on serials it had not already succeeded for — never noticed the
    tunnels had gone and never rebuilt them."""
    calls = []
    cycles = [0]
    # Cycle 0: tunnels up, nothing to do. Cycle 1: the adb server has
    # restarted and every tunnel is gone, while the phone stays connected.
    reverse_state = [set(control_server.ADB_PORTS), set()]

    monkeypatch.setattr(control_server, "find_adb", lambda: "adb")
    monkeypatch.setattr(control_server, "list_authorized_serials", lambda adb: {"PHONE1"})
    monkeypatch.setattr(
        control_server, "list_reverse_ports",
        lambda adb, serial: reverse_state[min(cycles[0], len(reverse_state) - 1)],
    )
    monkeypatch.setattr(control_server, "start_services", lambda: [])

    def fake_setup(serial):
        calls.append(serial)
        return {"ok": True}

    monkeypatch.setattr(control_server, "setup_adb_reverse", fake_setup)

    class Stop(Exception):
        pass

    def fake_sleep(_seconds):
        cycles[0] += 1
        if cycles[0] >= 2:
            raise Stop()

    monkeypatch.setattr(control_server.time, "sleep", fake_sleep)

    try:
        control_server.watch_usb_devices()
    except Stop:
        pass

    assert calls == ["PHONE1"], (
        "watcher did not re-forward after the tunnels vanished under a connected phone"
    )


def test_watcher_does_nothing_while_the_tunnels_are_healthy(monkeypatch):
    calls = []
    monkeypatch.setattr(control_server, "find_adb", lambda: "adb")
    monkeypatch.setattr(control_server, "list_authorized_serials", lambda adb: {"PHONE1"})
    monkeypatch.setattr(control_server, "list_reverse_ports",
                        lambda adb, serial: set(control_server.ADB_PORTS))
    monkeypatch.setattr(control_server, "setup_adb_reverse",
                        lambda serial: calls.append(serial) or {"ok": True})
    monkeypatch.setattr(control_server, "start_services", lambda: [])

    class Stop(Exception):
        pass

    cycles = [0]

    def fake_sleep(_seconds):
        cycles[0] += 1
        if cycles[0] >= 3:
            raise Stop()

    monkeypatch.setattr(control_server.time, "sleep", fake_sleep)

    try:
        control_server.watch_usb_devices()
    except Stop:
        pass

    assert calls == [], "watcher re-forwarded tunnels that were already up"


def test_a_partial_tunnel_set_is_repaired(monkeypatch):
    """One port missing is as broken as all of them — the phone needs 8787 for
    video and 8790 to reach the control server at all."""
    calls = []
    monkeypatch.setattr(control_server, "find_adb", lambda: "adb")
    monkeypatch.setattr(control_server, "list_authorized_serials", lambda adb: {"PHONE1"})
    monkeypatch.setattr(control_server, "list_reverse_ports", lambda adb, serial: {8787, 8788})
    monkeypatch.setattr(control_server, "setup_adb_reverse",
                        lambda serial: calls.append(serial) or {"ok": True})
    monkeypatch.setattr(control_server, "start_services", lambda: [])

    class Stop(Exception):
        pass

    def fake_sleep(_seconds):
        raise Stop()

    monkeypatch.setattr(control_server.time, "sleep", fake_sleep)

    try:
        control_server.watch_usb_devices()
    except Stop:
        pass

    assert calls == ["PHONE1"]
