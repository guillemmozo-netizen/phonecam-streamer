"""Stand-in for the Android app: streams frames to a receiver so the whole
pipeline (capture -> reward-tier gating -> watermark -> encode -> TCP ->
decode -> virtual cam) can be exercised on a single PC, today, without a
phone.

Uses the PC's webcam if one is available, otherwise falls back to a
synthetic generated pattern (so it also runs in headless/CI environments).

    python -m pc_receiver.demo_sender --port 8787
    python -m pc_receiver.demo_sender --port 8787 --simulate-ads 1   # start premium
"""

from __future__ import annotations

import argparse
import logging
import socket
import time
from typing import Optional

import cv2
import numpy as np

from pc_receiver.protocol import Hello, send_frame, send_hello
from reward_engine.reward_manager import RewardConfig, RewardManager, StreamProfile

log = logging.getLogger("demo_sender")

_QUALITY_RESOLUTIONS = {
    "1080p60": (1920, 1080, 60),
    "4k60": (3840, 2160, 60),
}

_FREE_JPEG_QUALITY = 80
_PREMIUM_JPEG_QUALITY = 95


def build_synthetic_frame(width: int, height: int, frame_index: int) -> np.ndarray:
    """Generates a moving test-pattern frame; used when no webcam is present."""
    frame = np.zeros((height, width, 3), dtype=np.uint8)
    offset = (frame_index * 4) % width
    frame[:, :, 0] = (np.arange(width) + offset).astype(np.uint8)
    frame[:, :, 1] = 60
    frame[:, :, 2] = (np.arange(height).reshape(-1, 1) * 0 + 90).astype(np.uint8)
    cv2.putText(
        frame,
        f"SYNTHETIC FEED  frame {frame_index}",
        (40, height // 2),
        cv2.FONT_HERSHEY_SIMPLEX,
        1.2,
        (255, 255, 255),
        2,
        cv2.LINE_AA,
    )
    return frame


def apply_watermark(frame: np.ndarray, text: str = "FrameCast - FREE") -> np.ndarray:
    """Burns a small watermark into the bottom-right corner, in place-safe."""
    out = frame.copy()
    height, width = out.shape[:2]
    font_scale = max(0.5, width / 1600)
    thickness = max(1, int(width / 800))
    (text_w, text_h), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, font_scale, thickness)
    margin = int(width * 0.015)
    x = width - text_w - margin
    y = height - margin
    cv2.putText(
        out,
        text,
        (x, y),
        cv2.FONT_HERSHEY_SIMPLEX,
        font_scale,
        (255, 255, 255),
        thickness,
        cv2.LINE_AA,
    )
    return out


def encode_jpeg(frame: np.ndarray, quality: int) -> bytes:
    ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError("JPEG encode failed")
    return buf.tobytes()


def render_frame_for_profile(
    source_frame: np.ndarray, profile: StreamProfile
) -> tuple[np.ndarray, int]:
    """Applies the reward-tier profile (resolution + watermark + jpeg quality)
    to a captured/synthetic source frame. Returns (frame, jpeg_quality)."""
    width, height, _fps = _QUALITY_RESOLUTIONS[profile.quality]
    resized = cv2.resize(source_frame, (width, height), interpolation=cv2.INTER_LINEAR)
    if profile.watermark:
        resized = apply_watermark(resized)
    jpeg_quality = _PREMIUM_JPEG_QUALITY if profile.premium_active else _FREE_JPEG_QUALITY
    return resized, jpeg_quality


class FrameSource:
    """Wraps a webcam if available, else generates a synthetic pattern."""

    def __init__(self) -> None:
        self._cap = cv2.VideoCapture(0)
        if not self._cap.isOpened():
            self._cap = None
            log.warning("no webcam found, using synthetic test pattern")
        self._frame_index = 0

    @property
    def is_synthetic(self) -> bool:
        return self._cap is None

    def read(self) -> np.ndarray:
        self._frame_index += 1
        if self._cap is not None:
            ok, frame = self._cap.read()
            if ok:
                return frame
            log.warning("webcam read failed, switching to synthetic pattern")
            self._cap = None
        return build_synthetic_frame(1280, 720, self._frame_index)

    def close(self) -> None:
        if self._cap is not None:
            self._cap.release()


def run(
    host: str,
    port: int,
    duration_seconds: Optional[float],
    max_frames: Optional[int],
    simulate_ads: int,
) -> None:
    reward = RewardManager(RewardConfig())
    for _ in range(simulate_ads):
        reward.credit_ad_watch()

    source = FrameSource()
    sock = socket.create_connection((host, port))
    log.info("connected to receiver at %s:%s", host, port)

    profile = reward.current_profile()
    width, height, fps = _QUALITY_RESOLUTIONS[profile.quality]
    send_hello(
        sock,
        Hello(
            width=width,
            height=height,
            fps=fps,
            quality=profile.quality,
            watermark=profile.watermark,
            device_name="demo-sender (PC webcam or synthetic)",
        ),
    )

    start = time.monotonic()
    frame_count = 0
    try:
        while True:
            if max_frames is not None and frame_count >= max_frames:
                break
            now = time.monotonic()
            if duration_seconds is not None and (now - start) >= duration_seconds:
                break

            profile = reward.current_profile()

            source_frame = source.read()
            frame, jpeg_quality = render_frame_for_profile(source_frame, profile)
            send_frame(sock, encode_jpeg(frame, jpeg_quality))
            frame_count += 1

            # Re-read fps each iteration — profile (and therefore quality tier) can
            # change mid-stream as the ad-earned balance drains, and pacing on a
            # stale value would drift once free/premium fps ever diverge.
            _, _, fps = _QUALITY_RESOLUTIONS[profile.quality]
            time.sleep(max(0.0, (1.0 / fps) - (time.monotonic() - now)))
    finally:
        source.close()
        sock.close()
        # balance_seconds() is a method, not a property — passing it unbound
        # made logging blow up on the format ("must be real number, not
        # method"), so the demo's closing summary never printed.
        log.info("sent %d frames, final balance=%.0fs", frame_count, reward.balance_seconds())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--duration", type=float, default=None, help="seconds to stream")
    parser.add_argument("--frames", type=int, default=None, help="stop after N frames")
    parser.add_argument(
        "--simulate-ads",
        type=int,
        default=0,
        help="pretend the user already watched N rewarded ads before connecting",
    )
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run(args.host, args.port, args.duration, args.frames, args.simulate_ads)


if __name__ == "__main__":
    main()
