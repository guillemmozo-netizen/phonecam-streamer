"""ADTS framing for AAC — the one place on the PC side that knows the format.

The phone's encoder (android-app AdtsHeader.kt) writes these headers; this
module builds the identical bytes for the PC stand-in sender and for the
tests, and parses them back so a test can assert on what was actually
produced rather than on a magic byte string.

An ADTS frame is a 7-byte header (9 with a CRC, which nothing here uses)
followed by one raw AAC access unit. The header re-states the profile, sample
rate and channel count on *every* frame, which is exactly why this pipeline
uses it: see audio_decoder's module doc for why a format that can be joined
mid-stream matters when the phone reconnects.
"""

from __future__ import annotations

from dataclasses import dataclass

ADTS_HEADER_BYTES = 7

# MPEG-4 sampling_frequency_index. The index, not the rate itself, is what
# goes in the header - there are only these 13 legal rates.
SAMPLE_RATE_INDEX = {
    96000: 0, 88200: 1, 64000: 2, 48000: 3, 44100: 4, 32000: 5,
    24000: 6, 22050: 7, 16000: 8, 12000: 9, 11025: 10, 8000: 11, 7350: 12,
}

# AAC-LC. The header field is audioObjectType - 1, so LC (object type 2)
# is written as 1.
PROFILE_AAC_LC = 1


class AdtsError(Exception):
    pass


def adts_header(payload_len: int, sample_rate: int, channels: int) -> bytes:
    """The 7-byte ADTS header for one AAC access unit of [payload_len] bytes.

    Bit layout, MSB first, as laid out in ISO/IEC 14496-3:

        syncword                        12  always 0xFFF
        mpeg_version                     1  0 = MPEG-4
        layer                            2  always 0
        protection_absent                1  1 = no CRC follows
        profile                          2  PROFILE_AAC_LC
        sampling_frequency_index         4  see SAMPLE_RATE_INDEX
        private_bit                      1  0
        channel_configuration            3  1 = mono, 2 = stereo
        originality/home/copyright x2    4  0
        frame_length                    13  header + payload, i.e. the whole frame
        buffer_fullness                 11  0x7FF = variable rate, "don't care"
        raw_data_blocks_in_frame - 1     2  0, one block per frame
    """
    try:
        rate_index = SAMPLE_RATE_INDEX[sample_rate]
    except KeyError:
        raise AdtsError(f"{sample_rate}Hz is not an MPEG-4 sample rate") from None
    if not 1 <= channels <= 7:
        raise AdtsError(f"channel_configuration must be 1..7, got {channels}")

    frame_len = payload_len + ADTS_HEADER_BYTES
    if frame_len >= 1 << 13:
        raise AdtsError(f"frame of {frame_len} bytes overflows ADTS's 13-bit length field")

    return bytes((
        0xFF,
        0xF1,  # MPEG-4, layer 0, protection absent (no CRC)
        (PROFILE_AAC_LC << 6) | (rate_index << 2) | ((channels >> 2) & 0x01),
        ((channels & 0x03) << 6) | ((frame_len >> 11) & 0x03),
        (frame_len >> 3) & 0xFF,
        ((frame_len & 0x07) << 5) | 0x1F,  # low 3 bits of length, then buffer fullness
        0xFC,  # rest of buffer fullness, and 0 extra raw data blocks
    ))


def wrap_adts(payload: bytes, sample_rate: int, channels: int) -> bytes:
    """One complete ADTS frame: header + raw AAC access unit."""
    return adts_header(len(payload), sample_rate, channels) + payload


@dataclass(frozen=True)
class AdtsInfo:
    sample_rate: int
    channels: int
    frame_length: int


def parse_adts_header(data: bytes) -> AdtsInfo:
    """Reads back what adts_header wrote. Used by the tests to prove the
    header says what it is meant to say, instead of comparing against a
    hardcoded blob that could be wrong in the same way twice."""
    if len(data) < ADTS_HEADER_BYTES:
        raise AdtsError(f"need {ADTS_HEADER_BYTES} bytes for an ADTS header, got {len(data)}")
    if data[0] != 0xFF or (data[1] & 0xF0) != 0xF0:
        raise AdtsError("missing ADTS syncword")

    rate_index = (data[2] >> 2) & 0x0F
    rates = {index: rate for rate, index in SAMPLE_RATE_INDEX.items()}
    if rate_index not in rates:
        raise AdtsError(f"reserved sampling_frequency_index {rate_index}")

    channels = ((data[2] & 0x01) << 2) | ((data[3] >> 6) & 0x03)
    frame_length = ((data[3] & 0x03) << 11) | (data[4] << 3) | ((data[5] >> 5) & 0x07)
    return AdtsInfo(sample_rate=rates[rate_index], channels=channels, frame_length=frame_length)
