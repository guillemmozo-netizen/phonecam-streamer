"""PC-side receiver: accepts one streaming connection and forwards decoded
frames to a virtual camera (so Zoom/Meet/OBS/etc. can pick up the phone as a
webcam).

Real (USB) usage:
    adb reverse tcp:8787 tcp:8787
    python -m pc_receiver.receiver --port 8787 --sink virtualcam

Local demo (no phone required, uses the PC's own webcam or a synthetic
pattern as the "phone"):
    python -m pc_receiver.receiver --port 8787 --sink preview
    python -m pc_receiver.demo_sender --port 8787
"""

from __future__ import annotations

import argparse
import logging
import os
import secrets
import socket
import threading
import time
from dataclasses import dataclass
from typing import Callable, Optional

import cv2
import numpy as np

from pc_receiver.audio_sink import AacDecoder, AudioOutput, list_output_devices
from pc_receiver.h264_decoder import H264Decoder
from pc_receiver.obs_sync import sync_video_settings
from pc_receiver.protocol import FrameReader, MessageType, ProtocolError, split_typed_message
from pc_receiver.sinks import FrameSink, create_sink

log = logging.getLogger("pc_receiver")

# How long the Hello-triggered OBS sync waits before touching OBS. Both OBS
# crashes so far happened within ~11s of OBS finishing its module load, while
# obs-websocket was already answering; this is comfortably past that, and it
# runs on a daemon thread nobody waits for.
OBS_SETTLE_SECONDS = 20.0


@dataclass
class ReceiverStats:
    frames_received: int = 0
    frames_decoded_failed: int = 0
    bytes_received: int = 0
    # Counted separately from frames_received: audio messages are not frames,
    # and folding them in would make the frame count (and every fps figure
    # derived from it) wrong the moment audio is enabled.
    audio_messages_received: int = 0


class _SinkWriter:
    """Runs sink.send() on its own thread so a slow/contended send() can't
    block the receive+decode loop from reading the next frame off the
    socket.

    Isolated benchmarking on the real target PC found cam.send() alone costs
    ~34ms at 4K (fits a 30fps budget easily), but the *same* call measured
    inside the live receive loop was 78-375ms — the only difference is that
    in the live loop it runs on the same thread as the NVDEC decode() call
    and the OpenCV color conversion, all serialized by the GIL and
    contending for the same CPU/GPU driver. Moving it off that thread is the
    direct fix for that contention, not a guess.

    Single-slot mailbox, not a queue: if the writer thread is still busy
    with an old frame when a new one arrives, the new one just overwrites
    the slot — the same "always show the freshest, never a backlog of
    stale frames" policy already used for the network-side backlog skip
    (see handle_connection's is_stale doc), just applied one stage later.
    """

    def __init__(self, sink: FrameSink, on_sent: Callable[[float], None]) -> None:
        self._sink = sink
        self._on_sent = on_sent
        self._cond = threading.Condition()
        self._pending: Optional[tuple[np.ndarray, int]] = None
        self._stopped = False
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def submit(self, frame: np.ndarray, fps: int) -> None:
        with self._cond:
            self._pending = (frame, fps)
            self._cond.notify()

    def _run(self) -> None:
        while True:
            with self._cond:
                while self._pending is None and not self._stopped:
                    self._cond.wait()
                if self._stopped and self._pending is None:
                    return
                frame, fps = self._pending
                self._pending = None
            start = time.monotonic()
            self._sink.send(frame, fps=fps)
            self._on_sent(time.monotonic() - start)

    def close(self) -> None:
        """Signals the writer thread to stop and waits for it — does NOT
        call sink.close() itself, since sink.close() must run after this
        thread has fully stopped touching the sink (handle_connection's
        finally block does that ordering)."""
        with self._cond:
            self._stopped = True
            self._cond.notify()
        self._thread.join(timeout=5)


