"""Playout-buffer tests.

This is the code that runs on a real-time audio callback thread, where a
mistake is an audible click rather than a stack trace, and where the two
escape hatches (underrun, drift overflow) are by definition rare in normal
running. So it is exercised here directly, with hand-fed blocks, rather than
left to whatever a live sound card happens to do.
"""

import numpy as np
import pytest

from pc_receiver.audio_sinks import (
    NullAudioSink,
    PlayoutBuffer,
    WavFileAudioSink,
    create_audio_sink,
)

SAMPLE_RATE = 48000
CHANNELS = 2


def make_buffer(target_ms=60, max_ms=240) -> PlayoutBuffer:
    return PlayoutBuffer(SAMPLE_RATE, CHANNELS, target_buffer_ms=target_ms, max_buffer_ms=max_ms)


def block(n_frames: int, value: int = 1000) -> np.ndarray:
    return np.full((n_frames, CHANNELS), value, dtype=np.int16)


def read(buffer: PlayoutBuffer, n_frames: int):
    out = np.zeros((n_frames, CHANNELS), dtype=np.int16)
    return buffer.read_into(out), out


# ─────────────────────────── priming ───────────────────────────

def test_nothing_plays_until_the_buffer_has_a_cushion():
    """Playing on the very first packet guarantees an immediate underrun —
    the cushion is the entire reason this class exists."""
    buffer = make_buffer(target_ms=60)  # 2880 frames at 48kHz
    buffer.write(block(1000), pts_us=0)

    assert buffer.priming is True
    written, out = read(buffer, 512)
    assert written == 0
    assert not out.any(), "silence expected while priming"


def test_playback_starts_once_the_target_depth_is_reached():
    buffer = make_buffer(target_ms=60)
    buffer.write(block(3000), pts_us=0)

    assert buffer.priming is False
    written, out = read(buffer, 512)
    assert written == 512
    assert (out == 1000).all()


# ─────────────────────────── draining ───────────────────────────

def test_blocks_are_drained_in_order_across_callback_boundaries():
    """A device block almost never lines up with a network packet, so reads
    that straddle two queued blocks are the normal case, not an edge one."""
    buffer = make_buffer(target_ms=10)  # 480 frames
    buffer.write(block(300, value=111), pts_us=0)
    buffer.write(block(300, value=222), pts_us=6250)

    written, out = read(buffer, 500)

    assert written == 500
    assert (out[:300] == 111).all()
    assert (out[300:] == 222).all()
    assert buffer.queued_frames == 100


def test_a_read_larger_than_the_queue_pads_with_silence_and_counts_an_underrun():
    buffer = make_buffer(target_ms=10)
    buffer.write(block(500, value=777), pts_us=0)

    written, out = read(buffer, 800)

    assert written == 500
    assert (out[:500] == 777).all()
    assert not out[500:].any()
    assert buffer.underruns == 1


def test_an_underrun_re_primes_so_the_cushion_is_rebuilt():
    """Otherwise the very next callback underruns again, and playback
    stutters continuously instead of recovering once."""
    buffer = make_buffer(target_ms=10)
    buffer.write(block(500), pts_us=0)
    read(buffer, 800)

    assert buffer.priming is True
    buffer.write(block(100), pts_us=0)
    written, _ = read(buffer, 100)
    assert written == 0, "should still be re-priming"

    buffer.write(block(500), pts_us=0)
    written, _ = read(buffer, 100)
    assert written == 100


# ─────────────────────────── drift / overflow ───────────────────────────

def test_the_queue_is_bounded_by_dropping_the_oldest_audio():
    """Clock drift between phone and sound card would otherwise grow the
    queue — and the latency with it — without limit."""
    buffer = make_buffer(target_ms=10, max_ms=50)  # max 2400 frames

    for i in range(10):
        buffer.write(block(500, value=i + 1), pts_us=i * 10_000)

    assert buffer.queued_frames <= buffer.max_frames
    assert buffer.dropped_frames > 0

    # What survived is the *newest* audio: the oldest block is gone.
    _, out = read(buffer, 100)
    assert out[0, 0] != 1


