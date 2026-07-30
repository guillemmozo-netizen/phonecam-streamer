"""Audio decode tests.

The AAC cases encode with PyAV and decode with the real AudioDecoder, the
same encode->decode round trip test_h264_decoder.py runs for video: PyAV
stands in for the phone's MediaCodec, so what is under test is genuine AAC
rather than a mock of it, and the ADTS framing the phone will produce is
proven to be decodable here before any phone exists to produce it.
"""

import av
import numpy as np
import pytest
from fractions import Fraction

from pc_receiver.aac import AdtsError, adts_header, parse_adts_header, wrap_adts
from pc_receiver.audio_decoder import AudioDecoder, make_audio_decoder
from pc_receiver.protocol import Hello

SAMPLE_RATE = 48000
CHANNELS = 2
AAC_FRAME_SAMPLES = 1024


def encode_aac(seconds: float = 0.5, sample_rate: int = SAMPLE_RATE, channels: int = CHANNELS):
    """ADTS frames of a 440Hz tone — what the phone puts on the wire."""
    encoder = av.CodecContext.create("aac", "w")
    encoder.sample_rate = sample_rate
    encoder.format = "fltp"
    encoder.layout = "mono" if channels == 1 else "stereo"
    encoder.bit_rate = 192_000

    total = int(sample_rate * seconds)
    t = np.arange(total, dtype=np.float64) / sample_rate
    tone = (0.4 * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32)

    packets = []
    for offset in range(0, total - AAC_FRAME_SAMPLES, AAC_FRAME_SAMPLES):
        block = np.stack([tone[offset:offset + AAC_FRAME_SAMPLES]] * channels)
        frame = av.AudioFrame.from_ndarray(block, format="fltp", layout=encoder.layout)
        frame.sample_rate = sample_rate
        frame.pts = offset
        frame.time_base = Fraction(1, sample_rate)
        packets.extend(encoder.encode(frame))
    packets.extend(encoder.encode(None))
    return [wrap_adts(bytes(p), sample_rate, channels) for p in packets]


# ─────────────────────────── ADTS framing ───────────────────────────

def test_adts_header_round_trips_through_its_own_parser():
    header = adts_header(payload_len=300, sample_rate=48000, channels=2)
    info = parse_adts_header(header)

    assert len(header) == 7
    assert info.sample_rate == 48000
    assert info.channels == 2
    assert info.frame_length == 307  # payload + the 7 header bytes


@pytest.mark.parametrize("sample_rate", [8000, 16000, 22050, 44100, 48000, 96000])
@pytest.mark.parametrize("channels", [1, 2])
def test_adts_header_encodes_every_format_the_phone_can_capture(sample_rate, channels):
    info = parse_adts_header(adts_header(1000, sample_rate, channels))
    assert (info.sample_rate, info.channels) == (sample_rate, channels)


def test_adts_header_rejects_impossible_formats():
    with pytest.raises(AdtsError):
        adts_header(100, sample_rate=48001, channels=2)   # not an MPEG-4 rate
    with pytest.raises(AdtsError):
        adts_header(100, sample_rate=48000, channels=0)
    with pytest.raises(AdtsError):
        adts_header(1 << 13, sample_rate=48000, channels=2)  # overflows the length field


def test_parse_rejects_bytes_that_are_not_an_adts_header():
    with pytest.raises(AdtsError):
        parse_adts_header(b"\x00\x00\x00\x00\x00\x00\x00")
    with pytest.raises(AdtsError):
        parse_adts_header(b"\xff")


# ─────────────────────────── AAC decode ───────────────────────────

def test_aac_round_trip_produces_the_expected_amount_of_audio():
    packets = encode_aac(seconds=0.5)
    decoder = AudioDecoder("aac", SAMPLE_RATE, CHANNELS)

    total = sum(decoder.decode(p).shape[0] for p in packets)
    total += decoder.flush().shape[0]

    # AAC's encoder delay means the count is close to, not exactly, the input.
    assert 0.4 * SAMPLE_RATE < total < 0.55 * SAMPLE_RATE
    assert decoder.packets_failed == 0


def test_aac_decode_output_shape_and_dtype_match_the_sink_contract():
    packets = encode_aac(seconds=0.2)
    decoder = AudioDecoder("aac", SAMPLE_RATE, CHANNELS)

    for packet in packets:
        samples = decoder.decode(packet)
        if samples.shape[0]:
            assert samples.ndim == 2
            assert samples.shape[1] == CHANNELS
            assert samples.dtype == np.int16
            return
    pytest.fail("no audio came out of the decoder at all")


