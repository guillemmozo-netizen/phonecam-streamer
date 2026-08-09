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
"""

from __future__ import annotations

import json
import socket
import struct
from dataclasses import asdict, dataclass, fields

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


class MessageType:
    """First byte of every message once Hello.audio is True.

    Audio and video share one TCP connection, so something has to say which is
    which. A type byte only appears when audio was negotiated: with audio off
    the stream is byte-for-byte what it always was, which is what keeps
    demo_sender.py and the whole existing test suite meaningful rather than
    rewritten around a framing change they don't exercise.
    """

    VIDEO = 0x01
    # AAC's AudioSpecificConfig (MediaCodec's csd-0). Sent once before the
    # first audio frame, and again after any reconfiguration - a decoder that
    # missed it cannot decode a single frame, so it is not merged into the
    # first frame message.
    AUDIO_CONFIG = 0x02
    AUDIO = 0x03

    ALL = (VIDEO, AUDIO_CONFIG, AUDIO)


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
    # Audio, off by default so every sender that predates it (demo_sender.py,
    # the tests, any older build) keeps the original framing: bare video frames
    # with nothing to demultiplex. When this is True the sender switches to
    # typed messages instead - see MessageType and the module docstring.
    audio: bool = False
    # Only meaningful when audio is True. "aac" is the only codec the phone
    # produces; the rest describe what the PC has to configure its output with,
    # since AAC's own config is sent separately as an AUDIO_CONFIG message.
    audio_codec: str = "aac"
    audio_sample_rate: int = 48_000
    audio_channels: int = 1
    audio_bitrate_bps: int = 128_000

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
        # 4000..384000 spans everything from a Bluetooth voice link to a
        # high-rate USB interface; the receiver opens an output stream at
        # whatever this says, so an absurd value is a broken output device
        # rather than a merely odd setting.
        filtered["audio_sample_rate"] = _int("audio_sample_rate", 48_000, 4_000, 384_000)
        filtered["audio_channels"] = _int("audio_channels", 1, 1, 2)
        filtered["audio_bitrate_bps"] = _int("audio_bitrate_bps", 128_000, 1_000, 1_000_000)

        for name in ("quality", "device_name", "codec", "auth_token", "audio_codec"):
            if name in filtered:
                filtered[name] = str(filtered[name])[:256]
        for name in ("watermark", "sync_obs", "audio"):
            if name in filtered:
                filtered[name] = bool(filtered[name])

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


def send_typed_message(sock: socket.socket, message_type: int, payload: bytes) -> None:
    """One message in the audio-enabled framing: type byte, then payload.

    The type byte lives *inside* the length-prefixed payload rather than beside
    it, so a reader that already knows how to pull one message off the wire
    needs no change to its framing — only to what it does with the bytes.
    """
    send_message(sock, bytes((message_type,)) + payload)


def split_typed_message(payload: bytes) -> tuple[int, bytes]:
    """(type, body) from a message read in the audio-enabled framing.

    Raises rather than guessing on an unknown type: with two media streams
    multiplexed onto one socket, silently treating an unrecognised message as
    video would push noise into the user's virtual camera.
    """
    if not payload:
        raise ProtocolError("typed message is empty")
    message_type = payload[0]
    if message_type not in MessageType.ALL:
        raise ProtocolError(f"unknown message type 0x{message_type:02x}")
    return message_type, payload[1:]


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

    def buffered_message_count(self) -> int:
        """How many complete messages are already waiting (in our buffer or
        the kernel's socket receive buffer) — i.e. how far behind the sender
        this reader currently is. Tops up from the kernel buffer
        (non-blocking) first, since a message can straddle the two."""
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
        # Saved and restored rather than reset to blocking: setblocking(True)
        # is settimeout(None), so the obvious version silently cleared whatever
        # deadline the caller had set. The receiver's idle timeout was disabled
        # by the first frame that arrived, which is precisely when it stops
        # being needed and starts being needed again.
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
            count += 1
            offset += _LENGTH_STRUCT.size + length
        return count
