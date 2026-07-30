"""Output sinks for decoded audio.

Mirrors sinks.py's split: the transport/decoding layers never touch a sound
device directly, so every one of them can be tested with NullAudioSink or
WavFileAudioSink on a machine with no audio hardware at all.

## Where the audio actually has to end up

Video has somewhere obvious to go — pyvirtualcam publishes a system webcam
that Zoom/Meet/OBS just see. There is no equivalent for audio: OBS's virtual
camera carries no audio track, and nothing in the Python ecosystem can
register a system *microphone*. That is a kernel-mode driver on Windows, and
it is why DroidCam ships one of its own.

So the honest arrangement, and what [DeviceAudioSink] implements, is: play
the phone's audio into a virtual audio cable (VB-CABLE, VoiceMeeter) that the
user installs once, and their conferencing app then picks that cable as its
microphone. [find_output_device] looks for exactly those devices by name so
the common setup needs no configuration.

Falling back to the default output device is supported but warned about
loudly — it means the phone's microphone comes out of the PC's speakers,
which is a feedback loop, not a webcam.
"""

from __future__ import annotations

import logging
import threading
import wave
from collections import deque
from typing import Deque, List, Optional, Protocol, Tuple

import numpy as np

log = logging.getLogger("pc_receiver.audio_sinks")

# Names of the virtual audio cables worth auto-selecting, most specific first.
# These are the two that people actually install on Windows for this job.
VIRTUAL_CABLE_HINTS = ("cable input", "voicemeeter input", "voicemeeter aux input", "virtual audio")


class AudioSink(Protocol):
    def write(self, samples: np.ndarray, pts_us: int) -> None: ...

    def playout_pts_us(self) -> Optional[int]: ...

    def close(self) -> None: ...


# Matches sinks._RETAINED_FRAMES in intent: keep enough for any assertion,
# not enough to matter. 256 AAC packets is ~5.5 seconds of audio.
_RETAINED_BLOCKS = 256


class NullAudioSink:
    """Discards audio, retaining only the most recent blocks for inspection.

    Bounded for the same reason NullSink is: this is `--audio-sink null` on
    the command line, not merely a test double. Unbounded, it grew by the
    full bitrate of the stream — a soak run measured exactly 23MB of "leak"
    in two minutes and it was entirely this list.

    playout_pts_us() returns None — "no clock here" — which is what makes
    av_sync release video immediately in tests that aren't about sync.
    """

    def __init__(self) -> None:
        self.blocks: Deque[np.ndarray] = deque(maxlen=_RETAINED_BLOCKS)
        self.pts: Deque[int] = deque(maxlen=_RETAINED_BLOCKS)
        self.closed = False
        self.total_frames = 0

    def write(self, samples: np.ndarray, pts_us: int) -> None:
        self.blocks.append(samples)
        self.pts.append(pts_us)
        self.total_frames += samples.shape[0]

    def playout_pts_us(self) -> Optional[int]:
        return None

    def close(self) -> None:
        self.closed = True


class WavFileAudioSink:
    """Writes the stream to a .wav file.

    The one sink that proves the whole audio path end to end without a driver,
    a cable or a speaker: run the receiver with --audio-sink wav, stream, and
    listen to the file. Also how an A/V sync problem gets diagnosed offline,
    since the file is exactly what arrived.
    """

    def __init__(self, path: str, sample_rate: int, channels: int) -> None:
        self._wave = wave.open(path, "wb")
        self._wave.setnchannels(channels)
        self._wave.setsampwidth(2)  # int16, the only width this pipeline carries
        self._wave.setframerate(sample_rate)
        self._closed = False
        self.path = path
        log.info("writing audio to %s (%dHz %dch)", path, sample_rate, channels)

    def write(self, samples: np.ndarray, pts_us: int) -> None:
        if self._closed:
            return
        self._wave.writeframes(np.ascontiguousarray(samples, dtype=np.int16).tobytes())

    def playout_pts_us(self) -> Optional[int]:
        # A file has no playout position; reporting None keeps video flowing
        # at full speed instead of pacing it to a write that never blocks.
        return None

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._wave.close()


