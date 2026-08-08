"""Decodes the phone's AAC stream and plays it out of a chosen PC device.

Why playback and not a virtual microphone: Windows ships no virtual audio
device, and OBS's virtual camera is video-only — there is nothing for the
receiver to "be". What does exist is a well-established free driver, VB-CABLE,
which presents a playback device whose output is a recording device. So the
receiver plays to whichever output device the user names, and naming
"CABLE Input" is what turns the phone's microphone into something Zoom, Meet,
Teams and OBS can select. Naming the speakers instead makes it monitoring.

That is the whole design: this file plays audio to a device, and which device
is a user setting rather than an assumption.

Split in two on purpose:

- AacDecoder is PyAV only, so the decode path is exercised by real tests here.
- AudioOutput wraps sounddevice, which needs PortAudio and a sound card, and
  is therefore imported lazily and never required to merely import this module.
"""

from __future__ import annotations

import logging
from typing import List, Optional

import numpy as np

log = logging.getLogger("pc_receiver.audio_sink")


class AacDecoder:
    """One AAC decoder per session — stateful, like the video one.

    Frames come back as float32 in sounddevice's interleaved (samples,
    channels) layout, because that is the only consumer and converting once
    here beats converting on every callback.
    """

    def __init__(self, sample_rate: int = 48_000, channels: int = 1) -> None:
        import av

        self.sample_rate = sample_rate
        self.channels = channels
        self._ctx = av.CodecContext.create("aac", "r")
        self._configured = False

    def configure(self, codec_config: bytes) -> None:
        """Feed AAC's AudioSpecificConfig (MediaCodec's csd-0).

        AAC in this protocol is raw access units with no ADTS headers, so the
        decoder cannot infer the profile, rate or channel layout from the
        bitstream — without this it fails on every frame. The phone sends it
        before the first audio frame and again after any reconfiguration.
        """
        if not codec_config:
            return
        self._ctx.extradata = codec_config
        self._configured = True

    def decode(self, data: bytes) -> List[np.ndarray]:
        """Any PCM that came out, as (samples, channels) float32 arrays."""
        import av
        import av.error

        if not self._configured:
            # Decoding before the config arrived produces nothing useful and
            # fills the log with errors; dropping is what the video path does
            # with pre-SPS/PPS data too.
            return []
        try:
            frames = self._ctx.decode(av.Packet(data))
        except av.error.FFmpegError:
            return []
        return [self._to_float32(frame) for frame in frames]

    def flush(self) -> List[np.ndarray]:
        import av
        import av.error

        if not self._configured:
            return []
        try:
            frames = self._ctx.decode(None)
        except (av.error.FFmpegError, EOFError):
            return []
        return [self._to_float32(frame) for frame in frames]

    @staticmethod
    def _to_float32(frame) -> np.ndarray:
        """PyAV's (channels, samples) planar output -> interleaved float32.

        AAC decodes to fltp, so to_ndarray gives one row per channel. Anything
        integer-formatted is scaled by its own dtype range rather than an
        assumed 16-bit one, so a decoder that hands back s32 does not come out
        32768x too quiet.
        """
        array = frame.to_ndarray()
        if array.dtype.kind in "iu":
            array = array.astype(np.float32) / float(np.iinfo(array.dtype).max)
        else:
            array = array.astype(np.float32, copy=False)
        if array.ndim == 1:
            return array.reshape(-1, 1)
        return np.ascontiguousarray(array.T)

    def close(self) -> None:
        self._ctx = None


def list_output_devices() -> List[dict]:
    """Playback devices, as {index, name, channels, default}.

    Returns an empty list rather than raising when PortAudio is missing or no
    sound card exists — the receiver must keep handling video on a PC with no
    audio output at all.
    """
    try:
        import sounddevice
    except Exception as e:  # ImportError, or OSError when PortAudio is absent
        log.warning("audio output unavailable (%s)", e)
        return []

    try:
        default_output = sounddevice.default.device[1]
        devices = []
        for index, device in enumerate(sounddevice.query_devices()):
            if device.get("max_output_channels", 0) <= 0:
                continue
            devices.append({
                "index": index,
                "name": device.get("name", f"device {index}"),
                "channels": device["max_output_channels"],
                "default": index == default_output,
            })
        return devices
    except Exception as e:
        log.warning("could not enumerate audio output devices (%s)", e)
        return []


