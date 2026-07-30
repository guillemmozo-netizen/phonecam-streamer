"""AvSync's whole job is deciding *when* a frame goes out, so every test here
drives it with a fake clock and integer timestamps rather than real time —
the escape hatches (audio stalled, queue overflowing) are the parts that must
not be wrong, and neither is reachable reliably by sleeping."""

from pc_receiver.av_sync import AvSync


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def make_sync(**kwargs) -> tuple[AvSync, FakeClock]:
    clock = FakeClock()
    return AvSync(clock=clock, **kwargs), clock


def test_no_audio_clock_releases_everything_immediately():
    """The single most important behaviour: video must never depend on audio
    working. A None clock is what a missing, failed or not-yet-started audio
    device reports."""
    sync, _ = make_sync()
    sync.submit("a", 1_000)
    sync.submit("b", 2_000)

    assert sync.due(None) == ["a", "b"]
    assert len(sync) == 0


def test_frames_are_held_until_the_audio_clock_reaches_them():
    sync, _ = make_sync(tolerance_us=0)
    sync.submit("early", 1_000)
    sync.submit("late", 100_000)

    assert sync.due(1_000) == ["early"]
    assert len(sync) == 1
    assert sync.due(50_000) == []
    assert sync.due(100_000) == ["late"]


def test_tolerance_releases_frames_that_are_close_enough():
    sync, _ = make_sync(tolerance_us=15_000)
    sync.submit("frame", 60_000)

    # 10ms ahead of the audio clock is inside a frame time; holding it would
    # just churn the queue over ordinary jitter.
    assert sync.due(50_000) == ["frame"]


def test_frames_are_released_in_order_and_stop_at_the_first_not_due():
    sync, _ = make_sync(tolerance_us=0)
    for i, pts in enumerate([10, 20, 30, 40]):
        sync.submit(f"f{i}", pts * 1_000)

    assert sync.due(25_000) == ["f0", "f1"]
    assert sync.due(25_000) == []
    assert sync.due(40_000) == ["f2", "f3"]


def test_a_stalled_audio_clock_releases_video_rather_than_freezing_it():
    """A hung audio device must degrade to a stutter, not a frozen picture."""
    sync, clock = make_sync(tolerance_us=0, max_hold_seconds=0.35)
    sync.submit("frame", 10_000_000)  # far in the future relative to the clock below

    assert sync.due(0) == []
    clock.advance(0.34)
    assert sync.due(0) == []

    clock.advance(0.02)
    assert sync.due(0) == ["frame"]
    assert sync.stats.released_on_timeout == 1


def test_timeout_is_not_counted_when_the_frame_was_due_anyway():
    sync, clock = make_sync(tolerance_us=0, max_hold_seconds=0.1)
    sync.submit("frame", 1_000)
    clock.advance(0.5)

    assert sync.due(5_000) == ["frame"]
    assert sync.stats.released_on_timeout == 0


def test_overflow_drops_the_oldest_frames():
    """Behind real time means show the freshest, matching the socket-backlog
    policy the receive loop already applies one stage earlier."""
    sync, _ = make_sync(tolerance_us=0, max_queued_frames=3)
    for i in range(5):
        sync.submit(f"f{i}", 1_000_000 + i)

    assert len(sync) == 3
    assert sync.stats.dropped_overflow == 2
    assert sync.due(None) == ["f2", "f3", "f4"]


def test_skew_is_reported_signed_against_the_newest_released_frame():
    sync, _ = make_sync(tolerance_us=100_000)
    sync.submit("a", 30_000)
    sync.submit("b", 45_000)

    sync.due(20_000)
    assert sync.stats.last_skew_us == 25_000        # video ahead: normal steady state
    assert sync.stats.last_skew_ms == 25.0

    sync.submit("c", 10_000)
    sync.due(60_000)
    assert sync.stats.last_skew_us == -50_000       # video late, went straight out


def test_drain_returns_everything_still_held():
    sync, _ = make_sync(tolerance_us=0)
    sync.submit("a", 999_000)
    sync.submit("b", 999_001)

    assert sync.due(0) == []
    assert sync.drain() == ["a", "b"]
    assert len(sync) == 0
    assert sync.drain() == []


def test_held_count_tracks_the_queue():
    sync, _ = make_sync(tolerance_us=0)
    sync.submit("a", 500_000)
    sync.submit("b", 600_000)

    sync.due(0)
    assert sync.stats.held_now == 2
    sync.due(500_000)
    assert sync.stats.held_now == 1
