"""Output sinks for decoded video frames.

Kept separate from socket/decoding logic so each sink can be unit tested
without a real virtual camera driver (OBS Virtual Camera on Windows) or a
display attached.
"""

from __future__ import annotations

from typing import List, Protocol

import numpy as np


class FrameSink(Protocol):
    def send(self, frame_rgb: np.ndarray, fps: int) -> None: ...

    def close(self) -> None: ...


class NullSink:
    """Discards frames; records how many/what shape it received. Test-only."""

    def __init__(self) -> None:
        self.frames: List[np.ndarray] = []
        self.closed = False

    def send(self, frame_rgb: np.ndarray, fps: int) -> None:
        self.frames.append(frame_rgb)

    def close(self) -> None:
        self.closed = True


class PreviewWindowSink:
    """Shows the stream in an OpenCV window. For manual/local testing only.

    OpenCV's imshow (like the rest of OpenCV) expects BGR — everywhere else
    in this module now deals in RGB (see h264_decoder.H264Decoder.decode's
    doc for why), so this is the one place that still needs a conversion,
    paid only by this debug-only path instead of the real virtualcam one.
    """

    def __init__(self, window_name: str = "PhoneCam Streamer - Preview") -> None:
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


class VirtualCamSink:
    """Feeds frames into a system virtual camera via pyvirtualcam.

    Requires a backend to be installed (OBS Virtual Camera on Windows,
    v4l2loopback on Linux). The camera is created lazily on the first frame
    because we only know the true resolution once the stream announces it.
    """

    def __init__(self) -> None:
        self._cam = None

    def send(self, frame_rgb: np.ndarray, fps: int) -> None:
        import pyvirtualcam

        height, width = frame_rgb.shape[:2]
        if self._cam is None or self._cam.width != width or self._cam.height != height:
            if self._cam is not None:
                self._cam.close()
            self._cam = pyvirtualcam.Camera(width=width, height=height, fps=fps)

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
