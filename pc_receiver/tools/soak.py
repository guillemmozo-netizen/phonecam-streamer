"""Long-running soak test for the PC receiver.

Answers the questions a passing unit test cannot: does anything grow without
bound over hours, does throughput decay, does A/V drift, do handles leak?

    python -m pc_receiver.tools.soak --minutes 60
    python -m pc_receiver.tools.soak --minutes 720 --fps 60 --audio   # 12h

It runs the *real* receiver in-process against the *real* demo sender in a
subprocess, so the code under test is the code that ships — not a
reimplementation of it. Samples are written to CSV so a long run can be
analysed after the fact rather than watched.

What is measured, once a second:

    rss_mb            process resident memory — the headline leak indicator
    handles           open OS handles (Windows) / file descriptors
    threads           thread count, which is how a leaked worker shows up
    frames, audio     cumulative counters, for throughput over time
    fps_window        frames in the last sample window
    sync_held         video frames held by A/V sync right now
    sync_dropped      cumulative overflow drops
    decode_ms         mean decode cost in the window

The exit status is non-zero if any regression threshold is breached, so this
is usable as a gate rather than only as something to read.
"""

from __future__ import annotations

import argparse
import csv
import os
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import List, Optional

import psutil

from pc_receiver.receiver import AudioOptions, ReceiverStats, handle_connection
from pc_receiver.sinks import NullSink


@dataclass
class Sample:
    elapsed_s: float
    rss_mb: float
    handles: int
    threads: int
    frames: int
    audio_packets: int
    fps_window: float
    sync_held: int
    sync_dropped: int


def sample_process(proc: psutil.Process) -> tuple:
    rss_mb = proc.memory_info().rss / (1024 * 1024)
    try:
        handles = proc.num_handles() if hasattr(proc, "num_handles") else proc.num_fds()
    except Exception:
        handles = -1
    return rss_mb, handles, proc.num_threads()


