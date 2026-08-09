"""Audio path: the wire framing that carries it, and the decoder at the far end."""

import socket

import numpy as np
import pytest

from pc_receiver.audio_sink import AacDecoder, resolve_output_device
from pc_receiver.protocol import (
    Hello,
    MessageType,
    ProtocolError,
    FrameReader,
    recv_message,
    send_frame,
    send_hello,
    send_typed_message,
    split_typed_message,
)

SAMPLE_RATE = 48_000
CHANNELS = 1


def _encode_aac(num_frames: int, sample_rate: int = SAMPLE_RATE, channels: int = CHANNELS):
    """(codec_config, [access units]) for a synthetic tone.

    Stands in for what the phone's MediaCodec AAC encoder produces: raw access
    units plus a separate AudioSpecificConfig, no ADTS headers.
    """
    import av

    encoder = av.CodecContext.create("aac", "w")
    encoder.sample_rate = sample_rate
    encoder.format = "fltp"
    encoder.layout = "mono" if channels == 1 else "stereo"

    packets = []
    samples_per_frame = 1024
    for i in range(num_frames):
        t = (np.arange(samples_per_frame) + i * samples_per_frame) / sample_rate
        tone = (0.25 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        planes = np.repeat(tone.reshape(1, -1), channels, axis=0)
        frame = av.AudioFrame.from_ndarray(
            planes, format="fltp", layout="mono" if channels == 1 else "stereo"
        )
        frame.sample_rate = sample_rate
        frame.pts = i * samples_per_frame
        for packet in encoder.encode(frame):
            packets.append(bytes(packet))
    for packet in encoder.encode(None):
        packets.append(bytes(packet))

    return bytes(encoder.extradata or b""), packets


# ---- framing ----


def test_audio_off_keeps_the_original_framing():
    """The default has to stay byte-for-byte what it was, or every sender that
    predates audio starts sending something the receiver can't read."""
    hello = Hello(width=64, height=48, fps=30, quality="q", watermark=False)
    assert hello.audio is False

    server, client = socket.socketpair()
    send_frame(client, b"just-a-video-frame")
    assert recv_message(server) == b"just-a-video-frame"
    server.close()
    client.close()


def test_typed_messages_round_trip():
    server, client = socket.socketpair()
    reader = FrameReader(server)

    send_typed_message(client, MessageType.AUDIO_CONFIG, b"csd")
    send_typed_message(client, MessageType.VIDEO, b"frame")
    send_typed_message(client, MessageType.AUDIO, b"aac")

    assert split_typed_message(reader.recv_message()) == (MessageType.AUDIO_CONFIG, b"csd")
    assert split_typed_message(reader.recv_message()) == (MessageType.VIDEO, b"frame")
    assert split_typed_message(reader.recv_message()) == (MessageType.AUDIO, b"aac")

    server.close()
    client.close()


def test_an_unknown_message_type_is_rejected_not_guessed():
    """Treating an unrecognised type as video would push noise into the user's
    virtual camera."""
    with pytest.raises(ProtocolError):
        split_typed_message(bytes((0x7F,)) + b"whatever")


def test_an_empty_typed_message_is_rejected():
    with pytest.raises(ProtocolError):
        split_typed_message(b"")


def test_audio_fields_survive_a_hello_round_trip():
    hello = Hello(
        width=1920, height=1080, fps=60, quality="1080p60", watermark=False,
        audio=True, audio_codec="aac", audio_sample_rate=16_000,
        audio_channels=2, audio_bitrate_bps=96_000,
    )
    parsed = Hello.from_json_bytes(hello.to_json_bytes())
    assert parsed.audio is True
    assert parsed.audio_sample_rate == 16_000
    assert parsed.audio_channels == 2
    assert parsed.audio_bitrate_bps == 96_000


def test_absurd_audio_parameters_are_rejected():
    """The receiver opens an output device with these numbers, so a bogus rate
    is a broken device rather than a merely odd setting."""
    for payload in (
        b'{"width":64,"height":48,"fps":30,"quality":"q","watermark":false,"audio_sample_rate":999999999}',
        b'{"width":64,"height":48,"fps":30,"quality":"q","watermark":false,"audio_channels":64}',
        b'{"width":64,"height":48,"fps":30,"quality":"q","watermark":false,"audio_bitrate_bps":0}',
    ):
        with pytest.raises(ProtocolError):
            Hello.from_json_bytes(payload)


def test_a_sender_that_predates_audio_still_parses():
    raw = b'{"width":64,"height":48,"fps":30,"quality":"q","watermark":false}'
    hello = Hello.from_json_bytes(raw)
    assert hello.audio is False
    assert hello.audio_sample_rate == 48_000


# ---- decoding ----


def test_aac_round_trip_produces_audio():
    config, packets = _encode_aac(10)
    assert config, "encoder produced no AudioSpecificConfig"

    decoder = AacDecoder(SAMPLE_RATE, CHANNELS)
    decoder.configure(config)
    out = []
    for packet in packets:
        out.extend(decoder.decode(packet))
    out.extend(decoder.flush())
    decoder.close()

    assert out, "decoded nothing"
    for block in out:
        assert block.ndim == 2, "PCM must be (samples, channels) for the output stream"
        assert block.shape[1] == CHANNELS
        assert block.dtype == np.float32
    # A 440 Hz tone at 0.25 amplitude must survive as something audible, not
    # as silence — which is what a mis-scaled or mis-planed conversion gives.
    peak = max(float(np.abs(block).max()) for block in out)
    assert 0.05 < peak <= 1.0, f"decoded peak {peak} is not a recognisable tone"


def test_decoding_before_the_config_arrives_is_dropped_not_crashed():
    _, packets = _encode_aac(3)
    decoder = AacDecoder(SAMPLE_RATE, CHANNELS)
    assert decoder.decode(packets[0]) == []
    decoder.close()


def test_a_garbage_packet_does_not_kill_the_stream():
    config, packets = _encode_aac(3)
    decoder = AacDecoder(SAMPLE_RATE, CHANNELS)
    decoder.configure(config)
    assert decoder.decode(b"not aac at all") == []
    assert decoder.decode(packets[0]) is not None
    decoder.close()


def test_stereo_decodes_to_two_interleaved_channels():
    config, packets = _encode_aac(6, channels=2)
    decoder = AacDecoder(SAMPLE_RATE, 2)
    decoder.configure(config)
    out = []
    for packet in packets:
        out.extend(decoder.decode(packet))
    out.extend(decoder.flush())
    decoder.close()
    assert out
    assert all(block.shape[1] == 2 for block in out)


# ---- output device selection ----


DEVICES = [
    {"index": 0, "name": "Speakers (Realtek High Definition Audio)", "channels": 2, "default": True},
    {"index": 1, "name": "CABLE Input (VB-Audio Virtual Cable)", "channels": 2, "default": False},
    {"index": 2, "name": "Headphones (USB Audio)", "channels": 2, "default": False},
]


def test_no_request_means_the_system_default():
    assert resolve_output_device(None, DEVICES) is None
    assert resolve_output_device("", DEVICES) is None
    assert resolve_output_device("   ", DEVICES) is None


def test_a_device_can_be_named_by_index():
    assert resolve_output_device("1", DEVICES) == 1


def test_an_out_of_range_index_falls_back_to_the_default():
    assert resolve_output_device("99", DEVICES) is None


def test_an_exact_name_wins():
    assert resolve_output_device("CABLE Input (VB-Audio Virtual Cable)", DEVICES) == 1


def test_a_substring_is_enough_to_find_vb_cable():
    """The name users are looking at is long and easy to mistype; 'cable' is
    what they will actually enter."""
    assert resolve_output_device("cable", DEVICES) == 1
    assert resolve_output_device("CABLE", DEVICES) == 1


def test_an_ambiguous_substring_picks_nothing():
    """Silently choosing one of several matches is how audio ends up in the
    wrong application."""
    devices = DEVICES + [
        {"index": 3, "name": "CABLE Input 2 (VB-Audio)", "channels": 2, "default": False}
    ]
    assert resolve_output_device("cable input", devices) is None


def test_an_unmatched_name_falls_back_rather_than_failing():
    assert resolve_output_device("does not exist", DEVICES) is None
