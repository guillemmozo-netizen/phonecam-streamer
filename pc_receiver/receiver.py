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

from pc_receiver.audio_decoder import make_audio_decoder
from pc_receiver.audio_sinks import create_audio_sink
from pc_receiver.av_sync import AvSync
from pc_receiver.h264_decoder import H264Decoder
from pc_receiver.obs_sync import sync_video_settings
from pc_receiver.protocol import FrameReader, Hello, MediaKind, ProtocolError, unpack_chunk
from pc_receiver.sinks import FrameSink, create_sink

log = logging.getLogger("pc_receiver")


@dataclass(frozen=True)
class AudioOptions:
    """How this receiver should handle whatever audio a sender offers.

    Separate from the sink *kind* strings used for video because the decision
    is different in nature: video always has somewhere to go, whereas audio's
    destination depends on what the user installed (see audio_sinks' module
    doc). Defaulting to "device" means a phone that sends audio is audible
    with no extra configuration once a virtual cable exists.
    """

    sink: str = "device"
    device: Optional[str] = None
    wav_path: str = "phonecam_audio.wav"
    # Play into an ordinary output device when no virtual cable is installed.
    # Off by default because this receiver auto-starts as a background
    # service, where that means an unrequested feedback loop — see
    # audio_sinks.create_audio_sink.
    allow_speakers: bool = False
    # Whether video is delayed to line up with audio (see av_sync.py). On by
    # default, because audio that does not match the picture is the more
    # obvious defect — but it is a real cost, and worth being able to refuse:
    # the delay equals the audio path's latency, and on Windows' default MME
    # host API that measured 182ms of device latency alone on the reference
    # PC. A user who cares more about latency than lip-sync (and anyone whose
    # audio goes somewhere else entirely) is better served with this off.
    av_sync: bool = True

    @property
    def enabled(self) -> bool:
        return self.sink != "none"

# How long the Hello-triggered OBS sync waits before touching OBS. Both OBS
# crashes so far happened within ~11s of OBS finishing its module load, while
# obs-websocket was already answering; this is comfortably past that, and it
# runs on a daemon thread nobody waits for.
OBS_SETTLE_SECONDS = 20.0

# How long a connected sender may go completely silent before the receiver
# gives up on it.
#
# Without this the receive loop blocked in recv() forever. A TCP peer that
# vanishes without closing — phone suspended, Wi-Fi dropped, laptop lid shut,
# USB cable pulled at the wrong instant — leaves a half-open socket that
# never errors and never delivers, so the loop parked there permanently. And
# because the receiver accepts one connection at a time, that also meant it
# never accepted another: the phone would reconnect, get no answer, and the
# only fix was restarting the PC service.
#
# 10s is far longer than any real gap in a live stream (a 1fps session still
# sends every second, and the encoder emits config data immediately at
# startup) while still being a delay a user reads as "it dropped" rather than
# "it's broken".
STREAM_IDLE_TIMEOUT_SECONDS = 10.0


def _bind_listener(server: socket.socket, host: str, port: int) -> None:
    """Bind the stream port so that a *second* receiver cannot silently share it.

    SO_REUSEADDR is deliberately not set. On Unix it means "reuse a socket
    stuck in TIME_WAIT", which is harmless — but on Windows it means *two live
    processes may bind the same port*, and incoming connections then land on
    an arbitrary one of them.

    That is not hypothetical here. Observed during Android validation: the
    PhoneCam service's receiver (--sink virtualcam) and a manually started one
    (--sink null) were both bound to 8787 at the same time, so the phone's
    session went to whichever won the race. Any measurement taken in that state
    is worthless, and a user whose services double-started would see the stream
    "sometimes" reach OBS.

    Same reasoning and same fix as ControlServer.allow_reuse_address in
    control_server.py — the port is the mutex. The cost is that a restart
    within the TIME_WAIT window can briefly fail to bind; the caller reports
    that as a clear error instead of starting a duplicate that half works.
    """
    try:
        server.bind((host, port))
    except OSError as e:
        log.error(
            "cannot bind %s:%s (%s) — another receiver is probably already running. "
            "Refusing to start a second one, since connections would be split between them.",
            host, port, e,
        )
        raise


