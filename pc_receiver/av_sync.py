"""Keeps video aligned with audio on the PC side.

## Why video is the stream that waits

Both streams arrive stamped with the same phone-side clock (see
protocol.pack_chunk), but they leave this machine through paths with very
different latencies:

  video   decode -> pyvirtualcam -> whatever app is reading the camera.
          Effectively immediate; nothing here buffers frames.
  audio   decode -> ring buffer -> the sound device's own callback.
          Deliberately buffered — an audio device that runs dry produces an
          audible click, so it has to be kept a few packets ahead at all
          times (see audio_sinks.DeviceAudioSink).

So audio is heard *later* than the matching video is shown, by roughly the
depth of that buffer. Closing the gap means delaying video: audio cannot be
hurried along without pitching or glitching it, which is far more noticeable
than a few tens of milliseconds of extra video latency.

That delay is a real cost for a product whose pitch is low latency, so it is
bounded at both ends: the audio buffer is kept as shallow as it can be
(audio_sinks picks the target), and this class never holds a frame longer
than [max_hold_seconds] no matter what the audio clock says. A stalled or
dead audio path degrades to "video runs free, exactly as it did before audio
existed" — never to a frozen picture.

## Not a clock recovery system

Two independent oscillators (the phone's ADC and the PC's DAC) drift apart
over a long session — a few samples per minute. This class does not correct
that; it only reacts to where the audio clock actually is, so drift shows up
as the hold time slowly changing rather than as accumulating desync. The
buffer-level correction that absorbs the drift lives in the audio sink, which
is the component that can actually add or drop samples.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Callable, List, Optional, Tuple

# How far out of step is close enough. One 60fps frame is 16.7ms and the
# ITU's widely-cited lip-sync tolerance is about -125ms (audio late) to +45ms
# (audio early), so aiming inside a frame time is comfortably stricter than
# anyone can perceive — and being stricter than this would just churn the
# queue over ordinary network jitter.
DEFAULT_TOLERANCE_US = 15_000

# Ceiling on how long a frame may be held waiting for audio that isn't
# advancing. Chosen to be longer than any plausible audio buffer plus network
# jitter, and short enough that a hung audio device reads as a brief stutter
# rather than a freeze.
DEFAULT_MAX_HOLD_SECONDS = 0.35


@dataclass
class SyncStats:
    """Diagnostics for the periodic pipeline log. Skew is signed: positive
    means video is ahead of (waiting on) audio, which is the normal steady
    state; negative means video arrived late and went straight out."""

    released: int = 0
    held_now: int = 0
    released_on_timeout: int = 0
    dropped_overflow: int = 0
    last_skew_us: int = 0

    @property
    def last_skew_ms(self) -> float:
        return self.last_skew_us / 1000.0


class AvSync:
    """Holds decoded video frames until the audio playout clock reaches them.

    Deliberately free of numpy, sockets and audio devices: frames are opaque
    objects here, and the only input from the outside world is an audio clock
    reading the caller passes in. That is what lets the whole policy —
    including the timeout and overflow escape hatches, which are the parts
    that must not be wrong — be tested with a fake clock and integers.
    """

    def __init__(
        self,
        tolerance_us: int = DEFAULT_TOLERANCE_US,
        max_hold_seconds: float = DEFAULT_MAX_HOLD_SECONDS,
        max_queued_frames: int = 30,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._tolerance_us = tolerance_us
        self._max_hold_seconds = max_hold_seconds
        self._max_queued_frames = max_queued_frames
        self._clock = clock
        # (frame, pts_us, queued_at_monotonic), oldest first.
        self._queue: List[Tuple[Any, int, float]] = []
        self.stats = SyncStats()

    def submit(self, frame: Any, pts_us: int) -> None:
        """Queue one decoded video frame. Never blocks and never drops
        silently — an overflow drop is counted in stats."""
        self._queue.append((frame, pts_us, self._clock()))
        while len(self._queue) > self._max_queued_frames:
            # Drop the oldest, not the newest: an overfull queue means this
            # side is behind real time, and the same "always show the
            # freshest" policy the receive loop already applies to socket
            # backlog is the right one here too.
            self._queue.pop(0)
            self.stats.dropped_overflow += 1

    def due(self, audio_clock_us: Optional[int]) -> List[Any]:
        """Frames whose moment has come, oldest first.

        [audio_clock_us] is the phone-clock timestamp of the audio currently
        being heard, or None when that is unknown — no audio on this
        connection, the device hasn't started, or it has failed. None means
        release everything: video must not depend on audio working.
        """
        if audio_clock_us is None:
            return self._release(len(self._queue))

        now = self._clock()
        count = 0
        for _, pts_us, queued_at in self._queue:
            due_by_clock = pts_us - audio_clock_us <= self._tolerance_us
            timed_out = now - queued_at >= self._max_hold_seconds
            if not (due_by_clock or timed_out):
                break
            if timed_out and not due_by_clock:
                self.stats.released_on_timeout += 1
            count += 1

        if count:
            # Measured against the newest frame actually going out, which is
            # the one whose alignment a viewer would judge.
            self.stats.last_skew_us = self._queue[count - 1][1] - audio_clock_us
        return self._release(count)

    def drain(self) -> List[Any]:
        """Everything still held. For end of session, where there is no more
        audio coming and dropping these would just lose the final frames."""
        return self._release(len(self._queue))

    def _release(self, count: int) -> List[Any]:
        if count <= 0:
            self.stats.held_now = len(self._queue)
            return []
        released = [item[0] for item in self._queue[:count]]
        del self._queue[:count]
        self.stats.released += len(released)
        self.stats.held_now = len(self._queue)
        return released

    def __len__(self) -> int:
        return len(self._queue)
