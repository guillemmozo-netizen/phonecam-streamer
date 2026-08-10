"""The speed test port is open to the LAN with no auth (it runs before
pairing), so the size a client declares is untrusted input."""

import socket
import struct
import threading

from pc_receiver.speed_test_server import (
    COMMAND_CLEANUP,
    COMMAND_DOWNLOAD,
    COMMAND_UPLOAD,
    MAX_UPLOAD_BYTES,
    handle_client,
)


def _run_handler(server_sock):
    thread = threading.Thread(
        target=handle_client, args=(server_sock, ("test", 0)), daemon=True
    )
    thread.start()
    return thread


def test_upload_of_declared_gigabytes_is_rejected():
    """A stranger on the LAN must not be able to turn the declared int64 size
    into a multi-gigabyte allocation held in this process."""
    server_sock, client_sock = socket.socketpair()
    thread = _run_handler(server_sock)

    client_sock.sendall(struct.pack(">i", COMMAND_UPLOAD))
    client_sock.sendall(struct.pack(">q", 8 * 1024 * 1024 * 1024))  # 8 GB

    # The handler must drop the connection instead of reading 8 GB.
    thread.join(timeout=5)
    assert not thread.is_alive()
    client_sock.close()


def test_negative_declared_size_is_rejected():
    server_sock, client_sock = socket.socketpair()
    thread = _run_handler(server_sock)

    client_sock.sendall(struct.pack(">i", COMMAND_UPLOAD))
    client_sock.sendall(struct.pack(">q", -1))

    thread.join(timeout=5)
    assert not thread.is_alive()
    client_sock.close()


def test_normal_roundtrip_still_works():
    """The app's real flow — upload, download back, cleanup — is untouched."""
    server_sock, client_sock = socket.socketpair()
    thread = _run_handler(server_sock)

    payload = b"\xab" * 4096
    assert len(payload) <= MAX_UPLOAD_BYTES
    client_sock.sendall(struct.pack(">i", COMMAND_UPLOAD))
    client_sock.sendall(struct.pack(">q", len(payload)))
    client_sock.sendall(payload)
    ack = client_sock.recv(1)
    assert ack == b"\x01"

    client_sock.sendall(struct.pack(">i", COMMAND_DOWNLOAD))
    size = struct.unpack(">q", _recv_exact(client_sock, 8))[0]
    assert size == len(payload)
    assert _recv_exact(client_sock, size) == payload

    client_sock.sendall(struct.pack(">i", COMMAND_CLEANUP))
    thread.join(timeout=5)
    assert not thread.is_alive()
    client_sock.close()


def _recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        assert chunk, "connection closed early"
        buf.extend(chunk)
    return bytes(buf)