@dataclass
class ReceiverStats:
    frames_received: int = 0
    frames_decoded_failed: int = 0
    bytes_received: int = 0
    audio_packets_received: int = 0
    audio_frames_decoded: int = 0
    audio_packets_failed: int = 0


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
        self._send_failures = 0
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
            try:
                self._sink.send(frame, fps=fps)
            except Exception:
                # Never let a failing send kill this thread. Without this the
                # thread simply exited, submit() went on filling a mailbox
                # nobody was reading, and video froze for the rest of the
                # session — with nothing logged, because the exception died
                # with the thread. A sink that raises every frame would then
                # also flood the log, so it is reported once and then counted.
                self._send_failures += 1
                if self._send_failures == 1:
                    log.exception("sink.send() failed — video output is stalled")
                elif self._send_failures % 300 == 0:
                    log.error("sink.send() still failing (%d frames)", self._send_failures)
                continue
            if self._send_failures:
                log.info("sink.send() recovered after %d failed frame(s)", self._send_failures)
                self._send_failures = 0
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

    def __init__(
        self,
        codec: str,
        decoder: Optional[H264Decoder],
        sync: Optional[AvSync] = None,
    ) -> None:
        self._codec = codec
        self._decoder = decoder
        # Present only on audio sessions; when it is, the log gains the one
        # number that says whether A/V sync is actually working (skew) and
        # the one that says what it costs (held).
        self._sync = sync
        self._window_start = time.monotonic()
        self._received = 0
        self._decoded = 0
        self._shown = 0
        self._audio_frames = 0
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

    def on_audio(self, sample_frames: int) -> None:
        self._audio_frames += sample_frames

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

        if self._sync is not None:
            sync_stats = self._sync.stats
            stage_bits += (
                f" audio={self._audio_frames / elapsed / 1000:.1f}kHz"
                f" skew={sync_stats.last_skew_ms:+.0f}ms held={sync_stats.held_now}"
            )
            if sync_stats.released_on_timeout or sync_stats.dropped_overflow:
                stage_bits += (
                    f" sync_timeouts={sync_stats.released_on_timeout}"
                    f" sync_drops={sync_stats.dropped_overflow}"
                )

        log.info(
            "pipeline: in=%.1ffps decoded=%.1ffps shown=%.1ffps backlog_max=%d sink_send=%.1fms %s",
            in_fps, decoded_fps, shown_fps, self._backlog_max, sink_avg_ms, stage_bits,
        )

        self._window_start = time.monotonic()
        self._received = 0
        self._decoded = 0
        self._audio_frames = 0
        self._backlog_max = 0


