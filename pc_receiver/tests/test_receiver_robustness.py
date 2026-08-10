"""Regression tests for failures found by auditing the code and this
machine's own receiver.log.

Each test here corresponds to a specific defect that was reachable in
production, and several of them to one that demonstrably *happened* — the
virtual-camera teardown crash appears 13 times in the log. They are grouped
by the failure they prevent rather than by the module they touch, because
the interesting part of each is the interaction, not the unit.
"""

import socket
import struct
import threading
import time
from fractions import Fraction

import av
import numpy as np
import pytest

from pc_receiver.demo_sender import build_synthetic_frame, encode_jpeg
from pc_receiver.h264_decoder import H264Decoder
from pc_receiver.protocol import FrameReader, Hello, send_hello, send_message
from pc_receiver.receiver import (
    STREAM_IDLE_TIMEOUT_SECONDS,
    AudioOptions,
    _SinkWriter,
    handle_connection,
)
from pc_receiver.sinks import NullSink


def encode_h264(count: int = 8, width: int = 64, height: int = 48):
    """Real Annex-B packets, standing in for the phone's MediaCodec."""
    encoder = av.CodecContext.create("h264", "w")
    encoder.width, encoder.height = width, height
    encoder.pix_fmt = "yuv420p"
    encoder.bit_rate = 400_000
    encoder.time_base = Fraction(1, 30)

    packets = []
    for i in range(count):
        image = np.full((height, width, 3), (i * 30) % 256, dtype=np.uint8)
        frame = av.VideoFrame.from_ndarray(image, format="rgb24").reformat(format="yuv420p")
        frame.pts = i
        packets.extend(encoder.encode(frame))
    packets.extend(encoder.encode(None))
    return [bytes(p) for p in packets]


# ───────────────── flush must match decode's pixel format ─────────────────

@pytest.mark.parametrize("output_format", ["rgb", "native"])
def test_flush_returns_the_same_layout_as_decode(output_format):
    """The bug behind 13 recorded "virtual camera output could not be
    started" failures.

    flush() hardcoded RGB while decode() honoured output_format, so on the
    real (native/NV12) path a session's last frames arrived in a different
    layout from every frame before them. VirtualCamSink derives the camera
    resolution from the array shape, so it read the height as two thirds of
    the picture, concluded the resolution had changed, and tore down a
    working camera to build a wrongly-sized one — which OBS refused.
    """
    decoder = H264Decoder(output_format=output_format)

    decoded = []
    for packet in encode_h264():
        decoded.extend(decoder.decode(packet))
    flushed = decoder.flush()

    assert decoded, "nothing decoded — the test's encoder produced no usable frames"
    assert flushed, "nothing flushed — this test cannot prove anything"
    assert {f.shape for f in flushed} == {f.shape for f in decoded}
    assert {f.dtype for f in flushed} == {f.dtype for f in decoded}


def test_a_sink_sees_one_consistent_geometry_for_the_whole_session():
    """The property that actually matters downstream: the resolution
    VirtualCamSink computes must never change mid-session, because changing
    it destroys and recreates the camera."""
    decoder = H264Decoder(output_format="native")

    def camera_size(frame):
        # The same arithmetic VirtualCamSink.send performs for planar 4:2:0.
        return (frame.shape[1], frame.shape[0] * 2 // 3)

    sizes = set()
    for packet in encode_h264():
        sizes.update(camera_size(f) for f in decoder.decode(packet))
    sizes.update(camera_size(f) for f in decoder.flush())

    assert len(sizes) == 1, f"camera would have been rebuilt mid-session: {sizes}"


# ───────────────── teardown must always release resources ─────────────────

class ExplodingSink:
    """A sink whose send() fails the way a virtual camera does when OBS
    refuses to start the output."""

    preferred_frame_format = "native"

    def __init__(self, fail_on_send: bool = True) -> None:
        self.fail_on_send = fail_on_send
        self.closed = False
        self.frames = 0

    def set_frame_format(self, name: str) -> None:
        pass

    def send(self, frame, fps: int) -> None:
        self.frames += 1
        if self.fail_on_send:
            raise RuntimeError("'obs' backend: virtual camera output could not be started")

    def close(self) -> None:
        self.closed = True


def run_h264_session(sink, packets, audio_options=None):
    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="1080p60", watermark=False, codec="h264")

    def sender():
        send_hello(client_sock, hello)
        for packet in packets:
            send_message(client_sock, packet)
            time.sleep(0.02)
        client_sock.close()

    thread = threading.Thread(target=sender)
    thread.start()
    try:
        return handle_connection(
            server_sock, sink, max_frames=len(packets),
            audio_options=audio_options or AudioOptions(sink="none"),
        )
    finally:
        thread.join(timeout=10)
        server_sock.close()


