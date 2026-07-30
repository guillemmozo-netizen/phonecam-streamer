"""End-to-end receive-loop tests for audio sessions, over a real socket.

The interleaving is the point: these send audio and video down one connection
the way the phone does, so the demux, the kind-filtered backlog counting and
the "audio failing never costs you video" rule are all exercised together
rather than in isolation.
"""

import socket
import struct
import threading
import time
import wave

import numpy as np

from pc_receiver.demo_sender import build_synthetic_frame, encode_jpeg
from pc_receiver.protocol import Hello, MediaKind, pack_chunk, send_hello, send_message
from pc_receiver.receiver import AudioOptions, handle_connection
from pc_receiver.sinks import NullSink
from pc_receiver.tests.test_audio_decoder import encode_aac

# Same reason as test_receiver.py's constant: these tests have no camera
# pacing behind them, so back-to-back video frames would correctly collapse
# to the newest one as backlog. Audio packets deliberately go out with no gap
# — they are never skipped for backlog, and sending them fast is exactly how
# the kind-filtered counting gets tested.
FRAME_GAP_SECONDS = 0.05

AUDIO_HELLO = dict(audio_codec="aac", audio_sample_rate=48000, audio_channels=2)


def audio_hello(**overrides) -> Hello:
    base = dict(
        width=64, height=48, fps=30, quality="1080p60", watermark=False, device_name="test"
    )
    base.update(AUDIO_HELLO)
    base.update(overrides)
    return Hello(**base)


def run_session(hello, send_side, max_frames, audio_options):
    """Drive handle_connection against a real socketpair and return
    (stats, video_sink)."""
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()

    def sender():
        send_hello(client_sock, hello)
        send_side(client_sock)
        client_sock.close()

    thread = threading.Thread(target=sender)
    thread.start()
    try:
        stats = handle_connection(
            server_sock, sink, max_frames=max_frames, audio_options=audio_options
        )
    finally:
        thread.join(timeout=10)
        server_sock.close()
    return stats, sink


def send_video(sock, index, pts_us):
    payload = encode_jpeg(build_synthetic_frame(64, 48, index), 80)
    send_message(sock, pack_chunk(MediaKind.VIDEO, pts_us, payload))


def test_interleaved_audio_and_video_both_arrive():
    packets = encode_aac(seconds=0.3)

    def send_side(sock):
        for i in range(3):
            for packet in packets[i * 4 : (i + 1) * 4]:
                send_message(sock, pack_chunk(MediaKind.AUDIO, i * 100_000, packet))
            send_video(sock, i, i * 100_000)
            time.sleep(FRAME_GAP_SECONDS)

    stats, sink = run_session(audio_hello(), send_side, 3, AudioOptions(sink="null"))

    assert stats.frames_received == 3            # max_frames counts video only
    assert len(sink.frames) == 3
    assert stats.audio_packets_received == 12
    assert stats.audio_frames_decoded > 0
    assert stats.audio_packets_failed == 0


def test_audio_packets_do_not_make_video_look_backlogged():
    """The regression this guards: an unfiltered backlog count sits at >= 2
    almost permanently once audio shares the socket, and every video frame
    behind it gets skipped as stale. A 10fps slideshow with perfect audio."""
    packets = encode_aac(seconds=0.5)

    def send_side(sock):
        for i in range(5):
            # A burst of audio immediately behind each frame is the exact
            # shape that used to trip the stale check.
            send_video(sock, i, i * 100_000)
            for packet in packets[i * 5 : (i + 1) * 5]:
                send_message(sock, pack_chunk(MediaKind.AUDIO, i * 100_000, packet))
            time.sleep(FRAME_GAP_SECONDS)

    stats, sink = run_session(audio_hello(), send_side, 5, AudioOptions(sink="null"))

    assert stats.frames_received == 5
    assert len(sink.frames) == 5, "video frames were skipped as stale because of audio backlog"


