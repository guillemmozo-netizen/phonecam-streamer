"""Fault injection: the receiver under the conditions that actually occur.

Every scenario here is one from the field — a phone that reconnects in a
loop, OBS not running so the virtual camera cannot open, a link that
dribbles bytes, a sender that lies about its frame sizes. The receiver is
expected to survive all of them and keep serving, because the alternative is
a background service that needs restarting by hand.
"""

import socket
import struct
import threading
import time

import numpy as np
import pytest

from pc_receiver.demo_sender import build_synthetic_frame, encode_jpeg
from pc_receiver.protocol import MAX_FRAME_BYTES, Hello, ProtocolError, send_hello, send_message
from pc_receiver.receiver import AudioOptions, handle_connection, serve_forever
from pc_receiver.sinks import NullSink

NO_AUDIO = AudioOptions(sink="none")


def hello_for(**kwargs) -> Hello:
    base = dict(width=64, height=48, fps=30, quality="1080p60", watermark=False)
    base.update(kwargs)
    return Hello(**base)


def jpeg(index: int = 0) -> bytes:
    return encode_jpeg(build_synthetic_frame(64, 48, index), 80)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


# ───────────────────── reconnect storms ─────────────────────

def test_the_listener_survives_a_burst_of_reconnects():
    """A phone whose stream keeps failing reconnects immediately and
    forever. Each cycle must be fully independent — one bad session must not
    degrade the next, and the listener must never stop accepting."""
    port = free_port()
    stop = threading.Event()
    server_thread = threading.Thread(
        target=lambda: serve_forever(port, "null", "127.0.0.1"), daemon=True,
    )
    server_thread.start()
    time.sleep(0.4)

    completed = 0
    for session in range(10):
        try:
            client = socket.create_connection(("127.0.0.1", port), timeout=3)
        except OSError:
            break
        with client:
            send_hello(client, hello_for())
            send_message(client, jpeg(session))
            time.sleep(0.05)
        completed += 1
        time.sleep(0.05)

    stop.set()
    assert completed == 10, f"listener stopped accepting after {completed} reconnects"


def test_a_client_that_connects_and_vanishes_does_not_wedge_the_listener():
    """Connect, send nothing, disappear — the shape of a phone that loses
    Wi-Fi during the handshake."""
    port = free_port()
    threading.Thread(target=lambda: serve_forever(port, "null", "127.0.0.1"), daemon=True).start()
    time.sleep(0.4)

    ghost = socket.create_connection(("127.0.0.1", port), timeout=3)
    ghost.close()
    time.sleep(0.2)

    # A real session must still be served afterwards.
    with socket.create_connection(("127.0.0.1", port), timeout=3) as client:
        send_hello(client, hello_for())
        send_message(client, jpeg())
        time.sleep(0.2)


# ───────────────────── the sink failing ─────────────────────

def test_a_sink_that_cannot_be_created_does_not_kill_the_process():
    """OBS not running means pyvirtualcam raises on creation. That used to
    propagate out of main() and kill the receiver, which control_server then
    respawned, in a tight loop."""
    port = free_port()
    import pc_receiver.receiver as receiver_module

    original = receiver_module.create_sink
    attempts = []

    def failing_sink(kind):
        attempts.append(kind)
        if len(attempts) <= 2:
            raise RuntimeError("'obs' backend: virtual camera output could not be started")
        return original("null")

    receiver_module.create_sink = failing_sink
    try:
        threading.Thread(target=lambda: serve_forever(port, "virtualcam", "127.0.0.1"), daemon=True).start()
        time.sleep(0.4)

        for i in range(3):
            # Connecting is the assertion: the listener must still be there.
            # The *writes* are expected to fail on the first two attempts —
            # the receiver closes the connection when it cannot build a sink,
            # so the client's send lands on a dead socket. That is correct
            # behaviour, not the failure under test.
            try:
                client = socket.create_connection(("127.0.0.1", port), timeout=3)
            except OSError:
                pytest.fail(f"listener died after {i} failed sink creation(s)")
            with client:
                try:
                    send_hello(client, hello_for())
                    send_message(client, jpeg(i))
                except OSError:
                    pass
            time.sleep(0.15)

        assert len(attempts) == 3, (
            f"listener stopped accepting after a sink failure (only {len(attempts)} attempts)"
        )
    finally:
        receiver_module.create_sink = original


# ───────────────────── hostile / broken senders ─────────────────────

def test_a_lying_length_prefix_is_rejected_without_allocating():
    """A 3GB length prefix must be refused on sight, not used to size a
    buffer."""
    server_sock, client_sock = socket.socketpair()
    client_sock.sendall(struct.pack(">I", MAX_FRAME_BYTES + 1) + b"x")
    sink = NullSink()

    # Refused during the hello read, which now ends the connection cleanly
    # instead of raising — a hostile sender must not be able to put a stack
    # trace in the log, let alone size a buffer from its own number.
    stats = handle_connection(server_sock, sink, audio_options=NO_AUDIO)

    assert stats.frames_received == 0
    assert sink.closed is True
    client_sock.close()
    server_sock.close()


def test_a_hello_that_is_not_json_is_rejected_cleanly():
    server_sock, client_sock = socket.socketpair()
    send_message(client_sock, b"<html>not a hello</html>")

    with pytest.raises(Exception):
        handle_connection(server_sock, NullSink(), audio_options=NO_AUDIO)

    client_sock.close()
    server_sock.close()


def test_a_dribbled_frame_still_decodes():
    """A slow or congested link delivers a frame in many small TCP segments.
    The reader must reassemble rather than treat a partial read as a frame."""
    server_sock, client_sock = socket.socketpair()
    payload = jpeg()
    sink = NullSink()

    def dribble():
        send_hello(client_sock, hello_for())
        client_sock.sendall(struct.pack(">I", len(payload)))
        for offset in range(0, len(payload), 97):   # deliberately awkward chunk size
            client_sock.sendall(payload[offset:offset + 97])
            time.sleep(0.001)
        time.sleep(0.1)
        client_sock.close()

    thread = threading.Thread(target=dribble)
    thread.start()
    stats = handle_connection(server_sock, sink, max_frames=1, audio_options=NO_AUDIO)
    thread.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == 1
    assert stats.frames_decoded_failed == 0
    assert sink.total_frames == 1


def test_an_abruptly_reset_connection_is_handled_as_a_disconnect():
    """RST rather than FIN — what a cable pull or a killed sender produces.
    It appears once in this machine's receiver.log as an unhandled
    ConnectionResetError."""
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()

    def reset():
        send_hello(client_sock, hello_for())
        send_message(client_sock, jpeg())
        time.sleep(0.05)
        # SO_LINGER 0 makes close() send RST instead of FIN.
        client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        client_sock.close()

    thread = threading.Thread(target=reset)
    thread.start()
    try:
        handle_connection(server_sock, sink, audio_options=NO_AUDIO)
    except (ConnectionResetError, OSError):
        pytest.fail("a reset connection escaped as an unhandled error")
    finally:
        thread.join(timeout=5)
        server_sock.close()

    assert sink.closed is True, "sink leaked when the peer reset the connection"


def test_an_empty_frame_does_not_crash_the_decoder():
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()

    def send_side():
        send_hello(client_sock, hello_for())
        send_message(client_sock, b"")
        time.sleep(0.05)
        send_message(client_sock, jpeg())
        time.sleep(0.05)
        client_sock.close()

    thread = threading.Thread(target=send_side)
    thread.start()
    stats = handle_connection(server_sock, sink, max_frames=2, audio_options=NO_AUDIO)
    thread.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == 2
    assert stats.frames_decoded_failed == 1