class _PipelineStageLog:
    """Periodic (~2s) per-stage throughput log for one connection.

    Added to pin down exactly which pipeline stage causes frame backlog at
    higher resolutions (4K) instead of guessing from a single blended
    number. "decoded" vs "shown" diverging from "in" (and backlog_max
    staying high) points at the decode+convert stage; "shown" tracking
    "decoded" but both trailing "in" points at the network/socket stage
    instead. decode/convert/backend come straight from H264Decoder — see
    its pop_stage_stats doc — so this also directly answers "is NVDEC
    actually active" (backend=h264_cuvid vs h264 (software)) instead of
    inferring it from GPU%, which doesn't count NVDEC's fixed-function
    decode block anyway.
    """

    WINDOW_SECONDS = 2.0

    def __init__(self, codec: str, decoder: Optional[H264Decoder]) -> None:
        self._codec = codec
        self._decoder = decoder
        self._window_start = time.monotonic()
        self._received = 0
        self._decoded = 0
        self._shown = 0
        self._backlog_max = 0
        self._sink_time_total = 0.0
        self._sink_calls = 0
        # sink send/shown now happen on _SinkWriter's own thread (see its
        # doc), so these two counters need a lock — everything else here is
        # still only ever touched from the single receive-loop thread.
        self._sink_lock = threading.Lock()

    def on_received(self) -> None:
        self._received += 1

    def on_backlog_sample(self, backlog: int) -> None:
        self._backlog_max = max(self._backlog_max, backlog)

    def on_decoded(self, count: int) -> None:
        self._decoded += count

    def on_sent(self, duration_seconds: float) -> None:
        with self._sink_lock:
            self._shown += 1
            self._sink_time_total += duration_seconds
            self._sink_calls += 1

    def maybe_log(self) -> None:
        elapsed = time.monotonic() - self._window_start
        if elapsed < self.WINDOW_SECONDS:
            return

        with self._sink_lock:
            shown = self._shown
            sink_time_total = self._sink_time_total
            sink_calls = self._sink_calls
            self._shown = 0
            self._sink_time_total = 0.0
            self._sink_calls = 0

        in_fps = self._received / elapsed
        decoded_fps = self._decoded / elapsed
        shown_fps = shown / elapsed
        sink_avg_ms = (sink_time_total / sink_calls * 1000) if sink_calls else 0.0

        stage_bits = f"codec={self._codec}"
        if self._decoder is not None:
            s = self._decoder.pop_stage_stats()
            stage_bits = f"backend={s.backend} decode={s.decode_avg_ms:.1f}ms convert={s.convert_avg_ms:.1f}ms"

        log.info(
            "pipeline: in=%.1ffps decoded=%.1ffps shown=%.1ffps backlog_max=%d sink_send=%.1fms %s",
            in_fps, decoded_fps, shown_fps, self._backlog_max, sink_avg_ms, stage_bits,
        )

        self._window_start = time.monotonic()
        self._received = 0
        self._decoded = 0
        self._backlog_max = 0


def decode_frame(jpeg_bytes: bytes) -> Optional[np.ndarray]:
    """Decode a JPEG payload into an RGB frame, or None if it's corrupt.

    cv2.imdecode always hands back BGR — converted here so this path (still
    used by the PC-only demo sender and older senders that predate H.264)
    matches what H264Decoder.decode now produces, since sinks.py no longer
    does any per-sink color conversion of its own.

    A corrupt/truncated frame should never crash the receiver — a dropped
    frame is fine, a dropped connection is not.
    """
    if not jpeg_bytes:
        return None
    array = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    frame = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if frame is None:
        return None
    return cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)


def _peer_ip(conn: socket.socket) -> str:
    """The peer's address, or "?" when the socket has none to report.

    AF_UNIX sockets have no address at all — getpeername() hands back an empty
    string rather than the (host, port) tuple AF_INET returns — so indexing the
    result blindly raised IndexError instead of yielding an address. That is
    what socket.socketpair() gives on POSIX, which is why the receiver's
    end-to-end tests only passed on Windows (where socketpair is emulated over
    AF_INET loopback). A Unix domain socket cannot be connected across a
    network, so its peer is local by construction and maps to loopback.
    """
    if conn.family == getattr(socket, "AF_UNIX", object()):
        return "127.0.0.1"
    try:
        peer = conn.getpeername()
    except OSError:
        return "?"
    return str(peer[0]) if isinstance(peer, tuple) and peer else "?"


