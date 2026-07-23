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
    """

    def __init__(self) -> None:
        self.backend, self._ctx = self._create_context()
        log.info("H264Decoder using backend=%s", self.backend)
        self._decode_calls = 0
        self._decode_time_total = 0.0
        # Split from decode time so a slow session can be pinned on the
        # actual decoder vs. the to_ndarray/cv2 pixel-format conversion —
        # see receiver.py's periodic pipeline-stage log, which reports both.
        self._convert_time_total = 0.0
        self._frames_out_total = 0

    @staticmethod
    def _create_context() -> tuple[str, "av.CodecContext"]:
        """Prefer NVDEC (h264_cuvid) over the software decoder.

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
        try:
            return "h264_cuvid (NVDEC)", av.CodecContext.create("h264_cuvid", "r")
        except Exception as e:
            log.warning("h264_cuvid unavailable (%s), falling back to software h264 decode", e)
            ctx = av.CodecContext.create("h264", "r")
            # Frame-level threading for the software path — the default is a
            # single thread, which leaves most of the CPU idle exactly when
            # this fallback needs it most (no NVDEC = decode competes with
            # everything else on the CPU).
            ctx.thread_type = "AUTO"
            ctx.thread_count = 0
            return "h264 (software)", ctx

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
        t0 = time.monotonic()
        try:
            packet = av.Packet(data)
            frames = self._ctx.decode(packet)
        except av.error.FFmpegError:
            return []
        t1 = time.monotonic()
        result = [_to_rgb(frame) for frame in frames]
        t2 = time.monotonic()

        self._decode_calls += 1
        self._decode_time_total += t1 - t0
        self._convert_time_total += t2 - t1
        self._frames_out_total += len(result)
        return result

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
            frames = self._ctx.decode(None)
        except av.error.FFmpegError:
            return []
        return [_to_rgb(frame) for frame in frames]

    def close(self) -> None:
        try:
            self._ctx.close()
        except Exception:
            pass