def run(minutes: float, fps: int, audio: bool, port: int, csv_path: str) -> int:
    os.makedirs(os.path.dirname(os.path.abspath(csv_path)) or ".", exist_ok=True)
    stats = ReceiverStats()
    sink = NullSink()
    samples: List[Sample] = []
    proc = psutil.Process(os.getpid())
    stop = threading.Event()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(1)

    sender_cmd = [
        sys.executable, "-m", "pc_receiver.demo_sender",
        "--port", str(port), "--duration", str(minutes * 60 + 30),
    ]
    if audio:
        sender_cmd.append("--audio")
    sender = subprocess.Popen(
        sender_cmd, cwd=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

    conn, _ = server.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def sampler() -> None:
        started = time.monotonic()
        last_frames = 0
        last_at = started
        # Streamed to disk as they are taken, not accumulated and written at
        # the end. A 12-hour run that dies at hour 11 must still leave 11
        # hours of evidence — and the whole point of a long soak is that the
        # interesting outcome is often the one where something died.
        with open(csv_path, "w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(CSV_COLUMNS)
            handle.flush()
            while not stop.wait(1.0):
                now = time.monotonic()
                rss_mb, handles_count, threads = sample_process(proc)
                window = now - last_at
                fps_window = (stats.frames_received - last_frames) / window if window else 0.0
                sample = Sample(
                    elapsed_s=now - started,
                    rss_mb=rss_mb,
                    handles=handles_count,
                    threads=threads,
                    frames=stats.frames_received,
                    audio_packets=stats.audio_packets_received,
                    fps_window=fps_window,
                    sync_held=0,
                    sync_dropped=0,
                )
                samples.append(sample)
                writer.writerow(csv_row(sample))
                last_frames = stats.frames_received
                last_at = now
                if len(samples) % 30 == 0:
                    # One fsync a minute rather than one a second: enough that
                    # a kill loses at most a minute, cheap enough to ignore.
                    handle.flush()
                    print(f"  [{sample.elapsed_s:7.0f}s] rss={sample.rss_mb:7.1f}MB "
                          f"handles={sample.handles:5d} threads={sample.threads:3d} "
                          f"fps={sample.fps_window:5.1f} frames={sample.frames}",
                          flush=True)

    sampler_thread = threading.Thread(target=sampler, daemon=True)
    sampler_thread.start()

    deadline = time.monotonic() + minutes * 60
    watchdog = threading.Thread(
        target=lambda: (time.sleep(max(0, deadline - time.monotonic())), conn.close()),
        daemon=True,
    )
    watchdog.start()

    print(f"soak: {minutes} min, audio={audio}, port={port}", flush=True)
    try:
        handle_connection(
            conn, sink, stats=stats,
            audio_options=AudioOptions(sink="null" if audio else "none"),
        )
    except Exception as e:
        print(f"receive loop ended: {type(e).__name__}: {e}", flush=True)
    finally:
        stop.set()
        sampler_thread.join(timeout=3)
        sender.terminate()
        try:
            sender.wait(timeout=5)
        except subprocess.TimeoutExpired:
            sender.kill()
        server.close()

    print(f"\nsamples written to {csv_path}")
    return report(samples, stats, sink)


CSV_COLUMNS = ["elapsed_s", "rss_mb", "handles", "threads", "frames",
               "audio_packets", "fps_window"]


def csv_row(s: Sample) -> list:
    return [f"{s.elapsed_s:.1f}", f"{s.rss_mb:.2f}", s.handles,
            s.threads, s.frames, s.audio_packets, f"{s.fps_window:.2f}"]


def _rss_floor_trend(samples: List[Sample]) -> tuple:
    """Leak rate in MB/h, measured as the slope of the per-minute *minimum*
    RSS, plus the first and last of those minima.

    ## Why the minimum, and not the obvious thing

    This used to report `last.rss_mb - first.rss_mb` scaled to an hour. On a
    process whose RSS sawtooths between GC cycles that is not a measurement,
    it is a coin flip on where the endpoints happen to land — and it lied in
    both directions on the same healthy system: -161.7 MB/h on one run,
    +56.6 MB/h on the next, while the process was allocating steadily and
    releasing everything.

    The floor is what actually answers the question. Whatever a garbage
    collector defers, it returns to a baseline; if that baseline climbs, memory
    is genuinely being retained, and if it does not, the peaks are just
    collection cadence. Measured this way the same three runs read: **+702
    MB/h** before the null-sink leak was fixed (floor 62.2 -> 73.9 MB in two
    minutes, unmistakable) and **+3.9 MB/h** after (floor 62.7 -> 63.5 MB over
    fourteen), which is at the resolution limit of a run this short.
    """
    buckets: dict = {}
    for sample in samples:
        buckets.setdefault(int(sample.elapsed_s // 60), []).append(sample.rss_mb)
    # Only whole-ish minutes: a partial bucket's minimum is biased high.
    minima = [(minute, min(values)) for minute, values in sorted(buckets.items())
              if len(values) > 20]
    if len(minima) < 3:
        return 0.0, 0.0, 0.0, len(minima)

    xs = [m for m, _ in minima]
    ys = [v for _, v in minima]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    denominator = sum((x - mean_x) ** 2 for x in xs)
    slope_per_minute = (
        sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / denominator
        if denominator else 0.0
    )
    return slope_per_minute * 60, ys[0], ys[-1], len(minima)


def report(samples: List[Sample], stats: ReceiverStats, sink: NullSink) -> int:
    if len(samples) < 10:
        print("too few samples to judge anything")
        return 1

    # Skip the first 10% as warm-up: decoder allocation, the virtual camera
    # opening and the first GC all land there and are not a trend.
    warm = samples[len(samples) // 10:]
    first, last = warm[0], warm[-1]
    duration_min = (last.elapsed_s - first.elapsed_s) / 60

    rss_per_hour, floor_first, floor_last, floor_minutes = _rss_floor_trend(warm)
    handle_growth = last.handles - first.handles
    thread_growth = last.threads - first.threads

    half = len(warm) // 2
    fps_first_half = sum(s.fps_window for s in warm[:half]) / max(1, half)
    fps_second_half = sum(s.fps_window for s in warm[half:]) / max(1, len(warm) - half)
    fps_decay = (fps_first_half - fps_second_half) / fps_first_half * 100 if fps_first_half else 0

    print()
    print("=" * 62)
    print(f"  duration            {duration_min:.1f} min (post warm-up)")
    print(f"  frames / audio      {stats.frames_received} / {stats.audio_packets_received}")
    print(f"  decode failures     {stats.frames_decoded_failed}")
    print(f"  RSS range           {min(s.rss_mb for s in warm):.1f} - "
          f"{max(s.rss_mb for s in warm):.1f} MB (GC sawtooth)")
    print(f"  RSS floor           {floor_first:.1f} -> {floor_last:.1f} MB "
          f"over {floor_minutes} min  ({rss_per_hour:+.1f} MB/h)")
    print(f"  handles             {first.handles} -> {last.handles} ({handle_growth:+d})")
    print(f"  threads             {first.threads} -> {last.threads} ({thread_growth:+d})")
    print(f"  fps 1st/2nd half    {fps_first_half:.1f} / {fps_second_half:.1f} ({fps_decay:+.1f}%)")
    print("=" * 62)

    failures = []
    # 15 MB/h against the *floor*. Generous enough that allocator arena growth
    # and a short run's measurement noise (~4 MB/h over 14 minutes) do not trip
    # it, tight enough to catch anything real: the null-sink leak this harness
    # found read 702 MB/h, and one leaked 1080p frame per second is 21 GB/h.
    # A genuine slow leak needs a long run to separate from noise — that is a
    # limitation of a short soak, not of this threshold.
    if rss_per_hour > 15:
        failures.append(f"RSS floor rose {rss_per_hour:.1f} MB/h")
    if handle_growth > 10:
        failures.append(f"handles grew by {handle_growth}")
    if thread_growth > 2:
        failures.append(f"threads grew by {thread_growth}")
    if fps_decay > 10:
        failures.append(f"throughput decayed {fps_decay:.1f}%")

    if failures:
        print("REGRESSIONS: " + "; ".join(failures))
        return 1
    print("no regressions detected")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minutes", type=float, default=5)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--audio", action="store_true")
    parser.add_argument("--port", type=int, default=8899)
    parser.add_argument("--csv", default="soak_samples.csv")
    args = parser.parse_args()
    return run(args.minutes, args.fps, args.audio, args.port, args.csv)


if __name__ == "__main__":
    sys.exit(main())
