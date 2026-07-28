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
