"""Decodes the Annex-B H.264 stream produced by the phone's MediaCodec
encoder (see android-app H264Encoder.kt) into BGR frames the existing sinks
already know how to consume.

Used when Hello.codec == "h264" (real devices, since the CameraStreamer
switch away from per-frame JPEG). The JPEG path (decode_frame in
receiver.py) is unchanged and still used for Hello.codec == "jpeg" (the
PC-only demo sender and the existing test suite).
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import List, Optional

import av
import av.error
import cv2
import numpy as np

log = logging.getLogger("pc_receiver.h264_decoder")


@dataclass
class DecoderStageStats:
    backend: str
    decode_calls: int
    decode_time_total: float
    convert_time_total: float
    frames_out: int

    @property
    def decode_avg_ms(self) -> float:
        return (self.decode_time_total / self.decode_calls * 1000) if self.decode_calls else 0.0

    @property
    def convert_avg_ms(self) -> float:
        return (self.convert_time_total / self.decode_calls * 1000) if self.decode_calls else 0.0

# PyAV's frame.to_ndarray(format="rgb24") runs the YUV->RGB conversion through
# swscale, which is single-threaded — benchmarked on the real target PC at
# ~32ms/frame for 1080p on NVDEC output (vs 4.5ms for the decode itself!),
# making the color conversion, not decoding, the actual pipeline bottleneck
# behind the sustained 1080p60 frame drops. Extracting the frame in its
# decoder-native format instead (~memcpy, ~3ms) and converting with OpenCV
# (SIMD + multithreaded, ~3.5ms) benchmarked at 13.2ms/frame total for the
# whole decode+convert path — 2.7x faster end-to-end.
_CV2_CODE_BY_PIX_FMT = {
    "nv12": cv2.COLOR_YUV2RGB_NV12,      # what h264_cuvid (NVDEC) outputs
    "yuv420p": cv2.COLOR_YUV2RGB_I420,   # what the software decoder outputs
    "yuvj420p": cv2.COLOR_YUV2RGB_I420,  # full-range variant, same layout
}


def _to_rgb(frame: "av.VideoFrame") -> np.ndarray:
    code = _CV2_CODE_BY_PIX_FMT.get(frame.format.name)
    if code is None:
        # Unexpected pixel format — let swscale handle it rather than crash.
        return frame.to_ndarray(format="rgb24")
    return cv2.cvtColor(frame.to_ndarray(format=frame.format.name), code)


class H264Decoder:
    """One instance per connection — the decoder is stateful (SPS/PPS,
    reference frames), so it can't be shared across streams or reused after
    a disconnect.

    [output_format] picks what decode() returns:

      "rgb"    - RGB ndarrays, converting from the decoder's native format
                 (the historical behaviour, and what the preview/JPEG paths
                 still want).
      "native" - whatever the decoder itself produced, unconverted: NV12 for
                 NVDEC, yuv420p for the software fallback. [frame_format]
                 then names it so the sink can be configured to match.

    "native" exists because the conversion was costing more than everything
    else in the pipeline combined. Measured at 3840x2160: cv2 NV12->RGB is
    7.5ms/frame here, and pyvirtualcam then spends another 24ms converting
    that RGB *back* to the virtual camera's native NV12 — 31ms of pure
    round-trip, capping the pipeline at 27fps. Handing NV12 straight through
    costs 0.9ms and runs at 59.4fps with a fifth of the CPU.
    """

    def __init__(self, output_format: str = "rgb", codec: str = "h264") -> None:
        if output_format not in ("rgb", "native"):
            raise ValueError(f"unknown output_format {output_format!r}")
        self._output_format = output_format
        # "h264" or "h265"/"hevc" - whatever Hello.codec announced. The phone
        # switches to HEVC above 4K (see mimeTypeFor in H264Encoder.kt), so the
        # decoder has to follow or it decodes nothing at all.
        self._codec = "hevc" if codec in ("h265", "hevc") else "h264"
        # Only meaningful once a frame has actually come out: the native
        # format depends on which decoder backend was available.
        self.frame_format = "rgb"
        self.backend, self._ctx = self._create_context(self._codec)
        # One-shot latch for the runtime hardware->software fallback below, so
        # a genuinely corrupt stream can't send it round the loop repeatedly.
        self._fell_back = False
        log.info("H264Decoder using backend=%s", self.backend)
        self._decode_calls = 0
        self._decode_time_total = 0.0
        # Split from decode time so a slow session can be pinned on the
        # actual decoder vs. the to_ndarray/cv2 pixel-format conversion —
        # see receiver.py's periodic pipeline-stage log, which reports both.
        self._convert_time_total = 0.0
        self._frames_out_total = 0

    def _to_output(self, frame: "av.VideoFrame") -> np.ndarray:
        """One frame, in whichever format this decoder was asked for."""
        if self._output_format == "rgb":
            return _to_rgb(frame)
        # to_ndarray in the frame's own format is a plane copy, not a
        # conversion — no colour maths, no swscale.
        self.frame_format = frame.format.name
        return frame.to_ndarray(format=frame.format.name)

    @staticmethod
    def _create_context(
        codec: str = "h264", allow_hardware: bool = True
    ) -> tuple[str, "av.CodecContext"]:
        """Prefer NVDEC (h264_cuvid) over the software decoder.

        [allow_hardware] is what `_fall_back_to_software` sets to False to
        force the software path after a hardware context turned out not to
        work on this PC.

        Confirmed on-device: plain libavcodec software decode can't keep up
        with a real 4K60 stream — it's not fast enough to decode frames as
        fast as they arrive, so the backlog (and end-to-end latency) grows
        without bound, observed as ~10s of accumulating delay. h264_cuvid
        decodes on the GPU instead (near-zero CPU cost, real-time well past
        4K60 on any NVIDIA GPU with NVDEC), which is every GPU from roughly
        the last decade. Falls back to the software decoder on PCs without
        an NVIDIA GPU/driver — same decode() API either way, PyAV's
        to_ndarray() handles the pixel format conversion regardless of which
        decoder produced the frame.

        Returns (backend_name, context) — the name is logged by __init__ so
        it's visible in logs/receiver.log which path a given session actually
        took, instead of silently guessing from GPU utilization alone.
        """
        hardware = f"{codec}_cuvid"
        if allow_hardware:
            try:
                return f"{hardware} (NVDEC)", av.CodecContext.create(hardware, "r")
            except Exception as e:
                log.warning(
                    "%s unavailable (%s), falling back to software %s decode",
                    hardware, e, codec,
                )
        ctx = av.CodecContext.create(codec, "r")
        # Frame-level threading for the software path — the default is a
        # single thread, which leaves most of the CPU idle exactly when
        # this fallback needs it most (no NVDEC = decode competes with
        # everything else on the CPU).
        ctx.thread_type = "AUTO"
        ctx.thread_count = 0
        return f"{codec} (software)", ctx

    def decode(self, data: bytes) -> List[np.ndarray]:
        """Feed one Annex-B chunk (the codec-config NALs sent once up front,
        or a single frame's access unit) and return any frames that came out
        the other side as RGB ndarrays.

        RGB, not OpenCV's usual BGR: pyvirtualcam (the only real consumer —
        see sinks.VirtualCamSink) wants RGB, so producing it here means
        nobody downstream needs a second full-frame conversion pass. The
        conversion itself goes through _to_rgb (native-format extraction +
        OpenCV), NOT to_ndarray(format="rgb24") — see _CV2_CODE_BY_PIX_FMT's
        doc for the benchmark numbers behind that.

        Usually yields exactly one frame per access unit, but B-frame
        reordering (or the very first config-only chunk, which yields none)
        means the count isn't fixed — callers must handle zero or several.
        A corrupt/undecodable chunk is treated the same as a bad JPEG
        elsewhere in this codebase: skip it, don't crash the connection.
        """
        if self._ctx is None:
            return []   # closed: a late frame from a torn-down session
        t0 = time.monotonic()
        try:
            packet = av.Packet(data)
            frames = self._ctx.decode(packet)
        except av.error.FFmpegError as e:
            if self._fall_back_to_software(e):
                return self.decode(data)
            return []
        t1 = time.monotonic()
        result = [self._to_output(frame) for frame in frames]
        t2 = time.monotonic()

        self._decode_calls += 1
        self._decode_time_total += t1 - t0
        self._convert_time_total += t2 - t1
        self._frames_out_total += len(result)
        return result

    def _fall_back_to_software(self, error: Exception) -> bool:
        """Swap a hardware decoder that can't actually run for the software one.

        _create_context can only catch hardware decoders that fail to *create*.
        h264_cuvid creates fine on any PC whose FFmpeg build was compiled with
        it — including PCs with no NVIDIA GPU at all, where it instead fails
        later, inside avcodec_open2, on the first packet fed to it. That
        surfaces as an FFmpegError (EPERM, "Operation not permitted"), which is
        the same type decode() deliberately shrugs off for corrupt chunks, so
        the whole session silently decoded zero frames: a frozen black virtual
        camera, an empty receiver.log, and no way to tell it apart from the
        phone not sending anything.

        Returns True when the caller should retry the packet on the new
        context. Latched, so a genuinely undecodable stream doesn't bounce
        between backends.
        """
        if self._fell_back or "cuvid" not in self.backend:
            return False
        self._fell_back = True
        log.warning(
            "%s could not be opened on this PC (%s) — switching to software %s "
            "decode for the rest of this session",
            self.backend, error, self._codec,
        )
        self.backend, self._ctx = self._create_context(self._codec, allow_hardware=False)
        return True

    def pop_stage_stats(self) -> "DecoderStageStats":
        """Snapshot decode-only vs. convert-only time since the last call,
        then reset — receiver.py's periodic pipeline log uses this to show
        exactly which half of H264Decoder.decode is the bottleneck, instead
        of one blended number that can't tell "GPU decode is slow" apart
        from "the CPU-side pixel conversion is slow" (which is what was
        actually true at 1080p60 before the NV12+cv2 change — see _to_rgb's
        module doc)."""
        stats = DecoderStageStats(
            backend=self.backend,
            decode_calls=self._decode_calls,
            decode_time_total=self._decode_time_total,
            convert_time_total=self._convert_time_total,
            frames_out=self._frames_out_total,
        )
        self._decode_calls = 0
        self._decode_time_total = 0.0
        self._convert_time_total = 0.0
        self._frames_out_total = 0
        return stats

    def flush(self) -> List[np.ndarray]:
        """Drains whatever the decoder is still holding onto internally.

        Hardware decoders (h264_cuvid/NVDEC in particular) pipeline several
        frames deep and only emit them once fed an explicit flush/EOF signal
        (a None packet) — without this, the last few frames of every real
        session, and potentially *all* frames of a very short one (e.g. a
        handful of test frames, or the phone disconnecting almost
        immediately), never come out of decode() at all. Call this once,
        right before close().
        """
        try:
            frames = self._ctx.decode(None) if self._ctx is not None else []
        except av.error.FFmpegError:
            return []
        return [_to_rgb(frame) for frame in frames]

    def close(self) -> None:
        """Releases the decoder context.

        This used to call self._ctx.close(), which does not exist on PyAV's
        VideoCodecContext - it raised AttributeError on every single call and
        the bare `except Exception: pass` swallowed it, so close() had never
        actually released anything since it was written. Dropping the reference
        is what PyAV supports: the context frees itself, including any NVDEC
        surfaces, once nothing holds it.

        Drains the context before dropping it, even though receiver.py's
        teardown already calls flush() first. Frame-threaded decoding
        (thread_type="AUTO", see _create_context) leaves its worker threads
        parked when it is handed a packet it cannot parse - decode() returns
        no frames and raises nothing, and freeing the context then joins those
        threads and blocks forever. So one corrupt chunk followed by a close()
        with no flush between them hung the calling thread permanently, with
        the connection never torn down. Draining here makes the order callers
        happen to use irrelevant.
        """
        ctx, self._ctx = self._ctx, None
        if ctx is None:
            return
        try:
            ctx.decode(None)
        except Exception:
            # Already flushed, already at EOF, or undecodable - none of which
            # matter now. The drain is wanted for its effect on the worker
            # threads, not for the frames.
            pass