class PlayoutBuffer:
    """The jitter buffer between "audio arrived over TCP" and "the sound card
    wants a block right now".

    Deliberately knows nothing about sounddevice, so all of it — the
    underrun path, the drift-dropping, the playout clock — is testable with
    plain arrays. [DeviceAudioSink] is then only the wiring.

    ## The buffer depth is the whole trade-off

    A sound device pulls fixed-size blocks on its own thread, on its own
    clock, and will not wait: whatever is not ready when it asks becomes
    silence, heard as a click. Audio arriving over TCP is jittery by nature.
    So samples queue up here and the device drains them, with the depth
    traded directly against latency:

      too shallow  every network hiccup is an audible click
      too deep     lip-sync costs that much video delay (see av_sync.py)

    [target_buffer_ms] is that trade-off, defaulted low because this is a live
    webcam rather than playback.

    ## Two clocks that never quite agree

    The phone samples at its idea of 48kHz and the sound card plays at its
    own; they drift by a few samples a minute. Left alone the queue would
    creep towards either empty (clicks forever) or full (growing delay).
    [_enforce_bounds] handles it the blunt but robust way — past
    [max_buffer_ms], the oldest audio is dropped. Dropping a few milliseconds
    an hour is inaudible; the growing delay it prevents is not.
    """

    def __init__(
        self,
        sample_rate: int,
        channels: int,
        target_buffer_ms: int = 60,
        max_buffer_ms: int = 240,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.target_frames = max(1, sample_rate * target_buffer_ms // 1000)
        self.max_frames = max(self.target_frames * 2, sample_rate * max_buffer_ms // 1000)

        self._lock = threading.Lock()
        # (block, pts_us of that block's first sample), oldest first.
        self._queue: Deque[Tuple[np.ndarray, int]] = deque()
        self._queued_frames = 0
        self._head_offset = 0          # samples already taken from _queue[0]
        # Silence until the queue first reaches target_frames: starting to
        # play on the very first packet guarantees an immediate underrun.
        self._priming = True

        self.underruns = 0
        self.dropped_frames = 0

    @property
    def queued_frames(self) -> int:
        with self._lock:
            return self._queued_frames

    @property
    def priming(self) -> bool:
        with self._lock:
            return self._priming

    def write(self, samples: np.ndarray, pts_us: int) -> None:
        if samples.size == 0:
            return
        block = np.ascontiguousarray(samples, dtype=np.int16)
        with self._lock:
            self._queue.append((block, pts_us))
            self._queued_frames += block.shape[0]
            self._enforce_bounds()
            if self._priming and self._queued_frames >= self.target_frames:
                self._priming = False

    def read_into(self, outdata: np.ndarray) -> int:
        """Fill one device block, padding with silence if the queue runs dry.
        Returns how many real (non-silence) sample frames were written.

        Runs on the device's own high-priority callback thread: allocates
        nothing and blocks on nothing but this one uncontended lock, because
        overrunning that callback produces exactly the clicks this buffer
        exists to prevent.
        """
        frames = outdata.shape[0]
        with self._lock:
            if self._priming:
                outdata.fill(0)
                return 0

            written = 0
            while written < frames and self._queue:
                block, _ = self._queue[0]
                take = min(block.shape[0] - self._head_offset, frames - written)
                outdata[written:written + take] = block[self._head_offset:self._head_offset + take]
                written += take
                self._head_offset += take
                self._queued_frames -= take
                if self._head_offset >= block.shape[0]:
                    self._queue.popleft()
                    self._head_offset = 0

            if written < frames:
                # Ran dry. Silence for the remainder, and re-prime so the next
                # refill rebuilds a full cushion instead of underrunning again
                # on the very next callback.
                outdata[written:].fill(0)
                self.underruns += 1
                self._priming = True
            return written

    def playout_pts_us(self, device_latency_us: int = 0) -> Optional[int]:
        """Phone-clock timestamp of the audio being heard right now, or None
        while nothing is playing yet.

        This is the reading av_sync paces video against, so it is deliberately
        the *audible* position: the sample about to be handed to the device,
        pushed back by the device's own output latency.
        """
        with self._lock:
            if self._priming or not self._queue:
                return None
            _, pts_us = self._queue[0]
            offset_us = self._head_offset * 1_000_000 // self.sample_rate
            return pts_us + offset_us - device_latency_us

    def _enforce_bounds(self) -> None:
        """Caller must hold _lock. Drops the oldest audio once the queue has
        grown past what the latency budget allows."""
        while self._queued_frames > self.max_frames and self._queue:
            block, _ = self._queue[0]
            available = block.shape[0] - self._head_offset
            self._queue.popleft()
            self._queued_frames -= available
            self._head_offset = 0
            self.dropped_frames += available


class DeviceAudioSink:
    """Plays decoded audio through a system output device.

    All of the interesting behaviour lives in [PlayoutBuffer]; this class is
    the sounddevice wiring around it.
    """

    def __init__(
        self,
        sample_rate: int,
        channels: int,
        device: Optional[object] = None,
        target_buffer_ms: int = 60,
        max_buffer_ms: int = 240,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.buffer = PlayoutBuffer(sample_rate, channels, target_buffer_ms, max_buffer_ms)
        self._closed = False

        import sounddevice as sd  # lazy: only the real device path needs it

        self._stream = sd.OutputStream(
            samplerate=sample_rate,
            channels=channels,
            dtype="int16",
            device=device,
            # The device's low-latency setting rather than its default, which
            # is the *high* one — 180ms instead of 90ms on Windows MME, and
            # every millisecond here is a millisecond av_sync must delay video
            # by. Our own jitter buffer is what absorbs network jitter; the
            # driver does not need to do it a second time.
            latency="low",
            callback=self._callback,
        )
        self._stream.start()
        # PortAudio's own reported output latency: the gap between handing it
        # a sample and that sample being audible. Part of the playout clock,
        # and typically the larger part of it on Windows' shared-mode WASAPI.
        self._device_latency_us = int(self._stream.latency * 1_000_000)
        log.info(
            "audio device open: %dHz %dch target_buffer=%dms device_latency=%.0fms",
            sample_rate, channels, target_buffer_ms, self._device_latency_us / 1000,
        )

    def write(self, samples: np.ndarray, pts_us: int) -> None:
        if self._closed:
            return
        self.buffer.write(samples, pts_us)

    def playout_pts_us(self) -> Optional[int]:
        if self._closed:
            return None
        return self.buffer.playout_pts_us(self._device_latency_us)

    def _callback(self, outdata: np.ndarray, frames: int, time_info, status) -> None:
        self.buffer.read_into(outdata)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._stream.stop()
            self._stream.close()
        except Exception:
            log.exception("closing the audio device failed")
        if self.buffer.underruns or self.buffer.dropped_frames:
            log.info(
                "audio device closed: %d underrun(s), %d frame(s) dropped to hold latency",
                self.buffer.underruns, self.buffer.dropped_frames,
            )


# Host-API preference, lowest number wins. This is the single biggest lever
# on A/V sync latency there is, because the delay av_sync applies to video is
# essentially the audio device's own latency — and on Windows the host APIs
# differ by more than an order of magnitude for the *same* physical device.
# Measured on the reference PC:
#
#     Windows WASAPI          3ms low / 10ms high
#     Windows WDM-KS         10ms      / 40-85ms
#     MME                    90ms      / 180ms      <- PortAudio's default
#     Windows DirectSound   120ms      / 240ms
#
# WASAPI in shared mode is both the fastest and the one that coexists with
# other applications. WDM-KS is comparably fast but takes the device
# exclusively, which for a virtual cable would lock out the very app meant to
# be listening to it, so it ranks below the neutral default. Anything not
# named here — ALSA, CoreAudio, JACK — scores as neutral, so this whole
# ranking is a no-op off Windows rather than a wrong guess about it.
_NEUTRAL_HOST_API_RANK = 1
_HOST_API_RANK = {
    "windows wasapi": 0,
    "windows wdm-ks": 2,
    "windows directsound": 3,
    "mme": 4,
}


def list_output_devices() -> List[dict]:
    """Every output-capable device, as sounddevice reports it, each with its
    PortAudio index and host API name attached.

    Empty if sounddevice isn't installed — this is used for diagnostics and
    for auto-selection, neither of which should be able to break the receiver.
    """
    try:
        import sounddevice as sd

        host_apis = sd.query_hostapis()
        devices = []
        for index, device in enumerate(sd.query_devices()):
            if device.get("max_output_channels", 0) <= 0:
                continue
            entry = dict(device)
            entry["index"] = index
            entry["hostapi_name"] = host_apis[device["hostapi"]]["name"]
            devices.append(entry)
        return devices
    except Exception:
        log.exception("could not enumerate audio output devices")
        return []


def _device_rank(device: dict) -> tuple:
    """Sort key: best host API first, then lowest reported latency."""
    return (
        _HOST_API_RANK.get(device["hostapi_name"].lower(), _NEUTRAL_HOST_API_RANK),
        device.get("default_low_output_latency", 1.0),
    )


def find_output_device(preferred: Optional[str] = None, allow_speakers: bool = False) -> Optional[int]:
    """Pick the device to play into, returning its PortAudio **index**, or
    None if there is nothing suitable to play into at all.

    An index, not a name, and that is the whole point of this function.
    Windows exposes the same physical device once per host API — "Speakers"
    exists under MME, DirectSound, WASAPI and WDM-KS — and asking sounddevice
    for a device *by name* gets whichever it enumerated first, which is MME:
    90ms of latency where WASAPI offers 3ms. Since that latency is exactly
    what av_sync has to delay video by, selecting by name was silently
    costing ~180ms of video delay on every session.

    An explicit [preferred] substring narrows the candidates but does not
    override the host-API ranking, so asking for "CABLE" still gets the
    fastest route to that cable rather than the first one listed.

    [allow_speakers] is what makes an ordinary output device acceptable when
    no virtual cable exists. It is off by default on purpose — see
    create_audio_sink.
    """
    devices = list_output_devices()
    if not devices:
        return None

    candidates: List[dict] = []
    if preferred:
        needle = preferred.lower()
        candidates = [d for d in devices if needle in d["name"].lower()]
        if not candidates:
            log.warning("no audio output device matching %r; falling back", preferred)
    if not candidates:
        candidates = [
            d for d in devices
            if any(hint in d["name"].lower() for hint in VIRTUAL_CABLE_HINTS)
        ]
    if not candidates:
        if not allow_speakers:
            return None
        # Still worth ranking: the same speakers via WASAPI beat MME by ~90ms
        # of video delay, cable or no cable.
        candidates = devices

    best = min(candidates, key=_device_rank)
    log.info(
        "audio output device: %s via %s (%.0fms) [index %d]",
        best["name"], best["hostapi_name"],
        best.get("default_low_output_latency", 0) * 1000, best["index"],
    )
    return best["index"]


def create_audio_sink(
    kind: str,
    sample_rate: int,
    channels: int,
    device: Optional[str] = None,
    wav_path: str = "phonecam_audio.wav",
    allow_speakers: bool = False,
) -> Optional[AudioSink]:
    """Build the audio sink named by [kind], or None for "no audio output".

    Returns None rather than raising when a device sink can't be opened: the
    user is on a video call, and losing the microphone is much better than
    losing the call. The failure is logged with the reason.

    ## Why no virtual cable means no audio, rather than the speakers

    Playing into the default output device sounds like a friendly fallback
    and is not. This receiver auto-starts as a background service whenever
    OBS opens (see Install_PhoneCam.vbs), so that fallback means: the moment
    a user streams, their phone's microphone starts coming out of their PC's
    speakers, into their phone's microphone, out of their speakers. A
    feedback loop nobody asked for, from a service they did not knowingly
    start, with no window to close.

    Staying silent leaves them exactly where they were before audio existed.
    [allow_speakers] (or naming a device outright) opts into it deliberately,
    which is the right shape for something only useful while debugging.
    """
    if kind == "none":
        return None
    if kind == "null":
        return NullAudioSink()
    if kind == "wav":
        return WavFileAudioSink(wav_path, sample_rate, channels)
    if kind == "device":
        # Naming a device is itself an explicit choice, so it implies consent
        # to whatever that device is.
        chosen = find_output_device(device, allow_speakers=allow_speakers or bool(device))
        if chosen is None:
            log.warning(
                "no virtual audio cable installed — audio will not be played, so the phone's "
                "microphone can't echo out of this PC's speakers. Install VB-CABLE (or "
                "VoiceMeeter) and it is picked up automatically; see docs/AUDIO.md, or run "
                "`python -m pc_receiver.check_audio_setup`. To play out of the speakers anyway, "
                "pass --audio-speakers or --audio-device NAME."
            )
            return None
        try:
            return DeviceAudioSink(sample_rate, channels, device=chosen)
        except Exception:
            log.warning(
                "could not open the selected audio device — retrying with the system default",
                exc_info=True,
            )
        # The ranked choice can fail where the plain default succeeds: WASAPI
        # shared mode refuses formats the device's mixer isn't configured for
        # (an odd sample rate, or more channels than it has). Losing the
        # microphone over that would be a poor trade for a few milliseconds.
        try:
            return DeviceAudioSink(sample_rate, channels, device=None)
        except Exception:
            log.exception("could not open an audio output device — continuing with video only")
            return None
    raise ValueError(f"unknown audio sink kind: {kind}")
