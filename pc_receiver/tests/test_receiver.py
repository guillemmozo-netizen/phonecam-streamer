import socket
import threading
import time

from pc_receiver.demo_sender import build_synthetic_frame, encode_jpeg
from pc_receiver.protocol import Hello, send_frame, send_hello
from pc_receiver.receiver import decode_frame, handle_connection
from pc_receiver.sinks import NullSink

# handle_connection() now skips displaying a frame if another one is already
# fully buffered behind it (see receiver.py's is_stale doc) — the fix for a
# several-second creeping delay confirmed after switching to 1080p60. These
# tests send frames back-to-back with no real camera pacing behind them, so
# without a small gap the whole burst would already be sitting in the socket
# by the time handle_connection reads the first one, correctly collapsing
# it down to just the last frame — a false failure, not a regression. The
# gap here stands in for that real pacing.
FRAME_GAP_SECONDS = 0.05


def test_decode_frame_handles_corrupt_bytes_gracefully():
    assert decode_frame(b"not a jpeg") is None
    assert decode_frame(b"") is None


def test_decode_frame_recovers_valid_jpeg():
    frame = build_synthetic_frame(64, 48, 0)
    jpeg_bytes = encode_jpeg(frame, quality=80)
    decoded = decode_frame(jpeg_bytes)
    assert decoded is not None
    assert decoded.shape == (48, 64, 3)


def test_handle_connection_over_real_socket_end_to_end():
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()

    hello = Hello(width=64, height=48, fps=30, quality="1080p60", watermark=True, device_name="test")

    def send_side():
        send_hello(client_sock, hello)
        for i in range(3):
            frame = build_synthetic_frame(64, 48, i)
            send_frame(client_sock, encode_jpeg(frame, 80))
            time.sleep(FRAME_GAP_SECONDS)
        client_sock.close()

    sender_thread = threading.Thread(target=send_side)
    sender_thread.start()

    stats = handle_connection(server_sock, sink, max_frames=3)
    sender_thread.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == 3
    assert stats.frames_decoded_failed == 0
    assert len(sink.frames) == 3
    assert sink.closed is True
    for f in sink.frames:
        assert f.shape == (48, 64, 3)


def test_handle_connection_survives_one_corrupt_frame():
    import struct

    server_sock, client_sock = socket.socketpair()
    sink = NullSink()
    hello = Hello(width=64, height=48, fps=30, quality="1080p60", watermark=True, device_name="test")

    def send_side():
        send_hello(client_sock, hello)
        good = encode_jpeg(build_synthetic_frame(64, 48, 0), 80)
        send_frame(client_sock, good)
        time.sleep(FRAME_GAP_SECONDS)
        bad = b"totally not a jpeg"
        client_sock.sendall(struct.pack(">I", len(bad)) + bad)
        time.sleep(FRAME_GAP_SECONDS)
        send_frame(client_sock, good)
        client_sock.close()

    t = threading.Thread(target=send_side)
    t.start()
    stats = handle_connection(server_sock, sink, max_frames=3)
    t.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == 3
    assert stats.frames_decoded_failed == 1
    assert len(sink.frames) == 2