def _load_control_token() -> str:
    """The token control_server generates. Read per connection rather than
    cached so rotating it doesn't need a receiver restart."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".control_token")
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def _peer_is_authorised(conn: socket.socket, supplied: str) -> bool:
    if _peer_ip(conn) in ("127.0.0.1", "::1"):
        return True
    token = _load_control_token()
    if not token:
        # No token on disk means the PC never generated one; refusing every
        # remote sender is the safe reading, and USB still works.
        return False
    return secrets.compare_digest(supplied or "", token)


def handle_connection(
    conn: socket.socket,
    sink: FrameSink,
    max_frames: Optional[int] = None,
    stats: Optional[ReceiverStats] = None,
    audio_device: Optional[str] = None,
) -> ReceiverStats:
    """Run the receive loop for one accepted connection.

    `max_frames` lets tests/smoke-runs stop deterministically instead of
    running forever.
    """
    stats = stats or ReceiverStats()
    reader = FrameReader(conn)
    hello = reader.recv_hello()

    # Same trust model as control_server: loopback (the USB tunnel, which
    # already required physical access and an authorised adb key) is exempt;
    # anything arriving over the network has to present the PC's token. Without
    # this the video socket was open to the whole LAN - anyone could connect and
    # push frames into the user's virtual camera, or occupy the port so the real
    # phone couldn't.
    if not _peer_is_authorised(conn, hello.auth_token):
        log.warning("rejected unauthorised connection from %s", _peer_ip(conn))
        raise ProtocolError("unauthorised sender")
    log.info(
        "stream started: %sx%s@%sfps quality=%s watermark=%s device=%s codec=%s",
        hello.width,
        hello.height,
        hello.fps,
        hello.quality,
        hello.watermark,
        hello.device_name,
        hello.codec,
    )

    if hello.sync_obs:
        # On its own thread, never inline. This talks to OBS over a WebSocket
        # and blocks for up to its connect timeout when OBS isn't answering
        # (PC asleep, OBS closed, a firewall dropping rather than refusing) —
        # and it sits directly in front of the receive loop, so that stall
        # happens with frames already arriving. They pile up in the socket, the
        # loop then reads a backlog of >= 2 and correctly skips those frames as
        # stale: a nice-to-have setting sync was costing real frames at the
        # start of every stream. Reproduced by the two receiver tests, which
        # lost exactly one frame each for this reason.
        #
        # Nothing downstream depends on the result, so fire-and-forget is the
        # whole fix — no lock, no ordering requirement, no failure path.
        #
        # settle_seconds because this is the one caller that can fire seconds
        # after OBS launched: the phone starting a stream is exactly what makes
        # a user open OBS. obs-websocket answers before OBS's frontend is
        # ready, and mutating OBS in that window is what both crash reports
        # have in common. Nothing waits on this thread, so the delay is free.
        threading.Thread(
            target=lambda: sync_video_settings(
                hello.width, hello.height, hello.fps, hello.video_bitrate_bps,
                settle_seconds=OBS_SETTLE_SECONDS, source="hello",
            ),
            name="obs-sync",
            daemon=True,
        ).start()

    # H.264 is stateful (SPS/PPS, reference frames) and one chunk doesn't
    # necessarily map to one output frame — the very first chunk is usually
    # config-only (0 frames), and reordering can occasionally emit more than
    # one at a time. JPEG stays exactly the old one-chunk-in/one-frame-out
    # behavior, still used by the PC-only demo sender.
    # A sink that can take the decoder's own pixel format gets it unconverted;
    # everything else keeps receiving RGB exactly as before. Only the H.264
    # path can honour this — the JPEG path decodes to RGB by nature.
    sink_prefers = getattr(sink, "preferred_frame_format", "rgb")
    h264_decoder = (
        H264Decoder(output_format="native" if sink_prefers == "native" else "rgb",
                    codec=hello.codec)
        if hello.codec in ("h264", "h265", "hevc")
        else None
    )
    frames_skipped_stale = 0
    pipeline = _PipelineStageLog(hello.codec, h264_decoder)
    sink_writer = _SinkWriter(sink, pipeline.on_sent)

    # Audio only exists when the sender negotiated it in Hello, and only then
    # does the stream carry a type byte per message (see protocol.MessageType).
    # A sender with audio off produces exactly the byte stream this loop has
    # always read.
    audio_decoder = AacDecoder(hello.audio_sample_rate, hello.audio_channels) if hello.audio else None
    audio_output = (
        AudioOutput(hello.audio_sample_rate, hello.audio_channels, device=audio_device)
        if hello.audio else None
    )
    if hello.audio:
        log.info(
            "audio: %s %dHz %dch %dkbps",
            hello.audio_codec, hello.audio_sample_rate, hello.audio_channels,
            hello.audio_bitrate_bps // 1000,
        )

    try:
        while max_frames is None or stats.frames_received < max_frames:
            try:
                payload = reader.recv_message()
            except ProtocolError:
                log.info("sender disconnected")
                break

            if hello.audio:
                try:
                    message_type, payload = split_typed_message(payload)
                except ProtocolError as e:
                    # One unreadable message is not a reason to drop a working
                    # video stream, the same call the decoders make about a
                    # corrupt chunk.
                    log.warning("skipping malformed message: %s", e)
                    continue
                if message_type == MessageType.AUDIO_CONFIG:
                    audio_decoder.configure(payload)
                    continue
                if message_type == MessageType.AUDIO:
                    stats.audio_messages_received += 1
                    # Audio is never skipped for backlog the way video is: a
                    # dropped block is an audible gap, and audio is a rounding
                    # error next to video on this link anyway.
                    for block in audio_decoder.decode(payload):
                        audio_output.write(block)
                    continue

            stats.frames_received += 1
            stats.bytes_received += len(payload)
            pipeline.on_received()

            # If the socket already has a real backlog behind this frame,
            # we've fallen behind real time — a confirmed cause of the
            # several-second creeping delay reported after switching to
            # 1080p60: nothing in this loop used to skip ahead, so any
            # transient slowdown built up a backlog that then never caught
            # back up, since steady-state throughput only ever matched
            # arrival rate. Skipping the display (not the decode — H.264
            # still needs every frame for its reference chain) of
            # backlogged frames caps the delay instead of letting it
            # compound.
            #
            # ">= 2", not ">= 1": the phone's MediaCodec drain pattern
            # routinely delivers chunks in pairs (encode() often returns 0
            # chunks then 2 — the codec pipelines a frame deep), so exactly
            # one message waiting is normal cadence, not backlog. Treating
            # it as backlog made this skip ~40% of all frames of a
            # perfectly-on-time stream (confirmed on-device: "skipped 202"
            # of 489 received), which looked like a 10fps slideshow.
            backlog = reader.buffered_message_count()
            is_stale = backlog >= 2
            pipeline.on_backlog_sample(backlog)

            if h264_decoder is not None:
                decoded = h264_decoder.decode(payload)
                pipeline.on_decoded(len(decoded))
                if decoded:
                    # The decoder only knows its native format once a frame has
                    # actually come out of it, so the sink is told here rather
                    # than at construction. Idempotent after the first call.
                    set_format = getattr(sink, "set_frame_format", None)
                    if set_format is not None:
                        set_format(h264_decoder.frame_format)
                if is_stale:
                    frames_skipped_stale += len(decoded)
                    pipeline.maybe_log()
                    continue
                for frame in decoded:
                    sink_writer.submit(frame, hello.fps)
                pipeline.maybe_log()
                continue

            if is_stale:
                pipeline.maybe_log()
                frames_skipped_stale += 1
                continue

            frame = decode_frame(payload)
            pipeline.on_decoded(0 if frame is None else 1)
            if frame is None:
                stats.frames_decoded_failed += 1
                pipeline.maybe_log()
                continue

            sink_writer.submit(frame, hello.fps)
            pipeline.maybe_log()
    finally:
        if frames_skipped_stale:
            log.info("skipped %d stale/backlogged frame(s) to stay caught up with real time", frames_skipped_stale)
        # Stop the writer thread *before* draining flush() — flush() can
        # return several frames at once (NVDEC pipelines multiple frames
        # deep and only releases them together on flush, see its doc), and
        # submitting them all through _SinkWriter's single-slot mailbox
        # with no pacing between them would silently drop all but the last
        # one (confirmed: broke test_handle_connection_routes_h264_codec_
        # to_decoder — only 1 of 4 frames arrived). These are the session's
        # final frames, not live ones, so there's no "stay caught up with
        # real time" reason to drop any of them — send them directly, and
        # only once the writer thread is guaranteed stopped so it can't be
        # calling sink.send() concurrently with this.
        sink_writer.close()
        if audio_decoder is not None:
            for block in audio_decoder.flush():
                audio_output.write(block)
            audio_decoder.close()
        if audio_output is not None:
            audio_output.close()
        if h264_decoder is not None:
            for frame in h264_decoder.flush():
                sink.send(frame, fps=hello.fps)
            h264_decoder.close()
        sink.close()

    return stats


def serve_once(
    port: int,
    sink_kind: str,
    host: str = "127.0.0.1",
    max_frames: Optional[int] = None,
    audio_device: Optional[str] = None,
) -> ReceiverStats:
    """Listen for a single incoming connection, handle it, and return stats.

    Binding to 0.0.0.0 (the default when launched via control_server/
    start_services) covers both USB — `adb reverse` tunnels a phone-local
    port to this PC's loopback — and Wi-Fi, where the phone connects to the
    PC's real LAN address on the same socket.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        log.info("waiting for stream on %s:%s ...", host, port)
        conn, addr = server.accept()
        log.info("connected: %s", addr)
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with conn:
            sink = create_sink(sink_kind)
            return handle_connection(conn, sink, max_frames=max_frames, audio_device=audio_device)


