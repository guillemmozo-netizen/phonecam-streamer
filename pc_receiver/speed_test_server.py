"""
Speed Test Server — runs on the PC alongside the main receiver.

Protocol (port 8788):
  1. Phone sends: int32 command
     - UPLOAD (1): phone→PC transfer
       Phone sends int64 file_size, then file_size bytes of data.
       Server stores temporarily, replies with 1 byte ack (0x01).
     - DOWNLOAD (2): PC→phone transfer
       Server sends int64 file_size, then the stored bytes back.
     - CLEANUP (3): server deletes stored data, no response.
"""

import socket
import struct
import threading

HOST = "0.0.0.0"
PORT = 8788

COMMAND_UPLOAD = 1
COMMAND_DOWNLOAD = 2
COMMAND_CLEANUP = 3


def recv_exact(conn: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(min(n - len(buf), 65536))
        if not chunk:
            raise ConnectionError("connection closed")
        buf.extend(chunk)
    return bytes(buf)


def handle_client(conn: socket.socket, addr):
    print(f"[speed-test] client connected: {addr}")
    stored_data: bytes = b""

    try:
        while True:
            try:
                cmd_bytes = recv_exact(conn, 4)
            except ConnectionError:
                break

            command = struct.unpack(">i", cmd_bytes)[0]

            if command == COMMAND_UPLOAD:
                size_bytes = recv_exact(conn, 8)
                file_size = struct.unpack(">q", size_bytes)[0]
                print(f"[speed-test] uploading {file_size / 1024 / 1024:.1f} MB...")
                stored_data = recv_exact(conn, file_size)
                conn.sendall(b"\x01")
                print(f"[speed-test] upload complete")

            elif command == COMMAND_DOWNLOAD:
                size = len(stored_data)
                print(f"[speed-test] downloading {size / 1024 / 1024:.1f} MB...")
                conn.sendall(struct.pack(">q", size))
                conn.sendall(stored_data)
                print(f"[speed-test] download complete")

            elif command == COMMAND_CLEANUP:
                stored_data = b""
                print(f"[speed-test] cleanup done")
                break

    except (ConnectionError, OSError) as e:
        print(f"[speed-test] client error: {e}")
    finally:
        conn.close()
        print(f"[speed-test] client disconnected: {addr}")


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(2)
    print(f"[speed-test] listening on {HOST}:{PORT}")

    while True:
        conn, addr = server.accept()
        thread = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
        thread.start()


if __name__ == "__main__":
    main()