class _AudioPipeline:
    """One connection's audio: decode -> sink, plus the playout clock video
    is paced against.

    Exists so handle_connection can treat "this sender has audio" and "this
    sender does not" as the same code path. Every method is safe to call on
    an inactive pipeline, and [playout_pts_us] then returns None, which is
    precisely the value that makes AvSync stop holding video back.

    Audio failing is never allowed to take video with it. A missing decoder,
    an unopenable device, a codec this build doesn't know: each one degrades
    to [active] being False and the session continuing as video-only, because
    a user on a call would far rather lose their microphone than their
    camera.
    """

    def __init__(self, hello: Hello, options: AudioOptions) -> None:
        self._decoder = None
        self._sink = None
        self.frames_written = 0

        if not hello.has_audio or not options.enabled:
            return
        decoder = make_audio_decoder(hello)
        if decoder is None:
            return
        sink = create_audio_sink(
            options.sink,
            sample_rate=decoder.sample_rate,
            channels=decoder.channels,
            device=options.device,
            wav_path=options.wav_path,
            allow_speakers=options.allow_speakers,
        )
        if sink is None:
            decoder.close()
            return
        self._decoder = decoder
        self._sink = sink
        log.info(
            "audio: %s %dHz %dch -> %s sink",
            hello.audio_codec, decoder.sample_rate, decoder.channels, options.sink,
        )

    @property
    def active(self) -> bool:
        return self._decoder is not None and self._sink is not None

    @property
    def packets_failed(self) -> int:
        return self._decoder.packets_failed if self._decoder is not None else 0

    def handle(self, data: bytes, pts_us: int) -> int:
        """Decode one audio message and hand it to the sink. Returns how many
        sample frames came out (0 is normal — an encoder priming, or a packet
        the decoder needs more data to complete)."""
        if not self.active:
            return 0
        samples = self._decoder.decode(data)
        if samples.shape[0] == 0:
            return 0
        self._sink.write(samples, pts_us)
        self.frames_written += samples.shape[0]
        return samples.shape[0]

    def playout_pts_us(self) -> Optional[int]:
        return self._sink.playout_pts_us() if self.active else None

    def close(self) -> None:
        if self._decoder is not None:
            # Flush before closing for the same reason the video path does:
            # a short session's last packets are still inside the decoder.
            try:
                tail = self._decoder.flush()
                if self._sink is not None and tail.shape[0]:
                    self._sink.write(tail, 0)
            except Exception:
                log.exception("flushing the audio decoder failed")
            self._decoder.close()
            self._decoder = None
        if self._sink is not None:
            self._sink.close()
            self._sink = None


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
    try:
        return conn.getpeername()[0]
    except OSError:
        return "?"


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
    audio_options: Optional[AudioOptions] = None,
) -> ReceiverStats:
    """Run the receive loop for one accepted connection.

    `max_frames` counts video frames only, so an audio session stops after the
    same amount of *picture* as a video-only one — it lets tests/smoke-runs
    stop deterministically instead of running forever, and audio packets
    arriving at their own unrelated rate must not change where that lands.
    """
    stats = stats or ReceiverStats()
    # Applies to the Hello read too, deliberately: a client that connects and
    # then says nothing would otherwise hold the receiver's single accept slot
    # open indefinitely, which anything on the LAN could do on purpose.
    conn.settimeout(STREAM_IDLE_TIMEOUT_SECONDS)
    reader = FrameReader(conn)
    try:
        hello = reader.recv_hello()
    except (ProtocolError, OSError) as e:
        # A connection that opens and closes without saying anything is a
        # port scan, a health check, or a phone that lost Wi-Fi mid-handshake
        # — all ordinary. It used to escape as a full traceback in the log
        # (confirmed by opening one such connection by hand), which means
        # anything on the LAN could fill the log with stack traces.
        log.info("client disconnected before sending a hello (%s)", type(e).__name__)
        sink.close()
        return stats

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
    options = audio_options or AudioOptions()
    audio = _AudioPipeline(hello, options)
    # Only when audio is actually being played, and only if asked for: with no
    # audio there is no clock to align to, and inserting the queue anyway
    # would add latency in exchange for nothing. A video-only session runs the
    # exact path it ran before audio existed.
    sync = AvSync() if (audio.active and options.av_sync) else None

    # The *framing* question is settled by what the sender announced, not by
    # whether audio output succeeded on this end: a sender with audio tags
    # every message whether or not this PC can play it, so a session that
    # fell back to video-only still has to strip those headers.
    tagged = hello.has_audio

    frames_skipped_stale = 0
    pipeline = _PipelineStageLog(hello.codec, h264_decoder, sync)
    sink_writer = _SinkWriter(sink, pipeline.on_sent)

    def show(frames, pts_us: int) -> None:
        """Route decoded video to the sink, through A/V sync when there is
        audio to align to. [pts_us] is the sender-clock timestamp of the
        access unit these frames came out of."""
        if sync is None:
            for frame in frames:
                sink_writer.submit(frame, hello.fps)
            return
        for frame in frames:
            sync.submit(frame, pts_us)
        release()

    def release() -> None:
        """Hand over any held video the audio clock has now reached.

        Called on audio arrival as well as video, because the audio clock
        advances whether or not new pictures show up — without that, the last
        frame before a pause would sit in the queue until the next one
        arrived to push it out.
        """
        if sync is None:
            return
        for frame in sync.due(audio.playout_pts_us()):
            sink_writer.submit(frame, hello.fps)

    try:
        while max_frames is None or stats.frames_received < max_frames:
            try:
                payload = reader.recv_message()
            except ProtocolError:
                log.info("sender disconnected")
                break
            except socket.timeout:
                # A half-open peer, not a clean disconnect. Ending the
                # connection is what frees the listener to accept the
                # phone's reconnect — see STREAM_IDLE_TIMEOUT_SECONDS.
                # Caught before OSError below: since Python 3.10 socket.timeout
                # *is* an OSError, so the order of these two is what keeps them
                # distinguishable.
                log.warning(
                    "no data from %s for %.0fs — dropping the connection so a reconnect can be accepted",
                    _peer_ip(conn), STREAM_IDLE_TIMEOUT_SECONDS,
                )
                break
            except OSError as e:
                # A reset rather than a clean shutdown — which is exactly what
                # pulling the USB cable, killing the sender, or a router
                # dropping the flow produces. This is an ordinary way for a
                # session to end, but it used to escape the receive loop
                # entirely: serve_forever logged it as a crashed connection
                # (one such ConnectionResetError sits in this machine's
                # receiver.log), and serve_once propagated it out of main()
                # and killed the process.
                log.info("sender connection reset (%s)", type(e).__name__)
                break

            stats.bytes_received += len(payload)

            if tagged:
                try:
                    kind, pts_us, payload = unpack_chunk(payload)
                except ProtocolError as e:
                    # A malformed chunk header is one bad message, not a dead
                    # connection — same treatment a corrupt JPEG has always
                    # had further down.
                    log.warning("dropping malformed chunk: %s", e)
                    continue
            else:
                kind, pts_us = MediaKind.VIDEO, 0

            if kind == MediaKind.AUDIO:
                stats.audio_packets_received += 1
                sample_frames = audio.handle(payload, pts_us)
                stats.audio_frames_decoded += sample_frames
                pipeline.on_audio(sample_frames)
                # Audio is never skipped for backlog the way video is: a
                # dropped packet is an audible click, and unlike a dropped
                # frame it cannot be made up for by the next one. The audio
                # sink bounds its own latency instead, by dropping from its
                # queue where it can do so in whole milliseconds.
                release()
                pipeline.maybe_log()
                continue

            stats.frames_received += 1
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
            backlog = reader.buffered_message_count(MediaKind.VIDEO if tagged else None)
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
                show(decoded, pts_us)
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

            show([frame], pts_us)
            pipeline.maybe_log()
    finally:
        # Order matters: the writer thread is stopped *before* anything
        # drains, because flush() can return several frames at once (NVDEC
        # pipelines multiple frames deep and releases them together), and
        # pushing them through _SinkWriter's single-slot mailbox with no
        # pacing would silently drop all but the last (confirmed: it broke
        # test_handle_connection_routes_h264_codec_to_decoder, 1 of 4 frames
        # arriving). These are the session's final frames, not live ones, so
        # they go straight to the sink — and only once the writer thread is
        # guaranteed stopped, so it cannot be inside sink.send() concurrently.
        #
        # Every step below is individually guarded, and that is the whole
        # point of the shape of this block. It used to be a plain sequence,
        # so the first thing to raise skipped everything after it — and the
        # thing that raised, in 13 recorded sessions, was the flush's
        # sink.send(). The result was that a failure in the *least* important
        # step (a handful of trailing frames) leaked the decoder context with
        # its NVDEC surfaces, the audio device, and the virtual camera, on
        # exactly the path that was already going wrong. Releasing resources
        # must not be conditional on anything else having succeeded.
        stats.audio_packets_failed = audio.packets_failed
        for label, step in (
            ("stale-frame count", lambda: _log_skipped(frames_skipped_stale)),
            ("sink writer", sink_writer.close),
            # Video still held for A/V sync goes out before the decoder's own
            # flush — it is older, and the audio clock it was waiting on is
            # not coming back. Direct sends because the mailbox is gone by
            # now and these frames need no pacing.
            ("A/V sync drain", lambda: _drain_to(sync, sink, hello.fps)),
            ("decoder flush", lambda: _flush_to(h264_decoder, sink, hello.fps)),
            ("decoder close", lambda: h264_decoder and h264_decoder.close()),
            ("audio close", audio.close),
            ("sink close", sink.close),
        ):
            try:
                step()
            except Exception:
                log.exception("error closing %s — continuing teardown", label)

    return stats


