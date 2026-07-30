"""Wire protocol shared by the phone-side streamer and the PC receiver.

Transport: plain TCP. In the real (USB) deployment this socket is not opened
directly to the phone's IP — it is opened to `127.0.0.1:<port>` on the PC
after running:

    adb reverse tcp:<port> tcp:<port>

which tunnels that PC-local port to the same port on the phone over the USB
cable (no Wi-Fi, no special drivers, no root). Over Wi-Fi the same protocol
works by connecting directly to the phone's LAN IP. See docs/PROTOCOL.md.

Framing is deliberately simple for an alpha: every message on the wire is a
4-byte big-endian length prefix followed by that many bytes of payload. The
first message after connecting is always a JSON "hello" describing the
stream; every message after that is one encoded video frame in whatever
codec Hello.codec names ("jpeg": one full JPEG image per message, unchanged
from before; "h264": one Annex-B access unit per message, the SPS/PPS config
data sent as its own message before the first real frame).

When the sender has audio (Hello.audio_codec is non-empty) those post-hello
payloads gain a 9-byte header so the two streams can share the one socket —
see pack_chunk. Senders without audio keep the original bare framing byte for
byte, which is what keeps every existing sender (and the JPEG demo path)
working unchanged.
"""

from __future__ import annotations

import json
import socket
import struct
from dataclasses import asdict, dataclass, fields
from enum import IntEnum
from typing import Optional, Tuple

_LENGTH_STRUCT = struct.Struct(">I")
MAX_FRAME_BYTES = 32 * 1024 * 1024  # sanity guard against a corrupt length prefix

# Ceiling for the opportunistic drain in buffered_message_count(). Large enough
# to hold several encoded frames at any resolution the app offers, small enough
# that a fast sender cannot turn this buffer into unbounded process memory.
# recv_message()'s own _fill() is deliberately not capped - a single frame may
# legitimately exceed this and still has to be read whole.
_MAX_OPPORTUNISTIC_BUFFER = 4 * 1024 * 1024


class ProtocolError(Exception):
    pass


class MediaKind(IntEnum):
    """Which stream a post-hello message belongs to. See pack_chunk."""

    VIDEO = 1
    AUDIO = 2


_CHUNK_HEADER = struct.Struct(">Bq")  # kind, presentation timestamp in microseconds

# Sender-clock microseconds. Signed because it is a monotonic *reading*
# (Android's System.nanoTime()), not a duration: its zero point is arbitrary
# and on some devices it starts negative. The receiver only ever takes
# differences between two of them, so the origin never matters — but the sign
# does, and an unsigned field would silently wrap a negative start into a
# nonsensical 584,000-year offset.
CHUNK_HEADER_BYTES = _CHUNK_HEADER.size


def pack_chunk(kind: MediaKind, pts_us: int, data: bytes) -> bytes:
    """Tags one encoded chunk with its stream and presentation timestamp.

    Audio and video share a single TCP connection rather than opening a
    second one, because the transport here is often an `adb reverse` tunnel:
    one forwarded port is one thing for the user (and the installer) to get
    right, and both streams then take exactly the same path, so neither can
    be delayed relative to the other by an unrelated route.

    The cost of sharing is head-of-line blocking — a video access unit ahead
    of an audio packet delays it by however long that video frame takes to
    write. At the frame sizes and link speeds involved (a 4K60 frame is
    ~100KB on a link measured at 80-300+ Mbps) that is under a millisecond,
    far below the audio packet duration itself, so it is not a practical
    constraint. Two sockets would trade it for clock-skew between streams,
    which is the harder problem to fix.

    [pts_us] comes from one clock shared by both streams (the phone's
    System.nanoTime), which is what makes PC-side A/V alignment possible at
    all — see av_sync.py.
    """
    return _CHUNK_HEADER.pack(int(kind), pts_us) + data


def unpack_chunk(payload: bytes) -> Tuple[MediaKind, int, bytes]:
    """Inverse of pack_chunk, on untrusted bytes.

    Same threat model as Hello.from_json_bytes: anything can open this socket,
    so a truncated or mislabelled chunk has to come back as a rejected message,
    never an IndexError or a struct.error escaping into the receive loop.
    """
    if len(payload) < CHUNK_HEADER_BYTES:
        raise ProtocolError(f"chunk of {len(payload)} bytes is shorter than its header")
    raw_kind, pts_us = _CHUNK_HEADER.unpack(payload[:CHUNK_HEADER_BYTES])
    try:
        kind = MediaKind(raw_kind)
    except ValueError:
        raise ProtocolError(f"unknown media kind {raw_kind}")
    return kind, pts_us, payload[CHUNK_HEADER_BYTES:]