def test_a_failing_sink_does_not_prevent_the_sink_being_closed():
    """The compounding half of the production bug: the exception escaped
    handle_connection's finally block, so the decoder context (with its NVDEC
    surfaces), the audio device and the camera were all left open — on
    exactly the path that was already failing."""
    sink = ExplodingSink()

    run_h264_session(sink, encode_h264())

    assert sink.closed is True, "sink was leaked when teardown raised"


def test_a_failing_sink_does_not_propagate_out_of_handle_connection():
    """serve_forever survives it either way, but it logged the session as a
    crashed connection rather than a completed one, which is what buried the
    real fault in the log for weeks."""
    stats = run_h264_session(ExplodingSink(), encode_h264())

    assert stats.frames_received > 0


def test_teardown_continues_past_a_failure_in_an_earlier_step():
    sink = ExplodingSink()
    sink.fail_on_send = True

    run_h264_session(sink, encode_h264())

    # close() runs even though every send() before it raised.
    assert sink.closed is True


# ───────────────── the writer thread must survive a bad send ─────────────────

def test_sink_writer_survives_a_raising_send():
    """A raising send used to kill this thread outright. submit() then went
    on filling a mailbox nobody read: video froze for the rest of the
    session, and nothing was logged because the exception died with the
    thread."""
    sink = ExplodingSink()
    writer = _SinkWriter(sink, on_sent=lambda _: None)

    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    for _ in range(5):
        writer.submit(frame, 30)
        time.sleep(0.02)

    assert writer._thread.is_alive(), "writer thread died on a failing send"
    assert sink.frames >= 2, "writer stopped consuming after the first failure"
    writer.close()


def test_sink_writer_recovers_when_the_sink_starts_working_again():
    sink = ExplodingSink()
    writer = _SinkWriter(sink, on_sent=lambda _: None)
    frame = np.zeros((48, 64, 3), dtype=np.uint8)

    writer.submit(frame, 30)
    time.sleep(0.05)
    sink.fail_on_send = False
    delivered = []
    writer._on_sent = lambda d: delivered.append(d)
    writer.submit(frame, 30)
    time.sleep(0.05)

    assert delivered, "writer never resumed delivering after the sink recovered"
    writer.close()


# ───────────────── a frozen peer must not wedge the receiver ─────────────────

def test_a_silent_sender_is_dropped_instead_of_blocking_forever(monkeypatch):
    """A phone that suspends, or Wi-Fi that drops without a FIN, leaves a
    half-open socket that never errors and never delivers. The receive loop
    parked in recv() forever — and since the receiver accepts one connection
    at a time, it never accepted the phone's reconnect either. The only fix
    was restarting the PC service.
    """
    import pc_receiver.receiver as receiver_module

    monkeypatch.setattr(receiver_module, "STREAM_IDLE_TIMEOUT_SECONDS", 0.3)

    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="1080p60", watermark=False)
    send_hello(client_sock, hello)
    send_message(client_sock, encode_jpeg(build_synthetic_frame(64, 48, 0), 80))
    # ...and then the sender goes silent without closing, exactly like a
    # suspended phone. client_sock is deliberately left open.

    sink = NullSink()
    started = time.monotonic()
    stats = handle_connection(server_sock, sink, audio_options=AudioOptions(sink="none"))
    elapsed = time.monotonic() - started

    assert elapsed < 5, f"handle_connection blocked for {elapsed:.1f}s on a silent peer"
    assert stats.frames_received == 1
    assert sink.closed is True
    client_sock.close()
    server_sock.close()


def test_idle_timeout_is_long_enough_for_a_slow_but_live_stream():
    """Guards the other direction: a 1fps session is legitimate, and must not
    be mistaken for a dead peer."""
    assert STREAM_IDLE_TIMEOUT_SECONDS >= 5