def test_aac_decode_recovers_the_actual_tone():
    """Not just "some bytes came back" — the 440Hz peak has to survive the
    encode/frame/decode round trip, which is what proves the ADTS headers
    describe the payload correctly rather than merely parsing."""
    packets = encode_aac(seconds=0.5)
    decoder = AudioDecoder("aac", SAMPLE_RATE, CHANNELS)

    chunks = [decoder.decode(p) for p in packets]
    chunks.append(decoder.flush())
    audio = np.concatenate([c for c in chunks if c.shape[0]], axis=0)

    # Skip the encoder's priming samples, then find the dominant frequency.
    window = audio[SAMPLE_RATE // 10 : SAMPLE_RATE // 10 + 8192, 0].astype(np.float64)
    spectrum = np.abs(np.fft.rfft(window * np.hanning(len(window))))
    peak_hz = np.fft.rfftfreq(len(window), 1 / SAMPLE_RATE)[np.argmax(spectrum)]

    assert abs(peak_hz - 440.0) < 15.0


def test_mono_is_decoded_as_a_single_channel():
    packets = encode_aac(seconds=0.2, channels=1)
    decoder = AudioDecoder("aac", SAMPLE_RATE, 1)

    audio = np.concatenate([decoder.decode(p) for p in packets] + [decoder.flush()], axis=0)
    assert audio.shape[1] == 1
    assert audio.shape[0] > 0


def test_corrupt_aac_packet_is_dropped_not_raised():
    """A bad packet costs a few milliseconds of audio; it must never cost the
    connection — same policy as a corrupt JPEG on the video side."""
    decoder = AudioDecoder("aac", SAMPLE_RATE, CHANNELS)

    assert decoder.decode(b"definitely not aac").shape[0] == 0
    assert decoder.decode(b"").shape[0] == 0

    # ...and a real packet still decodes afterwards.
    packets = encode_aac(seconds=0.2)
    assert sum(decoder.decode(p).shape[0] for p in packets) > 0


def test_decode_after_close_returns_nothing_instead_of_crashing():
    decoder = AudioDecoder("aac", SAMPLE_RATE, CHANNELS)
    decoder.close()

    assert decoder.decode(encode_aac(seconds=0.1)[0]).shape[0] == 0
    assert decoder.flush().shape[0] == 0


# ─────────────────────────── PCM fallback ───────────────────────────

def test_pcm_passthrough_reinterprets_bytes_as_interleaved_samples():
    decoder = AudioDecoder("pcm_s16le", SAMPLE_RATE, 2)
    original = np.array([[1, -1], [32767, -32768], [100, 200]], dtype=np.int16)

    decoded = decoder.decode(original.tobytes())

    assert decoded.shape == (3, 2)
    assert np.array_equal(decoded, original)


def test_pcm_with_a_partial_trailing_sample_drops_the_remainder():
    """Keeping every later packet aligned to a sample boundary matters more
    than the half sample that gets dropped."""
    decoder = AudioDecoder("pcm_s16le", SAMPLE_RATE, 2)

    decoded = decoder.decode(np.array([1, 2, 3, 4], dtype=np.int16).tobytes() + b"\x01")

    assert decoded.shape == (2, 2)
    assert decoder.packets_failed == 1


def test_unsupported_codec_is_rejected_at_construction():
    with pytest.raises(ValueError):
        AudioDecoder("mp3", SAMPLE_RATE, CHANNELS)
    with pytest.raises(ValueError):
        AudioDecoder("aac", 0, CHANNELS)


# ─────────────────────────── decoder selection ───────────────────────────

def _hello(**kwargs) -> Hello:
    base = dict(width=640, height=480, fps=30, quality="1080p60", watermark=False)
    base.update(kwargs)
    return Hello(**base)


def test_no_decoder_for_a_sender_without_audio():
    assert make_audio_decoder(_hello()) is None


def test_decoder_is_built_from_the_hello_format():
    decoder = make_audio_decoder(
        _hello(audio_codec="aac", audio_sample_rate=44100, audio_channels=1)
    )

    assert decoder is not None
    assert (decoder.codec, decoder.sample_rate, decoder.channels) == ("aac", 44100, 1)


def test_unknown_codec_degrades_to_video_only_rather_than_failing():
    """A phone announcing some future codec should cost the user their
    microphone, not their camera."""
    assert make_audio_decoder(
        _hello(audio_codec="future-codec", audio_sample_rate=48000, audio_channels=2)
    ) is None
