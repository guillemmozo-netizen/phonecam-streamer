"""Decodes the audio stream the phone sends alongside video.

Counterpart to h264_decoder.py, and used when Hello.audio_codec is non-empty
(see protocol.py). Two codecs, both produced by android-app's AudioEncoder.kt:

  "aac"        ADTS-framed AAC-LC, one ADTS frame per message. This is the
               normal path — MediaCodec's AAC-LC encoder is the one audio
               encoder every Android device is required to have.
  "pcm_s16le"  Raw interleaved 16-bit little-endian samples, the fallback the
               phone drops to when its AAC encoder won't start. No decoding
               to speak of, but it means "the mic works" never depends on a
               vendor codec behaving.

Output is always (n_frames, channels) int16 — one row per sample instant,
interleaved on the second axis — because that is the layout every audio sink
here wants (sounddevice's raw output stream and the `wave` module both take
exactly this) and int16 is what the phone captured in the first place, so the
normal path adds no conversion of its own.

ADTS rather than raw AAC with the encoder's AudioSpecificConfig sent once up
front: every ADTS frame re-states the sample rate, channel count and profile,
so the decoder needs no out-of-band setup and — the part that matters here —
a receiver that starts decoding halfway through a stream still works. That is
not hypothetical: the phone reconnects mid-session (cable pulled, PC asleep,
receiver restarted) and each reconnect gets a brand-new decoder on this side.
A config-up-front format would need that config re-sent on every reconnect and
would decode nothing at all if it were ever missed.
"""

from __future__ import annotations

import logging
from typing import List, Optional

import numpy as np

log = logging.getLogger("pc_receiver.audio_decoder")

PCM_CODEC = "pcm_s16le"
SUPPORTED_CODECS = ("aac", PCM_CODEC)

# One (n, channels) int16 array, reused for every "nothing came out" return so
# the caller can always treat the result as an array without a None check.
_EMPTY = np.zeros((0, 0), dtype=np.int16)


class AudioDecoder:
    """One instance per connection — like H264Decoder, the AAC decoder carries
    state between packets and cannot be reused across streams.

    [sample_rate]/[channels] are what Hello announced. Decoded audio is
    resampled to exactly that, rather than trusted to already match it: the
    sink downstream opens a device (or a .wav header) sized from the same
    hello, and a stream whose real format drifted from its announcement would
    otherwise play at the wrong speed instead of failing visibly.
    """

    def __init__(self, codec: str, sample_rate: int, channels: int) -> None:
        if codec not in SUPPORTED_CODECS:
            raise ValueError(f"unsupported audio codec {codec!r}, expected one of {SUPPORTED_CODECS}")
        if sample_rate <= 0 or channels <= 0:
            raise ValueError(f"invalid audio format {sample_rate}Hz/{channels}ch")

        self.codec = codec
        self.sample_rate = sample_rate
        self.channels = channels
        self.packets_failed = 0
        self._ctx = None
        self._resampler = None

        if codec != PCM_CODEC:
            self._open_av_decoder(codec, sample_rate, channels)

    def _open_av_decoder(self, codec: str, sample_rate: int, channels: int) -> None:
        # Imported here, not at module scope, so the PCM path (and anything
        # that merely imports this module) doesn't require PyAV.
        import av

        self._ctx = av.CodecContext.create(codec, "r")
        # AAC decodes to planar float; the resampler is what turns that into
        # the interleaved int16 the sinks take. It also normalises rate and
        # layout in the same pass, so this is one conversion rather than three.
        self._resampler = av.AudioResampler(
            format="s16",
            layout="mono" if channels == 1 else "stereo",
            rate=sample_rate,
        )
        log.info("AudioDecoder: %s %dHz %dch", codec, sample_rate, channels)

    def decode(self, data: bytes) -> np.ndarray:
        """Decode one message payload into (n_frames, channels) int16.

        Returns an empty array when the payload yields nothing — a truncated
        packet, or an AAC frame the decoder needs more data before it can
        emit. Same policy as the video path: a bad packet costs a few
        milliseconds of audio, never the connection.
        """
        if not data:
            return self._empty()
        if self.codec == PCM_CODEC:
            return self._decode_pcm(data)
        return self._decode_av(data)

    def _decode_pcm(self, data: bytes) -> np.ndarray:
        frame_bytes = 2 * self.channels
        usable = len(data) - (len(data) % frame_bytes)
        if usable <= 0:
            self.packets_failed += 1
            return self._empty()
        # A partial trailing sample means the sender framed something wrong;
        # dropping the remainder keeps every later packet aligned to a sample
        # boundary, which a running offset would not.
        if usable != len(data):
            self.packets_failed += 1
        return np.frombuffer(data[:usable], dtype="<i2").reshape(-1, self.channels)

    def _decode_av(self, data: bytes) -> np.ndarray:
        import av
        import av.error

        if self._ctx is None:
            return self._empty()  # closed: a late packet from a torn-down session
        try:
            frames = self._ctx.decode(av.Packet(data))
        except av.error.FFmpegError:
            self.packets_failed += 1
            return self._empty()
        return self._resample(frames)

    def _resample(self, frames: List["av.AudioFrame"]) -> np.ndarray:
        chunks = []
        for frame in frames:
            for out in self._resampler.resample(frame):
                # Packed s16 comes back as a single interleaved row of
                # samples*channels, not one row per channel — reshaping is
                # what turns it into the (n_frames, channels) contract.
                chunks.append(np.asarray(out.to_ndarray()).reshape(-1, self.channels))
        if not chunks:
            return self._empty()
        if len(chunks) == 1:
            return chunks[0]
        return np.concatenate(chunks, axis=0)

    def flush(self) -> np.ndarray:
        """Drains whatever the decoder and resampler are still holding.

        Mirrors H264Decoder.flush: the last packets of a short session can
        otherwise never come out. Harmless on the PCM path, which holds
        nothing.
        """
        if self._ctx is None:
            return self._empty()
        import av.error

        try:
            frames = self._ctx.decode(None)
        except av.error.FFmpegError:
            return self._empty()
        return self._resample(frames)

    def close(self) -> None:
        """Drops the decoder context. PyAV frees it once nothing holds it —
        there is no close() on a CodecContext to call (see H264Decoder.close,
        which learned this the hard way)."""
        self._ctx = None
        self._resampler = None

    def _empty(self) -> np.ndarray:
        return np.zeros((0, self.channels), dtype=np.int16)


def make_audio_decoder(hello) -> Optional[AudioDecoder]:
    """The decoder a hello calls for, or None when it announced no audio.

    Keeps the "is there audio on this connection at all" decision in one
    place, next to the codec table it depends on, rather than in the receive
    loop. An unsupported codec is logged and downgraded to no-audio, not
    raised: video is the product, and a phone announcing some future codec
    should cost the user their microphone, not their camera.
    """
    if not hello.has_audio:
        return None
    if hello.audio_codec not in SUPPORTED_CODECS:
        log.warning(
            "sender announced unsupported audio codec %r — continuing with video only",
            hello.audio_codec,
        )
        return None
    try:
        return AudioDecoder(hello.audio_codec, hello.audio_sample_rate, hello.audio_channels)
    except Exception:
        log.exception("could not start the audio decoder — continuing with video only")
        return None
