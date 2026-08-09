"""The Wi-Fi path, exercised over a real non-loopback socket.

Loopback is exempt from authentication because it is the USB tunnel, so a test
that connects to 127.0.0.1 proves nothing about Wi-Fi. Everything here goes
through the host's own routable address instead, which is what makes the
receiver see a LAN peer and apply the rules a phone on Wi-Fi actually meets.
"""

from __future__ import annotations

import socket
import threading
import time

import numpy as np
import pytest

import pc_receiver.control_server as control_server
import pc_receiver.receiver as receiver_module
from pc_receiver.protocol import (
    Hello,
    MessageType,
    ProtocolError,
    send_hello,
    send_typed_message,
)
from pc_receiver.receiver import handle_connection
from pc_receiver.sinks import NullSink


def _routable_address() -> str:
    """This host's own non-loopback address, or skip.

    Connecting to it from this same machine still produces a peer address that
    is not 127.0.0.1, which is the only property these tests need.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        address = probe.getsockname()[0]
    except OSError:
        pytest.skip("no routable local address to stand in for a LAN peer")
    finally:
        probe.close()
    if address.startswith("127."):
        pytest.skip("only loopback available; cannot exercise the LAN path")
    return address


@pytest.fixture
def lan_address() -> str:
    return _routable_address()


def _jpeg() -> bytes:
    import cv2

    frame = np.zeros((240, 320, 3), np.uint8)
    frame[:] = (30, 90, 180)
    ok, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
    assert ok
    return buffer.tobytes()


def _stream_from_lan(lan_address: str, hello: Hello, audio: bool):
    """Run one receiver connection against a sender on the LAN address.

    Returns (stats, error) — exactly one of them is not None.
    """
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", 0))
    listener.listen(1)
    port = listener.getsockname()[1]
    result = {}

    def pc_side():
        conn, address = listener.accept()
        result["peer"] = address[0]
        try:
            result["stats"] = handle_connection(conn, NullSink(), max_frames=3)
        except Exception as e:
            result["error"] = e
        finally:
            conn.close()

    pc = threading.Thread(target=pc_side, daemon=True)
    pc.start()

    phone = socket.create_connection((lan_address, port), timeout=5)
    try:
        send_hello(phone, hello)
        jpeg = _jpeg()
        if audio:
            send_typed_message(phone, MessageType.AUDIO_CONFIG, b"\x11\x90")
            for _ in range(4):
                send_typed_message(phone, MessageType.VIDEO, jpeg)
                send_typed_message(phone, MessageType.AUDIO, b"\x01" * 64)
                time.sleep(0.02)
        else:
            for _ in range(4):
                send_typed_message(phone, MessageType.VIDEO, jpeg)
                time.sleep(0.02)
    except OSError:
        # The receiver closing on a rejected Hello is the expected shape of a
        # refusal, not a test failure.
        pass
    finally:
        phone.close()

    pc.join(timeout=10)
    listener.close()
    return result


def _hello(token: str, audio: bool) -> Hello:
    return Hello(
        width=320, height=240, fps=30, quality="240p", watermark=False,
        device_name="wifi-test", codec="jpeg", auth_token=token,
        audio=audio, audio_sample_rate=48_000, audio_channels=1,
        audio_bitrate_bps=128_000,
    )


# ---- the LAN gate ----


def test_a_lan_sender_without_a_token_is_rejected(lan_address, monkeypatch):
    monkeypatch.setattr(receiver_module, "_load_control_token", lambda: "the-real-token")
    result = _stream_from_lan(lan_address, _hello("", audio=False), audio=False)

    assert not result["peer"].startswith("127."), "this test must not run over loopback"
    assert isinstance(result.get("error"), ProtocolError)


def test_a_lan_sender_with_the_wrong_token_is_rejected(lan_address, monkeypatch):
    monkeypatch.setattr(receiver_module, "_load_control_token", lambda: "the-real-token")
    result = _stream_from_lan(lan_address, _hello("guessed", audio=True), audio=True)
    assert isinstance(result.get("error"), ProtocolError)


def test_audio_and_video_both_arrive_over_wifi(lan_address, monkeypatch):
    """The point of the whole exercise: a paired phone streaming wirelessly
    delivers both media over the one socket, from a non-loopback peer."""
    monkeypatch.setattr(receiver_module, "_load_control_token", lambda: "the-real-token")
    result = _stream_from_lan(lan_address, _hello("the-real-token", audio=True), audio=True)

    assert "error" not in result, result.get("error")
    assert not result["peer"].startswith("127.")
    stats = result["stats"]
    assert stats.frames_received == 3
    assert stats.audio_messages_received >= 1, "no audio survived the Wi-Fi path"


def test_video_still_works_over_wifi_without_audio(lan_address, monkeypatch):
    monkeypatch.setattr(receiver_module, "_load_control_token", lambda: "the-real-token")
    hello = _hello("the-real-token", audio=True)
    result = _stream_from_lan(lan_address, hello, audio=False)
    assert "error" not in result, result.get("error")
    assert result["stats"].frames_received == 3
    assert result["stats"].audio_messages_received == 0


# ---- pairing ----


@pytest.fixture(autouse=True)
def closed_pairing_window():
    control_server._pairing_opens_until = 0.0
    yield
    control_server._pairing_opens_until = 0.0


def test_pairing_is_closed_until_someone_opens_it():
    assert control_server.pairing_seconds_left() == 0
    assert control_server.claim_pairing() is False


def test_an_open_window_can_be_claimed_exactly_once():
    """Single-use is what keeps the window from being a door left ajar: the
    race is to one device, not to everyone on the LAN for two minutes."""
    control_server.open_pairing_window(60)
    assert control_server.pairing_seconds_left() > 0
    assert control_server.claim_pairing() is True
    assert control_server.claim_pairing() is False
    assert control_server.pairing_seconds_left() == 0


def test_a_window_that_has_expired_cannot_be_claimed():
    deadline = control_server.open_pairing_window(60)
    assert control_server.claim_pairing(now=deadline + 1) is False


def test_the_window_is_claimable_right_up_to_its_deadline():
    deadline = control_server.open_pairing_window(60)
    assert control_server.claim_pairing(now=deadline - 0.001) is True


def test_seconds_left_counts_down_and_never_goes_negative():
    deadline = control_server.open_pairing_window(120)
    assert control_server.pairing_seconds_left(now=deadline - 30) == 30
    assert control_server.pairing_seconds_left(now=deadline + 999) == 0


# ---- pairing over HTTP, from the LAN ----
#
# The tests above call open_pairing_window/claim_pairing directly, which is how
# a shipped-broken /pair endpoint passed review: the state machine was right and
# unreachable. These go through the real HTTP handler from a non-loopback
# address, which is the only thing that proves a phone could ever pair.


@pytest.fixture
def control_server_on_lan(lan_address):
    import http.server

    control_server.load_or_create_token()
    server = http.server.HTTPServer(("0.0.0.0", 0), control_server.Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield lan_address, port
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _get(url: str):
    import json
    import urllib.error
    import urllib.request

    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def _post(url: str):
    import json
    import urllib.error
    import urllib.request

    request = urllib.request.Request(url, data=b"", method="POST")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def test_a_lan_phone_can_pair_once_the_window_is_open(control_server_on_lan):
    """The regression that shipped: /pair sat behind the token check, so the
    endpoint whose whole job is handing out the token required one."""
    lan, port = control_server_on_lan

    status, _ = _post(f"http://127.0.0.1:{port}/pair/open")
    assert status == 200

    status, body = _get(f"http://{lan}:{port}/pair")
    assert status == 200, f"a LAN phone could not pair: {body}"
    assert body["token"] == control_server._auth_token


def test_a_lan_phone_cannot_pair_without_a_window(control_server_on_lan):
    lan, port = control_server_on_lan
    status, body = _get(f"http://{lan}:{port}/pair")
    assert status == 403
    assert "window" in body.get("error", "")


def test_the_window_serves_exactly_one_phone_over_http(control_server_on_lan):
    lan, port = control_server_on_lan
    _post(f"http://127.0.0.1:{port}/pair/open")
    assert _get(f"http://{lan}:{port}/pair")[0] == 200
    assert _get(f"http://{lan}:{port}/pair")[0] == 403


def test_a_lan_device_cannot_open_its_own_pairing_window(control_server_on_lan):
    """Opening the window is the privileged half — if the LAN could do it, the
    gate would be decoration."""
    lan, port = control_server_on_lan
    status, _ = _post(f"http://{lan}:{port}/pair/open")
    assert status == 403
    assert _get(f"http://{lan}:{port}/pair")[0] == 403


def test_a_non_ascii_token_header_is_answered_not_crashed(control_server_on_lan):
    """secrets.compare_digest raises TypeError on non-ASCII, which left the
    request unanswered and a traceback in the log — triggerable by any LAN
    device with one byte."""
    lan, port = control_server_on_lan

    connection = socket.create_connection((lan, port), timeout=5)
    connection.sendall(
        "GET /status HTTP/1.1\r\nHost: x\r\n"
        "X-FrameCast-Token: caf\u00e9\r\nConnection: close\r\n\r\n".encode("latin-1")
    )
    response = connection.recv(4096)
    connection.close()
    assert response.startswith(b"HTTP/1.0 403") or response.startswith(b"HTTP/1.1 403"), response[:80]