def _log_skipped(count: int) -> None:
    if count:
        log.info("skipped %d stale/backlogged frame(s) to stay caught up with real time", count)


def _drain_to(sync: Optional[AvSync], sink: FrameSink, fps: int) -> None:
    if sync is None:
        return
    for frame in sync.drain():
        sink.send(frame, fps=fps)


def _flush_to(decoder: Optional[H264Decoder], sink: FrameSink, fps: int) -> None:
    if decoder is None:
        return
    for frame in decoder.flush():
        sink.send(frame, fps=fps)


def serve_once(
    port: int,
    sink_kind: str,
    host: str = "127.0.0.1",
    max_frames: Optional[int] = None,
    audio_options: Optional[AudioOptions] = None,
) -> ReceiverStats:
    """Listen for a single incoming connection, handle it, and return stats.

    Binding to 0.0.0.0 (the default when launched via control_server/
    start_services) covers both USB — `adb reverse` tunnels a phone-local
    port to this PC's loopback — and Wi-Fi, where the phone connects to the
    PC's real LAN address on the same socket.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        _bind_listener(server, host, port)
        # Backlog, not concurrency: this receiver still handles exactly one
        # connection at a time. The queue is what stops a burst of connection
        # attempts being *refused* outright — with listen(1) a couple of stray
        # connects (a port scan, or the phone's own supervisor retrying while
        # a stale session is still timing out) filled the queue, and the real
        # phone then got ECONNREFUSED rather than waiting its turn. Measured:
        # 10 rapid connects, only 2 accepted, the port unreachable to anything
        # else until the idle timeout expired.
        server.listen(8)
        log.info("waiting for stream on %s:%s ...", host, port)
        conn, addr = server.accept()
        log.info("connected: %s", addr)
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with conn:
            sink = create_sink(sink_kind)
            return handle_connection(
                conn, sink, max_frames=max_frames, audio_options=audio_options
            )


def serve_forever(
    port: int,
    sink_kind: str,
    host: str = "127.0.0.1",
    audio_options: Optional[AudioOptions] = None,
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
        _bind_listener(server, host, port)
        # Backlog, not concurrency: this receiver still handles exactly one
        # connection at a time. The queue is what stops a burst of connection
        # attempts being *refused* outright — with listen(1) a couple of stray
        # connects (a port scan, or the phone's own supervisor retrying while
        # a stale session is still timing out) filled the queue, and the real
        # phone then got ECONNREFUSED rather than waiting its turn. Measured:
        # 10 rapid connects, only 2 accepted, the port unreachable to anything
        # else until the idle timeout expired.
        server.listen(8)
        log.info("waiting for stream on %s:%s ...", host, port)
        while True:
            conn, addr = server.accept()
            log.info("connected: %s", addr)
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                with conn:
                    sink = create_sink(sink_kind)
                    stats = handle_connection(conn, sink, audio_options=audio_options)
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
        "--audio-sink",
        choices=["device", "wav", "null", "none"],
        default="device",
        help="where the phone's audio goes: device (a virtual audio cable if one is "
             "installed, see docs/AUDIO.md), wav (record to a file), null (discard), "
             "none (ignore audio entirely). Ignored when the sender has no audio.",
    )
    parser.add_argument(
        "--audio-device",
        default=None,
        help="substring of the output device name to play into; default auto-detects a virtual cable",
    )
    parser.add_argument("--audio-wav", default="phonecam_audio.wav", help="output path for --audio-sink wav")
    parser.add_argument(
        "--audio-speakers",
        action="store_true",
        help="play audio out of an ordinary output device when no virtual cable is installed. "
             "Off by default: the phone's mic coming out of this PC's speakers is a feedback "
             "loop, not a microphone",
    )
    parser.add_argument(
        "--no-av-sync",
        action="store_true",
        help="don't delay video to match audio. Lowest video latency, at the cost of lip-sync "
             "being off by however long the audio path takes",
    )
    parser.add_argument(
        "--list-audio-devices",
        action="store_true",
        help="print the available audio output devices and exit",
    )
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.list_audio_devices:
        from pc_receiver.audio_sinks import list_output_devices

        for device in list_output_devices():
            print(f"{device['index']:>3}  {device['name']}")
        return

    audio_options = AudioOptions(
        sink=args.audio_sink,
        device=args.audio_device,
        wav_path=args.audio_wav,
        allow_speakers=args.audio_speakers,
        av_sync=not args.no_av_sync,
    )

    if args.serve_forever:
        serve_forever(args.port, args.sink, args.host, audio_options)
    else:
        stats = serve_once(args.port, args.sink, args.host, args.max_frames, audio_options)
        log.info("done: %s", stats)


if __name__ == "__main__":
    main()
