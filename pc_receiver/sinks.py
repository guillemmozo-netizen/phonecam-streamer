"""Output sinks for decoded video frames.

Kept separate from socket/decoding logic so each sink can be unit tested
without a real virtual camera driver (OBS Virtual Camera on Windows) or a
display attached.
"""

from __future__ import annotations

from collections import deque
from typing import Deque, Protocol

import numpy as np


class FrameSink(Protocol):
    def send(self, frame_rgb: np.ndarray, fps: int) -> None: ...

    def close(self) -> None: ...


# A sink may additionally declare:
#
#   preferred_frame_format = "native"   -> receive frames in the decoder's own
#                                          pixel format instead of RGB
#   set_frame_format(name)              -> told which format that turned out to be
#
# Both are optional and looked up with getattr, so a sink (or a test double)
# that doesn't care keeps working unchanged and keeps receiving RGB.


# How many recent frames a null sink keeps. Enough for any assertion a test
# makes about frame contents, small enough that holding them costs nothing
# that matters (256 x 1080p RGB is ~1.6GB in the worst case, which is why the
# cap is on bytes as well — see _RETAINED_BYTES).
_RETAINED_FRAMES = 256
_RETAINED_BYTES = 64 * 1024 * 1024


class NullSink:
    """Discards frames, retaining only the most recent few for inspection.

    Written as a test double, but it is also `--sink null` on the command
    line — which is how it ended up being the receiver's fastest memory leak.
    It used to append every frame it was ever given: at 1080p RGB that is
    6MB a frame, so a null-sink session grew by ~186MB *per second* and a
    long one simply died. A soak run caught this exact shape in the audio
    equivalent within two minutes.

    Bounded retention keeps every existing test working — they assert on a
    handful of frames — while making the sink genuinely constant-memory.
    """

    def __init__(self) -> None:
        self.frames: Deque[np.ndarray] = deque(maxlen=_RETAINED_FRAMES)
        self.total_frames = 0
        self.closed = False
        self._retained_bytes = 0

    def send(self, frame_rgb: np.ndarray, fps: int) -> None:
        self.total_frames += 1
        # A second cap in bytes, because maxlen alone bounds the *count* and
        # frame size varies by three orders of magnitude between a 64x48 test
        # frame and 4K NV12.
        if self._retained_bytes + frame_rgb.nbytes > _RETAINED_BYTES:
            self.frames.clear()
            self._retained_bytes = 0
        if len(self.frames) == self.frames.maxlen and self.frames:
            self._retained_bytes -= self.frames[0].nbytes
        self.frames.append(frame_rgb)
        self._retained_bytes += frame_rgb.nbytes

    def close(self) -> None:
        self.closed = True


class PreviewWindowSink:
    """Shows the stream in an OpenCV window. For manual/local testing only.

    OpenCV's imshow (like the rest of OpenCV) expects BGR — everywhere else
    in this module now deals in RGB (see h264_decoder.H264Decoder.decode's
    doc for why), so this is the one place that still needs a conversion,
    paid only by this debug-only path instead of the real virtualcam one.
    """

    def __init__(self, window_name: str = "FrameCast - Preview") -> None:
        self._window_name = window_name
        self._opened = False

    def send(self, frame_rgb: np.ndarray, fps: int) -> None:
        import cv2

        cv2.imshow(self._window_name, cv2.cvtColor(frame_rgb, cv2.COLOR_RGB2BGR))
        self._opened = True
        cv2.waitKey(1)

    def close(self) -> None:
        if self._opened:
            import cv2

            cv2.destroyWindow(self._window_name)


def _pixel_format_by_name() -> dict:
    """av pixel-format name -> pyvirtualcam PixelFormat. Imported lazily so
    merely importing this module doesn't require pyvirtualcam (the tests and
    the null/preview sinks don't)."""
    try:
        from pyvirtualcam import PixelFormat
    except ImportError:  # pragma: no cover - only on machines without the backend
        return {}
    return {
        "nv12": PixelFormat.NV12,        # what h264_cuvid (NVDEC) outputs
        "yuv420p": PixelFormat.I420,     # what the software decoder outputs
        "yuvj420p": PixelFormat.I420,
        "rgb": PixelFormat.RGB,
    }