def test_a_steady_stream_within_budget_never_drops():
    buffer = make_buffer(target_ms=60, max_ms=240)

    for i in range(50):
        buffer.write(block(1024), pts_us=i * 21_333)
        read(buffer, 1024)

    assert buffer.dropped_frames == 0
    assert buffer.underruns == 0


# ─────────────────────────── the playout clock ───────────────────────────

def test_no_playout_clock_before_playback_starts():
    """None is what tells AvSync to stop holding video back — it must be the
    answer whenever there is no meaningful position to report."""
    buffer = make_buffer()
    assert buffer.playout_pts_us() is None

    buffer.write(block(100), pts_us=5_000)
    assert buffer.playout_pts_us() is None, "still priming"


def test_playout_clock_advances_with_consumed_samples():
    buffer = make_buffer(target_ms=10)
    buffer.write(block(4800), pts_us=1_000_000)

    assert buffer.playout_pts_us() == 1_000_000

    read(buffer, 480)  # 10ms at 48kHz
    assert buffer.playout_pts_us() == 1_010_000

    read(buffer, 2400)  # 50ms more
    assert buffer.playout_pts_us() == 1_060_000


def test_playout_clock_follows_each_blocks_own_timestamp():
    """Blocks carry their own pts rather than being counted off a running
    total, so a gap in the sender's audio doesn't desync everything after
    it."""
    buffer = make_buffer(target_ms=10)
    buffer.write(block(480), pts_us=0)
    buffer.write(block(480), pts_us=9_000_000)  # a large jump in sender time

    read(buffer, 480)
    assert buffer.playout_pts_us() == 9_000_000


def test_device_latency_is_subtracted_from_the_playout_clock():
    """The clock has to report what is *audible*, not what was last handed to
    the driver — the difference is the video delay AvSync applies."""
    buffer = make_buffer(target_ms=10)
    buffer.write(block(4800), pts_us=1_000_000)

    assert buffer.playout_pts_us(device_latency_us=30_000) == 970_000


def test_playout_clock_returns_to_none_when_the_queue_empties():
    buffer = make_buffer(target_ms=10)
    buffer.write(block(500), pts_us=0)
    read(buffer, 800)

    assert buffer.playout_pts_us() is None


# ─────────────────────────── the other sinks ───────────────────────────

def test_null_sink_records_what_it_was_given():
    sink = NullAudioSink()
    sink.write(block(100), pts_us=42)
    sink.write(block(50), pts_us=99)

    assert sink.total_frames == 150
    assert list(sink.pts) == [42, 99]
    assert sink.playout_pts_us() is None

    sink.close()
    assert sink.closed is True


def test_wav_sink_writes_a_readable_file(tmp_path):
    import wave

    path = tmp_path / "out.wav"
    sink = WavFileAudioSink(str(path), SAMPLE_RATE, CHANNELS)
    sink.write(block(1000, value=1234), pts_us=0)
    sink.close()

    with wave.open(str(path)) as handle:
        assert handle.getnchannels() == CHANNELS
        assert handle.getframerate() == SAMPLE_RATE
        assert handle.getsampwidth() == 2
        assert handle.getnframes() == 1000


def test_wav_sink_ignores_writes_after_close(tmp_path):
    path = tmp_path / "out.wav"
    sink = WavFileAudioSink(str(path), SAMPLE_RATE, CHANNELS)
    sink.close()
    sink.write(block(100), pts_us=0)  # must not raise on a closed file
    sink.close()                      # idempotent


def test_sink_factory_covers_every_kind(tmp_path):
    assert create_audio_sink("none", SAMPLE_RATE, CHANNELS) is None
    assert isinstance(create_audio_sink("null", SAMPLE_RATE, CHANNELS), NullAudioSink)

    wav = create_audio_sink("wav", SAMPLE_RATE, CHANNELS, wav_path=str(tmp_path / "a.wav"))
    assert isinstance(wav, WavFileAudioSink)
    wav.close()

    with pytest.raises(ValueError):
        create_audio_sink("speakers-please", SAMPLE_RATE, CHANNELS)


