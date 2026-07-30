"""Stand-in for the Android app: streams frames to a receiver so the whole
pipeline (capture -> reward-tier gating -> watermark -> encode -> TCP ->
decode -> virtual cam) can be exercised on a single PC, today, without a
phone.

Uses the PC's webcam if one is available, otherwise falls back to a
synthetic generated pattern (so it also runs in headless/CI environments).

    python -m pc_receiver.demo_sender --port 8787
    python -m pc_receiver.demo_sender --port 8787 --simulate-ads 1   # start premium
    python -m pc_receiver.demo_sender --port 8787 --audio            # with an audio track

--audio makes this the stand-in for the phone's *audio* path too: it encodes
AAC-LC with PyAV, frames it as ADTS and interleaves it with video on one
socket, exactly as android-app's AudioEncoder.kt does. That is what makes the
receiver's demux, audio decode, A/V sync and audio sinks all exercisable on a
PC with no phone attached — the same reason test_h264_decoder.py uses PyAV to
stand in for MediaCodec on the video side.
"""

from __future__ import annotations

import argparse
import logging
import socket
import time
from fractions import Fraction
from typing import List, Optional

import cv2
import numpy as np

from pc_receiver.aac import wrap_adts
from pc_receiver.protocol import (
    Hello,
    MediaKind,
    pack_chunk,
    send_frame,
    send_hello,
    send_message,
)
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


def apply_watermark(frame: np.ndarray, text: str = "PhoneCam Streamer - FREE") -> np.ndarray:
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


AUDIO_SAMPLE_RATE = 48000
AUDIO_CHANNELS = 2
AUDIO_BITRATE_BPS = 192_000
# AAC-LC always codes 1024 samples per frame — 21.3ms at 48kHz. Feeding the
# encoder in exactly that unit means one input block produces one output
# packet, so the sender's pacing and the wire's packet rate are the same
# thing and there is no hidden buffering to reason about.
AAC_SAMPLES_PER_FRAME = 1024