def test_backlog_check_does_not_clear_the_socket_timeout():
    """setblocking(True) is defined as settimeout(None), so the obvious
    `finally: setblocking(True)` silently removed the receiver's only defence
    against a half-open peer — from the very first frame onwards."""
    server_sock, client_sock = socket.socketpair()
    server_sock.settimeout(7.5)
    reader = FrameReader(server_sock)

    send_message(client_sock, b"payload")
    reader.buffered_message_count()

    assert server_sock.gettimeout() == 7.5
    client_sock.close()
    server_sock.close()


def test_a_client_that_never_sends_a_hello_is_dropped(monkeypatch):
    """Anything on the LAN can open the port. Holding the receiver's single
    accept slot open by simply saying nothing must not be possible."""
    import pc_receiver.receiver as receiver_module

    monkeypatch.setattr(receiver_module, "STREAM_IDLE_TIMEOUT_SECONDS", 0.3)
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()

    started = time.monotonic()
    stats = handle_connection(server_sock, sink, audio_options=AudioOptions(sink="none"))
    elapsed = time.monotonic() - started

    # Returns cleanly rather than raising: a silent client is ordinary, and
    # letting it escape meant a full traceback in the log for every port scan.
    assert elapsed < 5
    assert stats.frames_received == 0
    assert sink.closed is True, "sink leaked when no hello ever arrived"

    client_sock.close()
    server_sock.close()


# ─────────── a permanently slow consumer must degrade, not go black ───────────

class _CountingSink:
    preferred_frame_format = "rgb"

    def __init__(self) -> None:
        self.frames = 0

    def send(self, frame, fps: int) -> None:
        self.frames += 1

    def close(self) -> None:
        pass


class _SlowDecoderStats:
    backend = "fake"
    decode_avg_ms = 0.0
    convert_avg_ms = 0.0


class _SlowDecoder:
    """Stands in for 8K: decode+convert costs more than the frame interval,
    so the receive loop itself falls permanently behind the arrivals."""

    frame_format = "rgb"

    def __init__(self, *args, **kwargs) -> None:
        pass

    def decode(self, payload):
        time.sleep(0.05)
        return [np.zeros((48, 64, 3), dtype=np.uint8)]

    def flush(self):
        return []

    def close(self) -> None:
        pass

    def pop_stage_stats(self):
        return _SlowDecoderStats()


def _run_against_a_slow_decoder(monkeypatch):
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "H264Decoder", _SlowDecoder)
    packets = [b"\x00\x00\x00\x01\x65" + bytes(200)] * 30
    sink = _CountingSink()
    server_sock, client_sock = socket.socketpair()
    hello = Hello(width=64, height=48, fps=30, quality="1080p60",
                  watermark=False, codec="h264")

    def sender():
        send_hello(client_sock, hello)
        # Pushed as fast as the socket takes them, so the reader always has a
        # deep backlog behind whatever the slow decoder is working on.
        for packet in packets:
            send_message(client_sock, packet)
        client_sock.close()

    thread = threading.Thread(target=sender)
    thread.start()
    try:
        handle_connection(server_sock, sink, max_frames=len(packets),
                          audio_options=AudioOptions(sink="none"))
    finally:
        thread.join(timeout=10)
        server_sock.close()
    return sink.frames


def test_a_consumer_that_can_never_catch_up_still_shows_frames(monkeypatch):
    """The 8K black-screen bug.

    Skipping backlogged frames assumes the backlog is transient. When the
    consumer is permanently slower than the producer every frame looks
    stale, so every frame was skipped and the virtual camera showed nothing
    at all — measured live on an 8K30 session as `shown=0.0fps` with
    `backlog_max=21`, while the stream itself decoded perfectly. Degrading to
    a lower frame rate is the correct outcome; degrading to black is not.
    """
    assert _run_against_a_slow_decoder(monkeypatch) > 0


def test_without_the_floor_the_picture_all_but_stops(monkeypatch):
    """Pins that the test above is testing the fix and not the weather.

    With the floor disabled only the handful of frames that arrive before
    the backlog builds are ever shown — measured here as 2 out of 30, and in
    the wild as a virtual camera frozen on one picture for a whole session.
    The floor turns the same run into several times that.
    """
    import pc_receiver.receiver as rx

    monkeypatch.setattr(rx, "MAX_SECONDS_WITHOUT_A_SHOWN_FRAME", 10_000)
    starved = _run_against_a_slow_decoder(monkeypatch)
    monkeypatch.undo()
    healthy = _run_against_a_slow_decoder(monkeypatch)
    assert starved <= 3
    assert healthy >= starved * 3