def resolve_output_device(requested: Optional[str], devices: List[dict]) -> Optional[int]:
    """Index of the device [requested] names, or None for the system default.

    Accepts an index as a string, an exact name, or a case-insensitive
    substring — so "cable" finds "CABLE Input (VB-Audio Virtual Cable)", which
    is the name users will actually be looking at and the one they will get
    wrong when typing it by hand.
    """
    if requested is None or not str(requested).strip():
        return None
    wanted = str(requested).strip()

    if wanted.lstrip("-").isdigit():
        index = int(wanted)
        if any(d["index"] == index for d in devices):
            return index
        log.warning("no output device with index %s; using the system default", index)
        return None

    for device in devices:
        if device["name"].lower() == wanted.lower():
            return device["index"]
    matches = [d for d in devices if wanted.lower() in d["name"].lower()]
    if len(matches) == 1:
        return matches[0]["index"]
    if len(matches) > 1:
        # Ambiguity resolved by picking nothing: silently choosing one of
        # several matches is how audio ends up in the wrong application.
        log.warning(
            "%r matches %d output devices (%s); using the system default",
            wanted, len(matches), ", ".join(d["name"] for d in matches),
        )
        return None
    log.warning("no output device matching %r; using the system default", wanted)
    return None


class AudioOutput:
    """Plays decoded PCM to one output device.

    Opened lazily on the first write: the device is a shared resource and a
    session that never carries audio should not hold one open.
    """

    def __init__(
        self,
        sample_rate: int,
        channels: int,
        device: Optional[str] = None,
        blocksize: int = 0,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.device_request = device
        self.device_index: Optional[int] = None
        self.blocksize = blocksize
        self._stream = None
        self._failed = False

    def _open(self) -> bool:
        if self._stream is not None:
            return True
        if self._failed:
            return False
        try:
            import sounddevice
        except Exception as e:
            log.warning("no audio output: %s", e)
            self._failed = True
            return False

        self.device_index = resolve_output_device(self.device_request, list_output_devices())
        try:
            self._stream = sounddevice.OutputStream(
                samplerate=self.sample_rate,
                channels=self.channels,
                dtype="float32",
                device=self.device_index,
                blocksize=self.blocksize,
            )
            self._stream.start()
        except Exception as e:
            # A wrong rate, a device in exclusive use, no device at all — none
            # of which should take the video stream down with them.
            log.warning("could not open audio output (%s); audio will be dropped", e)
            self._stream = None
            self._failed = True
            return False

        log.info(
            "audio out: device=%s %dHz %dch",
            self.device_index if self.device_index is not None else "system default",
            self.sample_rate, self.channels,
        )
        return True

    def write(self, pcm: np.ndarray) -> None:
        if not self._open():
            return
        try:
            # Channel count is fixed at open time, so a stream that decodes to
            # a different one is adapted rather than dropped: mono into a
            # stereo stream is duplicated, extra channels are trimmed.
            if pcm.shape[1] != self.channels:
                if pcm.shape[1] == 1:
                    pcm = np.repeat(pcm, self.channels, axis=1)
                else:
                    pcm = pcm[:, : self.channels]
            self._stream.write(np.ascontiguousarray(pcm, dtype=np.float32))
        except Exception as e:
            log.warning("audio write failed (%s); dropping audio for this session", e)
            self.close()
            self._failed = True

    def close(self) -> None:
        stream, self._stream = self._stream, None
        if stream is None:
            return
        try:
            stream.stop()
            stream.close()
        except Exception:
            pass