class ToneAudioSource:
    """Generates and AAC-encodes a test tone, standing in for the phone's mic.

    A 440Hz tone that pulses once a second, so an A/V sync problem is
    *audible* against the video's frame counter rather than only visible in
    the logs: the pulse should land on the frame where the counter ticks over.

    PyAV does the encoding here for the same reason it does on the video side
    of the test suite — it is a real AAC encoder standing in for MediaCodec,
    so what goes on the wire is genuine AAC rather than a mock, and the
    receiver's decoder is exercised for real.
    """

    def __init__(self, sample_rate: int = AUDIO_SAMPLE_RATE, channels: int = AUDIO_CHANNELS) -> None:
        import av

        self.sample_rate = sample_rate
        self.channels = channels
        self._layout = "mono" if channels == 1 else "stereo"
        self._encoder = av.CodecContext.create("aac", "w")
        self._encoder.sample_rate = sample_rate
        self._encoder.format = "fltp"
        self._encoder.layout = self._layout
        self._encoder.bit_rate = AUDIO_BITRATE_BPS
        self._samples_sent = 0

    @property
    def samples_sent(self) -> int:
        return self._samples_sent

    def next_packets(self, up_to_sample: int) -> List[bytes]:
        """ADTS frames covering audio up to [up_to_sample], so the caller can
        drive audio off the same wall clock as video instead of a second,
        independently drifting one."""
        import av

        packets: List[bytes] = []
        while self._samples_sent + AAC_SAMPLES_PER_FRAME <= up_to_sample:
            block = self._render(self._samples_sent, AAC_SAMPLES_PER_FRAME)
            frame = av.AudioFrame.from_ndarray(block, format="fltp", layout=self._layout)
            frame.sample_rate = self.sample_rate
            frame.pts = self._samples_sent
            frame.time_base = Fraction(1, self.sample_rate)
            for packet in self._encoder.encode(frame):
                packets.append(wrap_adts(bytes(packet), self.sample_rate, self.channels))
            self._samples_sent += AAC_SAMPLES_PER_FRAME
        return packets

    def _render(self, start_sample: int, count: int) -> np.ndarray:
        t = (np.arange(start_sample, start_sample + count, dtype=np.float64)) / self.sample_rate
        # A 40ms blip at the top of every second, over a continuous quiet tone.
        envelope = np.where((t % 1.0) < 0.04, 0.5, 0.06)
        wave = (np.sin(2 * np.pi * 440.0 * t) * envelope).astype(np.float32)
        return np.stack([wave] * self.channels)

    def flush(self) -> List[bytes]:
        packets = []
        for packet in self._encoder.encode(None):
            packets.append(wrap_adts(bytes(packet), self.sample_rate, self.channels))
        return packets


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
    audio: bool = False,
) -> None:
    reward = RewardManager(RewardConfig())
    for _ in range(simulate_ads):
        reward.credit_ad_watch()

    source = FrameSource()
    audio_source = ToneAudioSource() if audio else None
    sock = socket.create_connection((host, port))
    log.info("connected to receiver at %s:%s (audio=%s)", host, port, audio)

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
            # Announcing audio is what switches the receiver's framing over to
            # tagged chunks, so it has to be decided before the first one goes
            # out and never changed mid-connection.
            audio_codec="aac" if audio else "",
            audio_sample_rate=AUDIO_SAMPLE_RATE if audio else 0,
            audio_channels=AUDIO_CHANNELS if audio else 0,
            audio_bitrate_bps=AUDIO_BITRATE_BPS if audio else 0,
        ),
    )

    start = time.monotonic()
    frame_count = 0

    def send_video(payload: bytes, pts_us: int) -> None:
        """Bare payload without audio, tagged with it — the two framings the
        receiver accepts, chosen by the same flag the hello announced."""
        if audio_source is None:
            send_frame(sock, payload)
        else:
            send_message(sock, pack_chunk(MediaKind.VIDEO, pts_us, payload))

    try:
        while True:
            if max_frames is not None and frame_count >= max_frames:
                break
            now = time.monotonic()
            if duration_seconds is not None and (now - start) >= duration_seconds:
                break

            elapsed = now - start
            pts_us = int(elapsed * 1_000_000)
            profile = reward.current_profile()

            # Audio first, and paced off the same elapsed time as video, so
            # both streams carry timestamps from one clock — which is the
            # entire basis on which the receiver can align them (av_sync.py).
            if audio_source is not None:
                for packet in audio_source.next_packets(int(elapsed * AUDIO_SAMPLE_RATE)):
                    packet_pts = audio_source.samples_sent * 1_000_000 // AUDIO_SAMPLE_RATE
                    send_message(sock, pack_chunk(MediaKind.AUDIO, packet_pts, packet))

            source_frame = source.read()
            frame, jpeg_quality = render_frame_for_profile(source_frame, profile)
            send_video(encode_jpeg(frame, jpeg_quality), pts_us)
            frame_count += 1

            # Re-read fps each iteration — profile (and therefore quality tier) can
            # change mid-stream as the ad-earned balance drains, and pacing on a
            # stale value would drift once free/premium fps ever diverge.
            _, _, fps = _QUALITY_RESOLUTIONS[profile.quality]
            time.sleep(max(0.0, (1.0 / fps) - (time.monotonic() - now)))
    finally:
        if audio_source is not None:
            try:
                for packet in audio_source.flush():
                    packet_pts = audio_source.samples_sent * 1_000_000 // AUDIO_SAMPLE_RATE
                    send_message(sock, pack_chunk(MediaKind.AUDIO, packet_pts, packet))
            except OSError:
                pass  # receiver already gone; the video stats below still matter
        source.close()
        sock.close()
        log.info(
            "sent %d frames%s, final balance=%.0fs",
            frame_count,
            f" and {audio_source.samples_sent / AUDIO_SAMPLE_RATE:.1f}s of audio" if audio_source else "",
            # Called, not passed: balance_seconds is a method, and handing the
            # bound method to %.0f made logging swallow this whole line — so
            # the run summary silently printed nothing at all.
            reward.balance_seconds(),
        )


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
    parser.add_argument(
        "--audio",
        action="store_true",
        help="also send an AAC test tone, so the receiver's audio path and A/V sync get exercised",
    )
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run(args.host, args.port, args.duration, args.frames, args.simulate_ads, args.audio)


if __name__ == "__main__":
    main()