_PIXEL_FORMAT_BY_NAME = _pixel_format_by_name()


class VirtualCamSink:
    """Feeds frames into a system virtual camera via pyvirtualcam.

    Requires a backend to be installed (OBS Virtual Camera on Windows,
    v4l2loopback on Linux). The camera is created lazily on the first frame
    because we only know the true resolution once the stream announces it.
    """

    # The virtual camera's own native format is NV12, which is also what NVDEC
    # produces — so asking for native frames removes a conversion at BOTH ends
    # (see H264Decoder's output_format doc for the measurements).
    preferred_frame_format = "native"

    def __init__(self) -> None:
        self._cam = None
        self._frame_format = "rgb"

    def set_frame_format(self, frame_format: str) -> None:
        """Tells this sink which pixel format frames will arrive in. The camera
        can't change format once created, so a change closes it and the next
        send() re-creates it — which in practice happens at most once, on the
        first decoded frame of a session."""
        if frame_format == self._frame_format:
            return
        self._frame_format = frame_format
        if self._cam is not None:
            self._cam.close()
            self._cam = None

    def send(self, frame_rgb: np.ndarray, fps: int) -> None:
        import pyvirtualcam
        from pyvirtualcam import PixelFormat

        pixel_format = _PIXEL_FORMAT_BY_NAME.get(self._frame_format, PixelFormat.RGB)
        if pixel_format in (PixelFormat.NV12, PixelFormat.I420):
            # Planar 4:2:0 arrives as one (height * 3/2, width) array: the luma
            # plane with the half-height chroma plane stacked underneath, so the
            # picture height is two thirds of the array's.
            height = frame_rgb.shape[0] * 2 // 3
            width = frame_rgb.shape[1]
        else:
            height, width = frame_rgb.shape[:2]

        if self._cam is None or self._cam.width != width or self._cam.height != height:
            if self._cam is not None:
                self._cam.close()
            try:
                self._cam = pyvirtualcam.Camera(
                    width=width, height=height, fps=fps, fmt=pixel_format,
                )
            except Exception as e:
                # pyvirtualcam's own message says only "virtual camera output
                # could not be started", which is true of a dozen different
                # causes and names none of them. The size is the one fact that
                # turns it into something actionable — and measured on the
                # reference PC, asking for a size the OBS virtual camera
                # cannot start (7680x4320) leaves it unable to start at ANY
                # size, including ones that worked a minute earlier, until OBS
                # is restarted. Someone reading this line at 2am should not
                # have to rediscover that.
                raise RuntimeError(
                    f"the virtual camera could not be started at {width}x{height} "
                    f"({pixel_format}). If this size is unusually large, that is the "
                    f"likely reason; note that a refused size can leave OBS's virtual "
                    f"camera broken for every size until OBS is restarted. "
                    f"Original error: {e}"
                ) from e

        # No cv2.cvtColor here — the decoder now hands us RGB directly (see
        # H264Decoder.decode's doc), which is exactly what pyvirtualcam
        # wants, so there's nothing left to convert.
        self._cam.send(frame_rgb)
        # No sleep_until_next_frame() here on purpose: that call is meant for
        # a producer that generates frames synthetically and needs to pace
        # itself to `fps` (pyvirtualcam's own demo does exactly that). Our
        # frames already arrive paced by the real source — the phone's camera
        # and the network — so this was a second, independent clock racing
        # the first one. Any network/decode jitter that put a frame even
        # slightly behind schedule was never recovered (nothing here skips
        # ahead to catch up), so the lag just accumulated forever — this is
        # what was actually behind the ~10s of growing delay, not decoder
        # speed. Sending as fast as frames are decoded and letting the source
        # be the only clock fixes that.

    def close(self) -> None:
        if self._cam is not None:
            self._cam.close()
            self._cam = None


def create_sink(kind: str) -> FrameSink:
    if kind == "virtualcam":
        return VirtualCamSink()
    if kind == "preview":
        return PreviewWindowSink()
    if kind == "null":
        return NullSink()
    raise ValueError(f"unknown sink kind: {kind}")