def serve_forever(
    port: int,
    sink_kind: str,
    host: str = "127.0.0.1",
    audio_device: Optional[str] = None,
) -> None:
    """Bind once and keep accepting connections indefinitely.

    The previous --serve-forever loop called serve_once() in a cycle, which
    tears down and rebinds the listening socket for every single connection.
    Worse, create_sink() (e.g. opening the OBS Virtual Camera) could raise —
    most commonly because OBS isn't installed or its Virtual Camera was never
    started — and that exception propagated all the way out of main(),
    killing the whole process. control_server then respawned it, the phone's
    connect-retry logic reconnected within a couple seconds, sink creation
    failed again, and the cycle repeated indefinitely (visible as a rapid
    "started receiver (pid ...)" loop in the control_server log). Binding
    once and isolating each connection's errors here means a single bad
    connection just gets logged, not a process crash.

    handle_connection runs *inline here*, on this one thread, for every
    connection the process ever serves. That is load-bearing, not incidental:
    the first decode on any given OS thread makes FFmpeg allocate a semaphore
    and a waitable timer that are never released when that thread dies.
    Measured on this PC, 30 sessions handled by this loop cost +2 handles in
    total (the one-off allocation), while the same 30 sessions each given their
    own thread cost +60 - exactly the "+2 handles per session, growing
    linearly" that was chased as a receiver leak and is in fact per *thread*.
    Moving to a thread-per-connection design to serve several phones at once
    would reintroduce it for real, and would need the decode to live on a
    long-lived worker instead.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        log.info("waiting for stream on %s:%s ...", host, port)
        while True:
            conn, addr = server.accept()
            log.info("connected: %s", addr)
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                with conn:
                    sink = create_sink(sink_kind)
                    stats = handle_connection(conn, sink, audio_device=audio_device)
                log.info("connection closed: %s", stats)
            except Exception:
                log.exception("connection from %s failed", addr)
            log.info("waiting for stream on %s:%s ...", host, port)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument(
        "--sink",
        choices=["virtualcam", "preview", "null"],
        default="virtualcam",
        help="virtualcam requires OBS Virtual Camera (or v4l2loopback) installed",
    )
    parser.add_argument("--max-frames", type=int, default=None)
    parser.add_argument("--serve-forever", action="store_true", help="accept new connections in a loop")
    parser.add_argument(
        "--audio-device",
        default=None,
        help=(
            "output device for the phone's microphone: an index, an exact name, or a "
            "unique substring (e.g. 'cable' for VB-CABLE, which makes the phone mic "
            "selectable as an input in Zoom/Meet/OBS). Omit for the system default. "
            "Only used when the phone has audio enabled; run --list-audio-devices to see them."
        ),
    )
    parser.add_argument(
        "--list-audio-devices",
        action="store_true",
        help="print the PC's audio output devices and exit",
    )
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.list_audio_devices:
        devices = list_output_devices()
        if not devices:
            print("no audio output devices found (is PortAudio/sounddevice installed?)")
        for device in devices:
            marker = " (system default)" if device["default"] else ""
            print(f"  [{device['index']}] {device['name']}  {device['channels']}ch{marker}")
        return

    if args.serve_forever:
        serve_forever(args.port, args.sink, args.host, audio_device=args.audio_device)
    else:
        stats = serve_once(
            args.port, args.sink, args.host, args.max_frames, audio_device=args.audio_device
        )
        log.info("done: %s", stats)


if __name__ == "__main__":
    main()
