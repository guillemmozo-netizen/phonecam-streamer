"""Reads a soak CSV and reports stability at several elapsed-time cutoffs.

    python -m pc_receiver.tools.analyse_soak soak_12h.csv
    python -m pc_receiver.tools.analyse_soak soak_12h.csv --hours 1 3 6 12

Why cutoffs rather than separate runs: a single continuous 12-hour session
answers the 1/3/6/12-hour questions *and* answers them better. Four separate
runs would each test a fresh process, which is a test of startup; one long run
is the only thing that can show a leak, a drift or a decay that needs hours to
become visible. Truncating the same CSV gives the short-horizon answers for
free.

Safe to run against a CSV that is still being written — the soak streams rows
as it takes them, so this reports on however much has happened so far.
"""

from __future__ import annotations

import argparse
import csv
import statistics
import sys
from typing import List, Optional, Tuple


def load(path: str) -> List[dict]:
    with open(path, newline="", encoding="utf-8") as handle:
        rows = []
        for row in csv.DictReader(handle):
            try:
                rows.append({
                    "t": float(row["elapsed_s"]),
                    "rss": float(row["rss_mb"]),
                    "handles": int(row["handles"]),
                    "threads": int(row["threads"]),
                    "frames": int(row["frames"]),
                    "audio": int(row["audio_packets"]),
                    "fps": float(row["fps_window"]),
                })
            except (ValueError, KeyError):
                # A row torn in half by a kill mid-write. Skip it rather than
                # refuse to analyse the eleven good hours around it.
                continue
        return rows


def slope_per_hour(points: List[Tuple[float, float]]) -> Optional[float]:
    """Least-squares slope in units/hour, or None if there is too little to
    say anything honest."""
    if len(points) < 3:
        return None
    xs = [x for x, _ in points]
    ys = [y for _, y in points]
    mean_x, mean_y = statistics.mean(xs), statistics.mean(ys)
    denominator = sum((x - mean_x) ** 2 for x in xs)
    if denominator == 0:
        return None
    slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / denominator
    return slope * 3600


def rss_floor_points(rows: List[dict]) -> List[Tuple[float, float]]:
    """Per-minute minimum RSS.

    The floor, not the raw series: RSS sawtooths tens of MB between garbage
    collections, so a fit through every sample mostly measures collection
    cadence. Whatever a collector defers it returns to a baseline — if that
    baseline climbs, memory is genuinely being retained.
    """
    buckets: dict = {}
    for row in rows:
        buckets.setdefault(int(row["t"] // 60), []).append(row["rss"])
    return [(minute * 60.0, min(values))
            for minute, values in sorted(buckets.items())
            if len(values) > 20]


def report_window(rows: List[dict], label: str) -> None:
    if len(rows) < 60:
        print(f"  {label:<7} (only {len(rows)} samples — not reached yet)")
        return

    # Drop the first 10% as warm-up: decoder allocation and the first GC land
    # there and are not a trend.
    warm = rows[len(rows) // 10:]
    floor = rss_floor_points(warm)
    rss_trend = slope_per_hour(floor)
    handles_trend = slope_per_hour([(r["t"], r["handles"]) for r in warm])
    threads_trend = slope_per_hour([(r["t"], r["threads"]) for r in warm])

    half = len(warm) // 2
    fps_first = statistics.mean(r["fps"] for r in warm[:half])
    fps_second = statistics.mean(r["fps"] for r in warm[half:])
    fps_decay = (fps_first - fps_second) / fps_first * 100 if fps_first else 0.0

    def rate(value: Optional[float], width: int, places: int) -> str:
        # A window too short for a trend says so, rather than printing a
        # number it cannot support — the whole point of the short cutoffs is
        # to show when there is not yet enough evidence.
        return f"{value:+{width}.{places}f}" if value is not None else "   n/a".rjust(width + 1)

    floor_text = (f"{floor[0][1]:.1f} -> {floor[-1][1]:.1f} MB" if floor else "n/a")
    print(f"  {label:<7} "
          f"rss_floor {rate(rss_trend, 6, 1)} MB/h ({floor_text})   "
          f"handles {rate(handles_trend, 5, 2)}/h   "
          f"threads {rate(threads_trend, 5, 2)}/h   "
          f"fps {fps_first:.1f}->{fps_second:.1f} ({fps_decay:+.1f}%)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path")
    parser.add_argument("--hours", type=float, nargs="+", default=[1, 3, 6, 12])
    args = parser.parse_args()

    rows = load(args.csv_path)
    if not rows:
        print("no usable samples")
        return 1

    elapsed_h = rows[-1]["t"] / 3600
    print("=" * 100)
    print(f"  {args.csv_path}")
    print(f"  {len(rows)} samples, {elapsed_h:.2f}h elapsed, "
          f"{rows[-1]['frames']} frames, {rows[-1]['audio']} audio packets")
    print("=" * 100)

    for hours in args.hours:
        window = [r for r in rows if r["t"] <= hours * 3600]
        report_window(window, f"{hours:g}h")

    print("-" * 100)
    report_window(rows, "all")
    print()
    print("  rss_floor is the leak metric. Endpoint-to-endpoint RSS is not: on this")
    print("  pipeline it read -161 MB/h and +57 MB/h on consecutive healthy runs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