def test_device_sink_failure_degrades_to_no_audio(monkeypatch):
    """A user on a video call would far rather lose their microphone than
    have the receiver fall over — so an unopenable device returns None."""
    import pc_receiver.audio_sinks as audio_sinks

    def explode(*args, **kwargs):
        raise RuntimeError("no such device")

    monkeypatch.setattr(audio_sinks, "DeviceAudioSink", explode)
    monkeypatch.setattr(audio_sinks, "find_output_device", lambda *a, **k: 3)
    assert create_audio_sink("device", SAMPLE_RATE, CHANNELS) is None


def test_a_failed_ranked_device_retries_the_system_default(monkeypatch):
    """WASAPI shared mode refuses formats a device's mixer isn't configured
    for. Losing the microphone over that would be a poor trade for a few
    milliseconds, so the plain default gets a turn."""
    import pc_receiver.audio_sinks as audio_sinks

    attempts = []

    def picky(sample_rate, channels, device=None, **kwargs):
        attempts.append(device)
        if device is not None:
            raise RuntimeError("unsupported format")
        return NullAudioSink()

    monkeypatch.setattr(audio_sinks, "DeviceAudioSink", picky)
    monkeypatch.setattr(audio_sinks, "find_output_device", lambda *a, **k: 7)

    assert create_audio_sink("device", SAMPLE_RATE, CHANNELS) is not None
    assert attempts == [7, None]


# ────────────────── output device selection ──────────────────
#
# The delay av_sync applies to video is essentially the audio device's
# latency, so picking the wrong route to the right device is a lip-sync bug,
# not a preference. These use a synthetic device table so the ranking is
# tested against the Windows layout regardless of what the test machine has.

def fake_devices():
    """The shape Windows really reports: every device once per host API."""
    return [
        dict(index=0, name="Speakers (Realtek)", hostapi_name="MME",
             default_low_output_latency=0.090),
        dict(index=1, name="CABLE Input (VB-Audio Virtual Cable)", hostapi_name="MME",
             default_low_output_latency=0.090),
        dict(index=2, name="Speakers (Realtek)", hostapi_name="Windows DirectSound",
             default_low_output_latency=0.120),
        dict(index=3, name="CABLE Input (VB-Audio Virtual Cable)", hostapi_name="Windows DirectSound",
             default_low_output_latency=0.120),
        dict(index=4, name="Speakers (Realtek)", hostapi_name="Windows WASAPI",
             default_low_output_latency=0.003),
        dict(index=5, name="CABLE Input (VB-Audio Virtual Cable)", hostapi_name="Windows WASAPI",
             default_low_output_latency=0.003),
        dict(index=6, name="Speakers (Realtek HD Audio output)", hostapi_name="Windows WDM-KS",
             default_low_output_latency=0.010),
    ]


def patch_devices(monkeypatch, devices):
    import pc_receiver.audio_sinks as audio_sinks

    monkeypatch.setattr(audio_sinks, "list_output_devices", lambda: devices)


def test_a_virtual_cable_is_chosen_over_the_speakers(monkeypatch):
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, fake_devices())
    assert find_output_device() == 5  # the CABLE, via WASAPI


def test_no_cable_means_no_device_rather_than_the_speakers(monkeypatch):
    """The receiver auto-starts as a background service, so playing into the
    default output would mean a user's phone mic coming out of their speakers,
    back into the phone — a feedback loop from something they never knowingly
    started. Silence leaves them where they were."""
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, [d for d in fake_devices() if "CABLE" not in d["name"]])

    assert find_output_device() is None
    assert find_output_device(allow_speakers=True) == 4  # opted in: speakers via WASAPI


