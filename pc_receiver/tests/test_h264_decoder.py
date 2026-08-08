import socket
import threading
import time

import av
import numpy as np

from pc_receiver.h264_decoder import H264Decoder
from pc_receiver.protocol import Hello, send_frame, send_hello
from pc_receiver.receiver import handle_connection
from pc_receiver.sinks import NullSink

WIDTH, HEIGHT = 64, 48


def _encode_synthetic_h264(num_frames: int) -> list[bytes]:
    """Encodes a handful of solid-color frames to Annex-B H.264 chunks —
    stands in for what android-app's H264Encoder.kt produces from the
    camera. No B-frames, matching a real-time streaming encoder (Android's
    MediaCodec doesn't reorder for this use case either), so each encoded
    chunk maps to exactly one decoded frame with no extra latency to drain.
    """
    encoder = av.CodecContext.create("h264", "w")
    encoder.width = WIDTH
    encoder.height = HEIGHT
    encoder.pix_fmt = "yuv420p"
    encoder.framerate = 30
    encoder.bit_rate = 500_000
    encoder.options = {"g": "10", "bf": "0"}

    chunks = []
    for i in range(num_frames):
        arr = np.full((HEIGHT, WIDTH, 3), fill_value=(i * 40) % 256, dtype=np.uint8)
        frame = av.VideoFrame.from_ndarray(arr, format="bgr24").reformat(format="yuv420p")
        for packet in encoder.encode(frame):
            chunks.append(bytes(packet))
    for packet in encoder.encode(None):
        chunks.append(bytes(packet))
    return chunks


def test_h264_decoder_roundtrip():
    chunks = _encode_synthetic_h264(5)
    decoder = H264Decoder()
    frames = []
    for chunk in chunks:
        frames.extend(decoder.decode(chunk))
    # Hardware decoders (h264_cuvid/NVDEC — see H264Decoder._create_context)
    # pipeline several frames deep and only emit them once flushed; a real
    # caller (receiver.py) always flushes before close(), so the roundtrip
    # isn't complete without it here either.
    frames.extend(decoder.flush())
    decoder.close()

    assert len(frames) == 5
    for f in frames:
        assert f.shape == (HEIGHT, WIDTH, 3)


def test_h264_decoder_survives_garbage_chunk():
    decoder = H264Decoder()
    # Not valid Annex-B data at all — must not raise, just yield nothing.
    assert decoder.decode(b"not h264 data") == []
    decoder.close()


def test_h264_decoder_closes_promptly_after_a_corrupt_chunk():
    """close() must not block when the last thing decoded was garbage.

    Frame-threaded decoding parks its workers on a packet it cannot parse, and
    freeing the context joins them — so decode(garbage) followed by close()
    with no flush in between used to hang the calling thread forever, taking
    the connection teardown in receiver.py with it. Run on a worker thread so
    a regression fails the suite instead of wedging the whole run.
    """
    for chunk in _encode_synthetic_h264(3):
        pass  # warm the module-level encoder path the same way a session does

    done = threading.Event()

    def close_after_garbage():
        decoder = H264Decoder()
        for chunk in _encode_synthetic_h264(3):
            decoder.decode(chunk)
        decoder.decode(b"not h264 data")
        decoder.close()  # deliberately no flush() first
        done.set()

    worker = threading.Thread(target=close_after_garbage, daemon=True)
    worker.start()
    assert done.wait(timeout=30), "H264Decoder.close() blocked after a corrupt chunk"


def test_h264_decoder_falls_back_when_hardware_decoder_cannot_open():
    """A cuvid context that only fails once it's fed a packet must not eat the
    whole stream.

    h264_cuvid is *creatable* on any PC whose FFmpeg build includes it, GPU or
    not; on a PC without NVDEC it fails inside avcodec_open2 on the first
    packet, raising the same FFmpegError type decode() swallows for corrupt
    chunks. That combination silently decoded zero frames for an entire
    session — a black virtual camera and an empty log.
    """
    chunks = _encode_synthetic_h264(3)

    class DeadHardwareContext:
        def decode(self, packet):
            raise av.error.PermissionError(1, 'avcodec_open2("h264_cuvid", {})')

    decoder = H264Decoder()
    decoder.backend = "h264_cuvid (NVDEC)"
    decoder._ctx = DeadHardwareContext()

    frames = []
    for chunk in chunks:
        frames.extend(decoder.decode(chunk))
    frames.extend(decoder.flush())
    decoder.close()

    assert "software" in decoder.backend
    assert len(frames) == 3


def test_h264_decoder_does_not_fall_back_twice_on_a_corrupt_stream():
    """The fallback is latched: once on the software decoder, a bad chunk is
    just a bad chunk again, not a reason to keep rebuilding the context."""
    decoder = H264Decoder()
    decoder.backend = "h264 (software)"
    assert decoder._fall_back_to_software(av.error.PermissionError(1, "x")) is False
    decoder.close()


def test_handle_connection_routes_h264_codec_to_decoder():
    server_sock, client_sock = socket.socketpair()
    sink = NullSink()
    chunks = _encode_synthetic_h264(4)

    hello = Hello(width=WIDTH, height=HEIGHT, fps=30, quality="1080p60", watermark=False, device_name="test", codec="h264")

    def send_side():
        send_hello(client_sock, hello)
        for chunk in chunks:
            send_frame(client_sock, chunk)
            # Two things need real pacing to avoid collapsing this burst
            # down to fewer than 4 displayed frames, same "always show the
            # freshest" policy applied at two different stages: (1)
            # handle_connection() skips displaying a frame if another one
            # is already fully buffered behind it (see its is_stale doc),
            # and (2) _SinkWriter's single-slot mailbox drops a frame if a
            # newer one is submit()'d before its own thread picks the old
            # one up (see its doc). A generous gap gives both stages time
            # to actually drain between chunks instead of racing.
            time.sleep(0.15)
        client_sock.close()

    sender_thread = threading.Thread(target=send_side)
    sender_thread.start()

    stats = handle_connection(server_sock, sink)
    sender_thread.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == len(chunks)
    assert len(sink.frames) == 4
    assert sink.closed is True
    for f in sink.frames:
        assert f.shape == (HEIGHT, WIDTH, 3)


def test_handle_connection_still_uses_jpeg_path_by_default():
    """Hello.codec defaults to "jpeg" — old senders (and the PC demo path)
    that never set it must keep working exactly as before."""
    import struct

    from pc_receiver.demo_sender import build_synthetic_frame, encode_jpeg

    server_sock, client_sock = socket.socketpair()
    sink = NullSink()
    hello = Hello(width=WIDTH, height=HEIGHT, fps=30, quality="1080p60", watermark=False, device_name="test")
    assert hello.codec == "jpeg"

    def send_side():
        send_hello(client_sock, hello)
        good = encode_jpeg(build_synthetic_frame(WIDTH, HEIGHT, 0), 80)
        send_frame(client_sock, good)
        client_sock.close()

    sender_thread = threading.Thread(target=send_side)
    sender_thread.start()

    stats = handle_connection(server_sock, sink)
    sender_thread.join(timeout=5)
    server_sock.close()

    assert stats.frames_received == 1
    assert len(sink.frames) == 1
