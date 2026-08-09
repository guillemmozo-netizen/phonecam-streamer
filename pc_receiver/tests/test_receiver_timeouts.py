"""A connection that stops talking must not hold the receiver forever.

`serve_forever` handles one connection at a time, so a single peer that opens a
socket and says nothing denies service to the user's phone indefinitely — and
it needs no token to do it, because the token is only checked after the Hello
has been read.

Every test here runs the receive loop on a daemon thread and asserts it
*finishes*. Before the timeouts existed they hung, which is a failure the suite
survives rather than a wedge.
"""

from __future__ import annotations

import socket
import threading
import time

import numpy as np
import pytest

import pc_receiver.receiver as receiver_module
from pc_receiver.protocol import Hello, send_frame, send_hello
from pc_receiver.receiver import handle_connection
from pc_receiver.sinks import NullSink

# Short enough to keep the suite fast, long enough that a scheduling hiccup
# cannot trip them.
HANDSHAKE = 1.0
IDLE = 1.5
GRACE = 6.0


@pytest.fixture(autouse=True)
def fast_timeouts(monkeypatch):
    monkeypatch.setattr(receiver_module, "HANDSHAKE_TIMEOUT_SECONDS", HANDSHAKE)
    monkeypatch.setattr(receiver_module, "IDLE_TIMEOUT_SECONDS", IDLE)


def _run_receiver(conn, **kwargs):
    """Run handle_connection on a thread; returns (thread, result dict)."""
    result = {}

    def body():
        try:
            result["stats"] = handle_connection(conn, NullSink(), **kwargs)
        except Exception as e:
            result["error"] = e

    thread = threading.Thread(target=body, daemon=True)
    thread.start()
    return thread, result


def _pair():
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    client = socket.create_connection(listener.getsockname(), timeout=5)
    server, _ = listener.accept()
    listener.close()
    return server, client


def _jpeg():
    import cv2

    frame = np.zeros((48, 64, 3), np.uint8)
    ok, buffer = cv2.imencode(".jpg", frame)
    assert ok
    return buffer.tobytes()


def test_a_peer_that_never_sends_a_hello_is_dropped():
    """The denial-of-service in one line: connect, say nothing, keep the
    receiver forever."""
    server, client = _pair()
    thread, result = _run_receiver(server)

    thread.join(timeout=HANDSHAKE + GRACE)
    assert not thread.is_alive(), "receiver still waiting for a Hello that never came"
    assert "error" in result

    client.close()
    server.close()


def test_a_peer_that_sends_a_partial_hello_is_dropped():
    """Half a length prefix is enough to hang a reader that has no deadline."""
    server, client = _pair()
    client.sendall(b"\x00\x00")  # two bytes of a four-byte length

    thread, result = _run_receiver(server)
    thread.join(timeout=HANDSHAKE + GRACE)
    assert not thread.is_alive(), "receiver still waiting for the rest of a length prefix"
    assert "error" in result

    client.close()
    server.close()


def test_a_peer_that_goes_silent_mid_stream_is_dropped():
    """A phone whose Wi-Fi dies without closing the socket looks exactly like
    this, and the receiver has to recover for the next connection."""
    server, client = _pair()
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False)
    send_hello(client, hello)
    send_frame(client, _jpeg())

    thread, result = _run_receiver(server)
    thread.join(timeout=IDLE + GRACE)
    assert not thread.is_alive(), "receiver still waiting mid-stream"

    client.close()
    server.close()


def test_a_slow_but_alive_sender_is_not_dropped():
    """The timeout must not punish a legitimately slow link — otherwise it
    trades one failure for a worse one."""
    server, client = _pair()
    send_hello(client, Hello(width=64, height=48, fps=30, quality="q", watermark=False))

    def sender():
        jpeg = _jpeg()
        for _ in range(3):
            # Comfortably inside the idle timeout, comfortably slower than a
            # real stream.
            time.sleep(IDLE * 0.4)
            try:
                send_frame(client, jpeg)
            except OSError:
                return

    sender_thread = threading.Thread(target=sender, daemon=True)
    sender_thread.start()

    thread, result = _run_receiver(server, max_frames=3)
    thread.join(timeout=IDLE * 3 + GRACE)
    sender_thread.join(timeout=2)

    assert not thread.is_alive()
    assert "error" not in result, result.get("error")
    assert result["stats"].frames_received == 3

    client.close()
    server.close()


def test_the_listener_accepts_a_new_connection_after_a_dead_one():
    """The point of all of this: the user's phone gets in after a peer that
    never spoke."""
    port_holder = {}
    accepted = []

    def server_loop():
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(8)
        port_holder["port"] = listener.getsockname()[1]
        port_holder["ready"].set()
        for _ in range(2):
            conn, _ = listener.accept()
            try:
                handle_connection(conn, NullSink(), max_frames=1)
                accepted.append("streamed")
            except Exception:
                accepted.append("dropped")
            finally:
                conn.close()
        listener.close()

    port_holder["ready"] = threading.Event()
    loop = threading.Thread(target=server_loop, daemon=True)
    loop.start()
    assert port_holder["ready"].wait(timeout=5)
    port = port_holder["port"]

    # A peer that connects and says nothing.
    dead = socket.create_connection(("127.0.0.1", port), timeout=5)
    time.sleep(HANDSHAKE + 1.0)

    # The real phone, which must get through.
    phone = socket.create_connection(("127.0.0.1", port), timeout=5)
    send_hello(phone, Hello(width=64, height=48, fps=30, quality="q", watermark=False))
    send_frame(phone, _jpeg())

    loop.join(timeout=IDLE + GRACE)
    dead.close()
    phone.close()

    assert accepted == ["dropped", "streamed"], accepted


def test_checking_for_backlog_does_not_clear_the_socket_deadline():
    """setblocking(True) is settimeout(None).

    FrameReader tops up from the kernel buffer non-blockingly on every frame and
    used to restore the socket with setblocking(True), which cleared the
    receiver's idle timeout the moment the first frame arrived — so the deadline
    protected only the handshake and nothing after it.
    """
    from pc_receiver.protocol import FrameReader

    server, client = _pair()
    server.settimeout(IDLE)
    reader = FrameReader(server)

    reader.buffered_message_count()

    assert server.gettimeout() == IDLE, (
        f"deadline was cleared: gettimeout() == {server.gettimeout()}"
    )

    client.close()
    server.close()