def test_naming_a_device_is_consent_to_use_it(monkeypatch):
    import pc_receiver.audio_sinks as audio_sinks

    patch_devices(monkeypatch, [d for d in fake_devices() if "CABLE" not in d["name"]])
    opened = []
    monkeypatch.setattr(
        audio_sinks, "DeviceAudioSink",
        lambda sr, ch, device=None, **kw: opened.append(device) or NullAudioSink(),
    )

    assert audio_sinks.create_audio_sink("device", SAMPLE_RATE, CHANNELS, device="Realtek") is not None
    assert opened == [4]


def test_no_cable_and_no_consent_yields_no_audio_sink(monkeypatch):
    import pc_receiver.audio_sinks as audio_sinks

    patch_devices(monkeypatch, [d for d in fake_devices() if "CABLE" not in d["name"]])
    monkeypatch.setattr(
        audio_sinks, "DeviceAudioSink",
        lambda *a, **k: pytest.fail("must not open a device without a cable or explicit consent"),
    )

    assert audio_sinks.create_audio_sink("device", SAMPLE_RATE, CHANNELS) is None


def test_the_fastest_route_to_the_same_device_wins(monkeypatch):
    """The regression this guards: selecting by *name* got MME's copy —
    90ms where WASAPI offers 3ms, and every one of those milliseconds is
    video delay."""
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, fake_devices())
    chosen = find_output_device()

    assert fake_devices()[chosen]["hostapi_name"] == "Windows WASAPI"


def test_an_explicit_preference_still_takes_the_fastest_route(monkeypatch):
    """Naming a device narrows the candidates; it must not drag the choice
    back onto a slow host API."""
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, fake_devices())
    assert find_output_device("cable") == 5


def test_an_unmatched_preference_falls_back_rather_than_failing(monkeypatch):
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, fake_devices())
    # No such device: fall back to the cable search, which does match.
    assert find_output_device("nonexistent device") == 5


def test_exclusive_mode_is_ranked_below_the_default(monkeypatch):
    """WDM-KS is fast but takes the device exclusively, which for a virtual
    cable would lock out the very app meant to be listening to it."""
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, [
        dict(index=0, name="Speakers", hostapi_name="Windows WDM-KS",
             default_low_output_latency=0.010),
        dict(index=1, name="Speakers", hostapi_name="MME",
             default_low_output_latency=0.090),
        dict(index=2, name="Speakers", hostapi_name="Windows WASAPI",
             default_low_output_latency=0.003),
    ])
    assert find_output_device(allow_speakers=True) == 2


def test_non_windows_host_apis_are_left_alone(monkeypatch):
    """Off Windows the ranking must be a no-op, not a wrong guess: with no
    known host API names, the lowest reported latency simply wins."""
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, [
        dict(index=0, name="default", hostapi_name="ALSA", default_low_output_latency=0.020),
        dict(index=1, name="pulse", hostapi_name="ALSA", default_low_output_latency=0.008),
    ])
    assert find_output_device(allow_speakers=True) == 1


def test_no_devices_at_all_hands_the_choice_to_portaudio(monkeypatch):
    from pc_receiver.audio_sinks import find_output_device

    patch_devices(monkeypatch, [])
    assert find_output_device() is None


def test_null_sinks_are_constant_memory_under_a_long_stream():
    """These are `--sink null` / `--audio-sink null` on the command line, not
    just test doubles. Unbounded, the video one grew ~186MB per second at
    1080p; a soak run measured the audio one adding 23MB in two minutes."""
    from pc_receiver.sinks import NullSink

    audio = NullAudioSink()
    for i in range(20_000):
        audio.write(block(1024), pts_us=i)
    assert audio.total_frames == 20_000 * 1024
    assert len(audio.blocks) <= 256

    video = NullSink()
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    for _ in range(5_000):
        video.send(frame, fps=30)
    assert video.total_frames == 5_000
    retained = sum(f.nbytes for f in video.frames)
    assert retained <= 64 * 1024 * 1024, f"null video sink retained {retained/1e6:.0f}MB"
