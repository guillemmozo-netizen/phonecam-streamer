import socket

import pytest

from pc_receiver.protocol import (
    Hello,
    ProtocolError,
    recv_frame,
    recv_hello,
    send_frame,
    send_hello,
)


@pytest.fixture
def socket_pair():
    a, b = socket.socketpair()
    yield a, b
    a.close()
    b.close()


def test_hello_round_trip(socket_pair):
    a, b = socket_pair
    hello = Hello(width=1920, height=1080, fps=60, quality="1080p60", watermark=True, device_name="Galaxy S23 Ultra")
    send_hello(a, hello)
    received = recv_hello(b)
    assert received == hello


def test_frame_round_trip(socket_pair):
    a, b = socket_pair
    payload = bytes(range(256)) * 100
    send_frame(a, payload)
    received = recv_frame(b)
    assert received == payload


def test_multiple_frames_in_order(socket_pair):
    a, b = socket_pair
    frames = [b"frame-one", b"frame-two-longer", b"3"]
    for f in frames:
        send_frame(a, f)
    for expected in frames:
        assert recv_frame(b) == expected


def test_empty_frame_is_valid(socket_pair):
    a, b = socket_pair
    send_frame(a, b"")
    assert recv_frame(b) == b""


def test_closed_connection_raises_protocol_error(socket_pair):
    a, b = socket_pair
    a.close()
    with pytest.raises(ProtocolError):
        recv_frame(b)


def test_oversized_length_prefix_rejected(socket_pair):
    a, b = socket_pair
    import struct

    a.sendall(struct.pack(">I", 64 * 1024 * 1024))
    with pytest.raises(ProtocolError):
        recv_frame(b)