def test_audio_reaches_a_wav_file_intact(tmp_path):
    """Proves the bytes make it all the way out of the pipeline, not just to
    the decoder — and gives a file to listen to when something sounds wrong."""
    wav_path = tmp_path / "out.wav"
    packets = encode_aac(seconds=0.5)

    def send_side(sock):
        for index, packet in enumerate(packets):
            send_message(sock, pack_chunk(MediaKind.AUDIO, index * 21_333, packet))
        for i in range(2):
            send_video(sock, i, 0)
            time.sleep(FRAME_GAP_SECONDS)

    stats, _ = run_session(
        audio_hello(), send_side, 2, AudioOptions(sink="wav", wav_path=str(wav_path))
    )

    assert stats.audio_packets_received == len(packets)
    with wave.open(str(wav_path)) as handle:
        assert handle.getnchannels() == 2
        assert handle.getframerate() == 48000
        frames = handle.getnframes()
        samples = np.frombuffer(handle.readframes(frames), dtype="<i2")
    assert frames > 0.4 * 48000
    assert np.abs(samples).max() > 1000, "the recorded audio is silent"


def test_a_malformed_chunk_costs_one_message_not_the_connection():
    def send_side(sock):
        send_video(sock, 0, 0)
        time.sleep(FRAME_GAP_SECONDS)
        # Too short to hold a chunk header at all.
        send_message(sock, b"\x01\x02")
        time.sleep(FRAME_GAP_SECONDS)
        # Well-formed framing, nonsense kind.
        send_message(sock, struct.pack(">Bq", 77, 0) + b"junk")
        time.sleep(FRAME_GAP_SECONDS)
        send_video(sock, 1, 100_000)
        time.sleep(FRAME_GAP_SECONDS)

    stats, sink = run_session(audio_hello(), send_side, 2, AudioOptions(sink="null"))

    assert stats.frames_received == 2
    assert len(sink.frames) == 2


def test_unsupported_audio_codec_still_delivers_video():
    """A phone announcing a codec this build doesn't know must cost the user
    their microphone, not their camera — and the framing is still tagged, so
    the headers have to be stripped regardless."""
    def send_side(sock):
        for i in range(3):
            send_message(sock, pack_chunk(MediaKind.AUDIO, 0, b"whatever this codec is"))
            send_video(sock, i, i * 100_000)
            time.sleep(FRAME_GAP_SECONDS)

    stats, sink = run_session(
        audio_hello(audio_codec="some-future-codec"), send_side, 3, AudioOptions(sink="null")
    )

    assert len(sink.frames) == 3
    assert stats.audio_packets_received == 3
    assert stats.audio_frames_decoded == 0


def test_audio_sink_none_ignores_audio_but_still_reads_the_framing():
    """--audio-sink none is a receiver-side choice; the sender still tags
    every message, so the headers must come off either way."""
    def send_side(sock):
        for i in range(3):
            send_message(sock, pack_chunk(MediaKind.AUDIO, 0, b"ignored"))
            send_video(sock, i, i * 100_000)
            time.sleep(FRAME_GAP_SECONDS)

    stats, sink = run_session(audio_hello(), send_side, 3, AudioOptions(sink="none"))

    assert len(sink.frames) == 3
    assert stats.audio_packets_received == 3
    assert stats.audio_frames_decoded == 0


def test_video_only_sender_is_untouched_by_any_of_this():
    """The compatibility guarantee, at the receive loop rather than the
    parser: an untagged sender's payloads are frames whole, headers and
    all."""
    def send_side(sock):
        for i in range(3):
            send_message(sock, encode_jpeg(build_synthetic_frame(64, 48, i), 80))
            time.sleep(FRAME_GAP_SECONDS)

    hello = Hello(width=64, height=48, fps=30, quality="1080p60", watermark=False)
    stats, sink = run_session(hello, send_side, 3, AudioOptions(sink="null"))

    assert stats.frames_received == 3
    assert stats.frames_decoded_failed == 0
    assert len(sink.frames) == 3
    assert stats.audio_packets_received == 0
    for frame in sink.frames:
        assert frame.shape == (48, 64, 3)