@dataclass(frozen=True)
class Hello:
    """Describes the stream the sender is about to push."""

    width: int
    height: int
    fps: int
    quality: str
    watermark: bool
    device_name: str = "unknown"
    # "jpeg" (default, back-compat with demo_sender.py and older senders that
    # never set this field) or "h264" — see the module docstring for framing.
    codec: str = "jpeg"
    # 0 (default, older senders that never set this field) means "unknown" —
    # obs_sync skips the bitrate half of the sync in that case rather than
    # pushing a bogus 0 kbps into OBS.
    video_bitrate_bps: int = 0
    # Settings > "Sync OBS settings" on the phone — see obs_sync.py. Defaults
    # to True so older senders that predate this field still get the sync
    # (matches the toggle's own default), rather than silently opting every
    # existing install out.
    sync_obs: bool = True
    # Shared secret from the PC, required for non-loopback (i.e. Wi-Fi)
    # senders. Empty for the USB path, which arrives on loopback and is
    # exempt - see receiver.handle_connection.
    auth_token: str = ""

    # "" (default) means this sender has no audio, and post-hello messages
    # are bare video payloads exactly as they always were. Non-empty ("aac"
    # or "pcm_s16le") means every post-hello message carries a pack_chunk
    # header instead. Defaulting to "" is what keeps every existing sender —
    # including demo_sender.py's JPEG path and any phone running an older
    # build - byte-for-byte compatible with this receiver.
    audio_codec: str = ""
    audio_sample_rate: int = 0
    audio_channels: int = 0
    audio_bitrate_bps: int = 0

    @property
    def has_audio(self) -> bool:
        """Whether post-hello messages are pack_chunk-tagged.

        The single switch for the whole framing question, deliberately derived
        from one field rather than sent as its own flag: two fields that can
        disagree is a wire format with an undefined state in it.
        """
        return bool(self.audio_codec)

    def to_json_bytes(self) -> bytes:
        return json.dumps(asdict(self)).encode("utf-8")

    @classmethod
    def from_json_bytes(cls, data: bytes) -> "Hello":
        """Parses a Hello from untrusted bytes.

        Anything on the network can open this socket, so the payload is
        treated as hostile: unknown keys are dropped rather than passed to the
        constructor (cls(**obj) raised TypeError on any unexpected field, which
        let a single stray key kill the connection - and would break every old
        receiver the moment a new field is added), types are coerced, and
        dimensions are bounded. A malformed Hello must fail as a rejected
        connection, never as a crash or an absurd canvas pushed into OBS.
        """
        obj = json.loads(data.decode("utf-8"))
        if not isinstance(obj, dict):
            raise ProtocolError("hello must be a JSON object")

        known = {f.name for f in fields(cls)}
        filtered = {k: v for k, v in obj.items() if k in known}

        def _int(name: str, default: int, low: int, high: int) -> int:
            try:
                value = int(filtered.get(name, default))
            except (TypeError, ValueError):
                raise ProtocolError(f"hello.{name} is not an integer")
            if not low <= value <= high:
                raise ProtocolError(f"hello.{name}={value} out of range {low}..{high}")
            return value

        # 16..8192 covers every mode the app offers (360p to 8K) with room to
        # spare; 240fps is the highest the reference device advertises at all.
        filtered["width"] = _int("width", 0, 16, 8192)
        filtered["height"] = _int("height", 0, 16, 8192)
        filtered["fps"] = _int("fps", 30, 1, 240)
        filtered["video_bitrate_bps"] = _int("video_bitrate_bps", 0, 0, 1_000_000_000)

        # 0 is in range for all three on purpose: it is what an audio-less
        # sender leaves them at, and rejecting it would reject every existing
        # sender. The upper bounds are the widest the decoders will ever be
        # asked for (192kHz/8ch covers any AudioRecord configuration Android
        # exposes), so a corrupt hello can't get a multi-gigabyte buffer
        # allocated downstream.
        filtered["audio_sample_rate"] = _int("audio_sample_rate", 0, 0, 192_000)
        filtered["audio_channels"] = _int("audio_channels", 0, 0, 8)
        filtered["audio_bitrate_bps"] = _int("audio_bitrate_bps", 0, 0, 10_000_000)

        for name in ("quality", "device_name", "codec", "auth_token", "audio_codec"):
            if name in filtered:
                filtered[name] = str(filtered[name])[:256]
        for name in ("watermark", "sync_obs"):
            if name in filtered:
                filtered[name] = bool(filtered[name])

        # Announcing a codec but no format is the one internally inconsistent
        # hello worth rejecting outright: it would be accepted here and then
        # fail deep inside the audio decoder or, worse, silently size a device
        # buffer from a 0 sample rate. Fail it at the edge, where the error
        # still names the actual problem.
        if filtered.get("audio_codec"):
            if filtered["audio_sample_rate"] < 8000:
                raise ProtocolError(
                    f"hello.audio_codec={filtered['audio_codec']!r} needs a real "
                    f"audio_sample_rate, got {filtered['audio_sample_rate']}"
                )
            if not 1 <= filtered["audio_channels"] <= 8:
                raise ProtocolError(
                    f"hello.audio_channels={filtered['audio_channels']} out of range 1..8"
                )

        return cls(**filtered)


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    chunks = []
    remaining = n
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ProtocolError("connection closed while reading frame")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def send_message(sock: socket.socket, payload: bytes) -> None:
    sock.sendall(_LENGTH_STRUCT.pack(len(payload)) + payload)


