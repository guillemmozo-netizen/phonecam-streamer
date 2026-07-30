"""The audio half of the wire protocol: chunk tagging, the hello fields that
switch it on, and — the part with the most ways to go wrong — that a sender
which knows nothing about audio still speaks a format this receiver reads
byte for byte."""

import socket
import struct

import pytest

from pc_receiver.protocol import (
    CHUNK_HEADER_BYTES,
    FrameReader,
    Hello,
    MediaKind,
    ProtocolError,
    pack_chunk,
    recv_hello,
    send_frame,
    send_hello,
    send_message,
    unpack_chunk,
)


@pytest.fixture
def socket_pair():
    a, b = socket.socketpair()
    yield a, b
    a.close()
    b.close()


# ─────────────────────────── chunk tagging ───────────────────────────

def test_chunk_round_trip_preserves_kind_timestamp_and_payload():
    packed = pack_chunk(MediaKind.AUDIO, 1_234_567, b"payload")
    kind, pts_us, data = unpack_chunk(packed)

    assert kind is MediaKind.AUDIO
    assert pts_us == 1_234_567
    assert data == b"payload"


def test_chunk_header_costs_nine_bytes():
    """Fixed at 9 (kind + int64) — 60fps of video plus ~47 audio packets a
    second is under a kilobyte an hour of overhead, which is what makes
    tagging every message the cheap option versus a second socket."""
    assert CHUNK_HEADER_BYTES == 9
    assert len(pack_chunk(MediaKind.VIDEO, 0, b"")) == 9


def test_negative_timestamps_survive():
    """Android's System.nanoTime() has an arbitrary origin and starts
    negative on some devices. An unsigned field would turn that into a
    584,000-year offset and hold every frame forever."""
    kind, pts_us, data = unpack_chunk(pack_chunk(MediaKind.VIDEO, -5_000_000, b"x"))

    assert pts_us == -5_000_000
    assert data == b"x"


def test_empty_payload_is_a_valid_chunk():
    kind, pts_us, data = unpack_chunk(pack_chunk(MediaKind.VIDEO, 7, b""))
    assert (kind, pts_us, data) == (MediaKind.VIDEO, 7, b"")


def test_truncated_chunk_is_rejected_not_crashed():
    with pytest.raises(ProtocolError):
        unpack_chunk(b"")
    with pytest.raises(ProtocolError):
        unpack_chunk(b"\x01\x00\x00\x00")


def test_unknown_media_kind_is_rejected():
    with pytest.raises(ProtocolError):
        unpack_chunk(struct.pack(">Bq", 99, 0) + b"data")


# ─────────────────────────── hello negotiation ───────────────────────────

def _hello(**kwargs) -> Hello:
    base = dict(width=1920, height=1080, fps=60, quality="1080p60", watermark=True)
    base.update(kwargs)
    return Hello(**base)


def test_hello_without_audio_reports_no_audio():
    hello = _hello()
    assert hello.has_audio is False
    assert hello.audio_codec == ""


def test_hello_with_audio_round_trips(socket_pair):
    a, b = socket_pair
    hello = _hello(
        audio_codec="aac", audio_sample_rate=48000, audio_channels=2, audio_bitrate_bps=192_000
    )
    send_hello(a, hello)

    received = recv_hello(b)
    assert received == hello
    assert received.has_audio is True


def test_a_sender_that_predates_audio_still_parses():
    """The compatibility guarantee: an older phone's hello has none of these
    fields, and must produce a valid audio-less Hello rather than an error."""
    legacy = b'{"width":1920,"height":1080,"fps":60,"quality":"1080p60","watermark":true}'

    hello = Hello.from_json_bytes(legacy)

    assert hello.has_audio is False
    assert (hello.audio_sample_rate, hello.audio_channels, hello.audio_bitrate_bps) == (0, 0, 0)


def test_audio_codec_without_a_format_is_rejected():
    """An internally inconsistent hello fails at the edge, where the error can
    still name the problem — rather than deep inside a decoder, or as a device
    buffer sized from a 0 sample rate."""
    with pytest.raises(ProtocolError):
        Hello.from_json_bytes(_hello(audio_codec="aac").to_json_bytes())

    with pytest.raises(ProtocolError):
        Hello.from_json_bytes(
            _hello(audio_codec="aac", audio_sample_rate=48000, audio_channels=0).to_json_bytes()
        )


def test_absurd_audio_formats_are_rejected():
    for field, value in [
        ("audio_sample_rate", 10_000_000),
        ("audio_channels", 99),
        ("audio_bitrate_bps", 999_999_999),
    ]:
        payload = _hello(audio_codec="aac", audio_sample_rate=48000, audio_channels=2)
        raw = payload.to_json_bytes().replace(
            f'"{field}": {getattr(payload, field)}'.encode(),
            f'"{field}": {value}'.encode(),
        )
        with pytest.raises(ProtocolError):
            Hello.from_json_bytes(raw)


# ─────────────────── kind-aware backlog counting ───────────────────

def test_backlog_counts_only_the_stream_asked_about(socket_pair):
    """Without this the receive loop reads a healthy audio session as
    permanently backlogged: audio arrives ~47 times a second, so a plain
    message count is almost never below 2."""
    a, b = socket_pair
    for _ in range(3):
        send_message(a, pack_chunk(MediaKind.AUDIO, 0, b"audio"))
    send_message(a, pack_chunk(MediaKind.VIDEO, 0, b"video"))
    for _ in range(2):
        send_message(a, pack_chunk(MediaKind.AUDIO, 0, b"audio"))

    reader = FrameReader(b)
    assert reader.buffered_message_count() == 6
    assert reader.buffered_message_count(MediaKind.VIDEO) == 1
    assert reader.buffered_message_count(MediaKind.AUDIO) == 5


def test_backlog_without_a_filter_is_unchanged_for_untagged_senders(socket_pair):
    a, b = socket_pair
    for payload in (b"one", b"two"):
        send_frame(a, payload)

    assert FrameReader(b).buffered_message_count() == 2


def test_partial_trailing_message_is_not_counted(socket_pair):
    a, b = socket_pair
    send_message(a, pack_chunk(MediaKind.VIDEO, 0, b"complete"))
    a.sendall(struct.pack(">I", 1000) + b"truncated")

    reader = FrameReader(b)
    assert reader.buffered_message_count(MediaKind.VIDEO) == 1
