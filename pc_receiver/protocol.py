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
from dataclasses import asdict, dataclass

_LENGTH_STRUCT = struct.Struct(">I")
MAX_FRAME_BYTES = 32 * 1024 * 1024  # sanity guard against a corrupt length prefix


class ProtocolError(Exception):
    pass


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

    def to_json_bytes(self) -> bytes:
        return json.dumps(asdict(self)).encode("utf-8")

    @classmethod
    def from_json_bytes(cls, data: bytes) -> "Hello":
        obj = json.loads(data.decode("utf-8"))
        return cls(**obj)


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

    def buffered_message_count(self) -> int:
        """How many complete messages are already waiting (in our buffer or
        the kernel's socket receive buffer) — i.e. how far behind the sender
        this reader currently is. Tops up from the kernel buffer
        (non-blocking) first, since a message can straddle the two."""
        self._sock.setblocking(False)
        try:
            while True:
                chunk = self._sock.recv(65536)
                if not chunk:
                    break  # peer closed; let the next recv_message() raise cleanly
                self._buf.extend(chunk)
        except BlockingIOError:
            pass
        finally:
            self._sock.setblocking(True)

        count = 0
        offset = 0
        while len(self._buf) - offset >= _LENGTH_STRUCT.size:
            (length,) = _LENGTH_STRUCT.unpack(self._buf[offset : offset + _LENGTH_STRUCT.size])
            if len(self._buf) - offset < _LENGTH_STRUCT.size + length:
                break
            count += 1
            offset += _LENGTH_STRUCT.size + length
        return count