def recv_message(sock: socket.socket) -> bytes:
    header = _recv_exact(sock, _LENGTH_STRUCT.size)
    (length,) = _LENGTH_STRUCT.unpack(header)
    if length > MAX_FRAME_BYTES:
        raise ProtocolError(f"frame length {length} exceeds max {MAX_FRAME_BYTES}")
    return _recv_exact(sock, length)


def send_hello(sock: socket.socket, hello: Hello) -> None:
    send_message(sock, hello.to_json_bytes())


def recv_hello(sock: socket.socket) -> Hello:
    return Hello.from_json_bytes(recv_message(sock))


def send_frame(sock: socket.socket, jpeg_bytes: bytes) -> None:
    send_message(sock, jpeg_bytes)


def recv_frame(sock: socket.socket) -> bytes:
    return recv_message(sock)


class FrameReader:
    """Buffered socket reader that can tell whether another complete frame
    is *already* waiting, so the receive loop can detect it has fallen
    behind real time and catch up instead of dutifully displaying every
    stale frame in order.

    Plain `recv_message()` can't answer that question: once bytes are taken
    off a socket they can't be put back, so checking "is there more?" has to
    go through the same buffer everything else reads from — hence this
    class, instead of a bare `socket.socket`, for the real receive loop in
    receiver.py. (The existing recv_frame/recv_hello functions above are
    untouched and still used as-is by the test suite and anywhere a single
    blocking read is all that's needed.)
    """

    def __init__(self, sock: socket.socket) -> None:
        self._sock = sock
        self._buf = bytearray()

    def _fill(self, min_bytes: int) -> None:
        while len(self._buf) < min_bytes:
            chunk = self._sock.recv(max(65536, min_bytes - len(self._buf)))
            if not chunk:
                raise ProtocolError("connection closed while reading frame")
            self._buf.extend(chunk)

    def recv_message(self) -> bytes:
        self._fill(_LENGTH_STRUCT.size)
        (length,) = _LENGTH_STRUCT.unpack(self._buf[: _LENGTH_STRUCT.size])
        if length > MAX_FRAME_BYTES:
            raise ProtocolError(f"frame length {length} exceeds max {MAX_FRAME_BYTES}")
        total = _LENGTH_STRUCT.size + length
        self._fill(total)
        payload = bytes(self._buf[_LENGTH_STRUCT.size : total])
        del self._buf[:total]
        return payload

    def recv_hello(self) -> Hello:
        return Hello.from_json_bytes(self.recv_message())

    def buffered_message_count(self, only_kind: "Optional[MediaKind]" = None) -> int:
        """How many complete messages are already waiting (in our buffer or
        the kernel's socket receive buffer) — i.e. how far behind the sender
        this reader currently is. Tops up from the kernel buffer
        (non-blocking) first, since a message can straddle the two.

        [only_kind] counts just the messages of one stream, by reading each
        buffered payload's pack_chunk kind byte without consuming it. The
        caller uses this to answer "how many *video* frames are backed up",
        which stopped being the same question as "how many messages are
        backed up" the moment audio started sharing the socket: audio packets
        arrive around 47 times a second, so on an audio session a plain
        message count sits at >= 2 almost permanently and would report a
        perfectly on-time stream as hopelessly backlogged.
        """
        # Bounded on purpose. This used to drain the kernel buffer in full,
        # every frame, with no cap - so whenever the sender outran the consumer
        # the surplus moved into this bytearray instead of staying in the
        # kernel, and it grew without limit: measured at 457 MiB in a single
        # object over one 6000-frame session, 530 MB of process RSS.
        #
        # It also defeated the very backpressure the caller relies on: emptying
        # the kernel buffer reopens the TCP window, so the phone was never told
        # to slow down.
        #
        # The caller only needs to know whether it is >= 2 messages behind, so
        # stop as soon as the cap is reached; anything beyond that stays in the
        # kernel where it belongs.
        # Saved and restored rather than just flipped back to blocking:
        # setblocking(True) is defined as settimeout(None), so the obvious
        # `finally: setblocking(True)` silently *removed* whatever timeout the
        # caller had configured — permanently, from the first frame onwards.
        # The receiver's idle timeout (its only defence against a half-open
        # peer wedging the whole service) would have been switched off by the
        # very next line of the receive loop.
        previous_timeout = self._sock.gettimeout()
        self._sock.setblocking(False)
        try:
            while len(self._buf) < _MAX_OPPORTUNISTIC_BUFFER:
                chunk = self._sock.recv(65536)
                if not chunk:
                    break  # peer closed; let the next recv_message() raise cleanly
                self._buf.extend(chunk)
        except BlockingIOError:
            pass
        finally:
            self._sock.settimeout(previous_timeout)

        count = 0
        offset = 0
        while len(self._buf) - offset >= _LENGTH_STRUCT.size:
            (length,) = _LENGTH_STRUCT.unpack(self._buf[offset : offset + _LENGTH_STRUCT.size])
            if len(self._buf) - offset < _LENGTH_STRUCT.size + length:
                break
            body = offset + _LENGTH_STRUCT.size
            if only_kind is None or (length >= 1 and self._buf[body] == int(only_kind)):
                count += 1
            offset = body + length
        return count
