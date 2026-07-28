"""Auth and input-validation tests for the network surface.

Both endpoints are reachable from the LAN by design (a Wi-Fi phone has to get
in), so everything they accept is untrusted input.
"""

import socket
import threading

import pytest

from pc_receiver.protocol import Hello, ProtocolError, send_hello
from pc_receiver.receiver import handle_connection
from pc_receiver.sinks import NullSink


def test_hello_ignores_unknown_fields():
    """A stray key must not kill the connection.

    cls(**obj) used to raise TypeError on anything unexpected, so a single new
    field from a newer phone would take down an older receiver.
    """
    raw = b'{"width":64,"height":48,"fps":30,"quality":"x","watermark":false,"brand_new_field":1}'
    hello = Hello.from_json_bytes(raw)
    assert hello.width == 64
    assert hello.fps == 30


def test_hello_rejects_absurd_dimensions():
    """Out-of-range geometry would otherwise be pushed straight into OBS's
    canvas by the settings sync."""
    for payload in (
        b'{"width":999999,"height":48,"fps":30,"quality":"x","watermark":false}',
        b'{"width":64,"height":0,"fps":30,"quality":"x","watermark":false}',
        b'{"width":64,"height":48,"fps":100000,"quality":"x","watermark":false}',
    ):
        with pytest.raises(ProtocolError):
            Hello.from_json_bytes(payload)


def test_hello_rejects_non_numeric_dimensions():
    with pytest.raises(ProtocolError):
        Hello.from_json_bytes(
            b'{"width":"wide","height":48,"fps":30,"quality":"x","watermark":false}'
        )


def test_hello_rejects_non_object():
    with pytest.raises(ProtocolError):
        Hello.from_json_bytes(b'["not", "an", "object"]')


def test_hello_truncates_oversized_strings():
    """An unbounded device_name would end up in logs and in OBS requests."""
    raw = b'{"width":64,"height":48,"fps":30,"quality":"x","watermark":false,"device_name":"' \
          + b"A" * 5000 + b'"}'
    assert len(Hello.from_json_bytes(raw).device_name) == 256


def test_loopback_sender_needs_no_token(monkeypatch):
    """USB arrives on loopback and must stay zero-configuration."""
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "_load_control_token", lambda: "a-real-token")
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False)

    threading.Thread(target=lambda: (send_hello(client_sock, hello), client_sock.close())).start()
    # socketpair() has no peer address, which _peer_ip reports as "?" — stand in
    # for the loopback case explicitly instead of pretending socketpair is one.
    monkeypatch.setattr(rx, "_peer_ip", lambda conn: "127.0.0.1")
    stats = handle_connection(server_sock, sink, max_frames=0)
    server_sock.close()
    assert stats.frames_received == 0


def test_remote_sender_without_token_is_rejected(monkeypatch):
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "_load_control_token", lambda: "a-real-token")
    monkeypatch.setattr(rx, "_peer_ip", lambda conn: "192.168.0.99")

    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False)
    threading.Thread(target=lambda: (send_hello(client_sock, hello), client_sock.close())).start()

    with pytest.raises(ProtocolError):
        handle_connection(server_sock, NullSink(), max_frames=0)
    server_sock.close()


def test_remote_sender_with_correct_token_is_accepted(monkeypatch):
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "_load_control_token", lambda: "a-real-token")
    monkeypatch.setattr(rx, "_peer_ip", lambda conn: "192.168.0.99")

    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False,
                  auth_token="a-real-token")
    threading.Thread(target=lambda: (send_hello(client_sock, hello), client_sock.close())).start()

    stats = handle_connection(server_sock, NullSink(), max_frames=0)
    server_sock.close()
    assert stats.frames_received == 0


def test_remote_sender_rejected_when_pc_has_no_token(monkeypatch):
    """No token on disk means the PC never generated one — refusing remote
    senders is the safe reading, and USB still works."""
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "_load_control_token", lambda: "")
    monkeypatch.setattr(rx, "_peer_ip", lambda conn: "192.168.0.99")

    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False, auth_token="x")
    threading.Thread(target=lambda: (send_hello(client_sock, hello), client_sock.close())).start()

    with pytest.raises(ProtocolError):
        handle_connection(server_sock, NullSink(), max_frames=0)
    server_sock.close()


def test_buffered_message_count_does_not_absorb_the_socket_without_limit():
    """Regression: this used to drain the kernel buffer in full on every frame.

    A sender outrunning the consumer moved the whole surplus into this
    bytearray - measured at 457 MiB in one object over a 6000-frame session -
    and reopening the TCP window that way also removed the backpressure the
    caller depends on.
    """
    import struct as _struct

    from pc_receiver.protocol import _MAX_OPPORTUNISTIC_BUFFER, FrameReader

    server_sock, client_sock = socket.socketpair()
    server_sock.settimeout(5)
    payload = b"x" * 60_000
    message = _struct.pack(">I", len(payload)) + payload

    # Push far more than the cap at the reader.
    sent = 0
    client_sock.setblocking(False)
    try:
        while sent < _MAX_OPPORTUNISTIC_BUFFER * 3:
            client_sock.sendall(message)
            sent += len(message)
    except (BlockingIOError, OSError):
        pass
    client_sock.setblocking(True)

    reader = FrameReader(server_sock)
    reader.buffered_message_count()
    assert len(reader._buf) <= _MAX_OPPORTUNISTIC_BUFFER + 65536

    client_sock.close()
    server_sock.close()
