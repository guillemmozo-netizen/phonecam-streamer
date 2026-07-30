"""Per-stage benchmark for the PC receive pipeline.

Answers "where does each millisecond go" with measurements rather than
guesses, at every resolution the app can send.

    python -m pc_receiver.tools.bench
    python -m pc_receiver.tools.bench --sizes 1920x1080,3840x2160 --frames 200

Each stage is timed in isolation, on real encoded data, so the numbers add
up to something meaningful against the per-frame budget printed alongside
them (16.7ms at 60fps, 33.3ms at 30fps).

Stages measured:

    encode        not part of the receiver, but needed to make the input;
                  reported so it can be subtracted mentally
    decode        H.264/HEVC through PyAV — NVDEC when available
    convert       decoder-native -> whatever the sink wants
    framing       protocol pack/unpack, i.e. what the wire format costs
    audio decode  one AAC packet, ADTS-framed
    av sync       submit + due, the cost of the alignment queue itself
"""

from __future__ import annotations

import argparse
import statistics
import time
from fractions import Fraction
from typing import Callable, List, Tuple

import av
import numpy as np

from pc_receiver.aac import wrap_adts
from pc_receiver.audio_decoder import AudioDecoder
from pc_receiver.av_sync import AvSync
from pc_receiver.h264_decoder import H264Decoder
from pc_receiver.protocol import MediaKind, pack_chunk, unpack_chunk


def timed(fn: Callable[[], object], repeats: int) -> List[float]:
    """Per-call milliseconds, warm-up call excluded."""
    fn()
    samples = []
    for _ in range(repeats):
        start = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - start) * 1000)
    return samples


def summarise(name: str, samples: List[float], budget_ms: float) -> None:
    if not samples:
        print(f"  {name:<16} (no samples)")
        return
    mean = statistics.mean(samples)
    p50 = statistics.median(samples)
    p95 = sorted(samples)[int(len(samples) * 0.95)] if len(samples) > 1 else samples[0]
    share = mean / budget_ms * 100 if budget_ms else 0
    print(f"  {name:<16} mean {mean:7.3f}ms  p50 {p50:7.3f}ms  p95 {p95:7.3f}ms   {share:5.1f}% of budget")


def make_h264(width: int, height: int, count: int) -> Tuple[List[bytes], str]:
    """Encoded packets plus the codec name, standing in for the phone."""
    codec = "hevc" if width * height > 3840 * 2160 else "h264"
    encoder = av.CodecContext.create(codec, "w")
    encoder.width, encoder.height = width, height
    encoder.pix_fmt = "yuv420p"
    encoder.bit_rate = 20_000_000
    encoder.time_base = Fraction(1, 30)

    rng = np.random.default_rng(7)
    packets = []
    for i in range(count):
        # Real noise, not a flat colour: a constant image encodes to almost
        # nothing and would make both encode and decode look free.
        image = rng.integers(0, 255, (height, width, 3), dtype=np.uint8)
        frame = av.VideoFrame.from_ndarray(image, format="rgb24").reformat(format="yuv420p")
        frame.pts = i
        packets.extend(encoder.encode(frame))
    packets.extend(encoder.encode(None))
    return [bytes(p) for p in packets], codec


def bench_video(width: int, height: int, fps: int, count: int) -> None:
    budget = 1000.0 / fps
    print(f"\n{width}x{height} @ {fps}fps  (budget {budget:.1f}ms/frame)")
    print("-" * 78)

    packets, codec = make_h264(width, height, count)
    total_bytes = sum(len(p) for p in packets)
    print(f"  {len(packets)} packets, {total_bytes/1e6:.1f}MB "
          f"(~{total_bytes*fps/len(packets)*8/1e6:.0f} Mbps at {fps}fps)")

    for output_format in ("native", "rgb"):
        decoder = H264Decoder(output_format=output_format, codec=codec)
        index = [0]

        def decode_one():
            packet = packets[index[0] % len(packets)]
            index[0] += 1
            return decoder.decode(packet)

        samples = timed(decode_one, min(count, len(packets)) - 1)
        label = f"decode({output_format})"
        summarise(label, samples, budget)
        if output_format == "native":
            print(f"  {'':<16} backend={decoder.backend}")
        decoder.flush()
        decoder.close()

    # Framing: what the wire format itself costs per message.
    payload = packets[len(packets) // 2]
    summarise("pack_chunk", timed(lambda: pack_chunk(MediaKind.VIDEO, 12345, payload), 2000), budget)
    packed = pack_chunk(MediaKind.VIDEO, 12345, payload)
    summarise("unpack_chunk", timed(lambda: unpack_chunk(packed), 2000), budget)


def bench_audio() -> None:
    print("\naudio  (one AAC-LC packet = 1024 samples = 21.3ms of audio)")
    print("-" * 78)
    sample_rate, channels = 48000, 2

    encoder = av.CodecContext.create("aac", "w")
    encoder.sample_rate = sample_rate
    encoder.format = "fltp"
    encoder.layout = "stereo"
    encoder.bit_rate = 192_000

    tone = (0.4 * np.sin(2 * np.pi * 440 * np.arange(sample_rate) / sample_rate)).astype(np.float32)
    packets = []
    for offset in range(0, sample_rate - 1024, 1024):
        block = np.stack([tone[offset:offset + 1024]] * channels)
        frame = av.AudioFrame.from_ndarray(block, format="fltp", layout="stereo")
        frame.sample_rate = sample_rate
        frame.pts = offset
        frame.time_base = Fraction(1, sample_rate)
        packets.extend(encoder.encode(frame))
    packets.extend(encoder.encode(None))
    adts = [wrap_adts(bytes(p), sample_rate, channels) for p in packets]

    decoder = AudioDecoder("aac", sample_rate, channels)
    index = [0]

    def decode_one():
        packet = adts[index[0] % len(adts)]
        index[0] += 1
        return decoder.decode(packet)

    # Budget is one packet's own duration: anything approaching it means the
    # decoder cannot keep up with real time.
    summarise("aac decode", timed(decode_one, len(adts) - 1), 21.3)
    summarise("adts wrap", timed(lambda: wrap_adts(bytes(packets[0]), sample_rate, channels), 2000), 21.3)

    pcm = AudioDecoder("pcm_s16le", sample_rate, channels)
    raw = np.zeros((1024, channels), dtype=np.int16).tobytes()
    summarise("pcm decode", timed(lambda: pcm.decode(raw), 2000), 21.3)


def bench_sync() -> None:
    print("\nA/V sync  (per video frame)")
    print("-" * 78)
    sync = AvSync()
    frame = object()
    counter = [0]

    def submit_and_release():
        counter[0] += 1
        sync.submit(frame, counter[0] * 16_667)
        return sync.due(counter[0] * 16_667)

    summarise("submit+due", timed(submit_and_release, 5000), 16.7)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", default="1280x720,1920x1080,3840x2160")
    parser.add_argument("--frames", type=int, default=60)
    parser.add_argument("--fps", type=int, default=60)
    args = parser.parse_args()

    print("=" * 78)
    print("PhoneCam PC pipeline — per-stage cost")
    print("=" * 78)

    for spec in args.sizes.split(","):
        width, height = (int(v) for v in spec.lower().split("x"))
        bench_video(width, height, args.fps, args.frames)

    bench_audio()
    bench_sync()
    print()


if __name__ == "__main__":
    main()
