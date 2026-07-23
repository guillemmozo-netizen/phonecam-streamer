"""
Discovery Server — responds to UDP broadcast from the phone app.

The phone broadcasts "PHONECAM_DISCOVER" on port 8789.
This server replies "PHONECAM_HERE" so the phone learns the PC's IP.

Run this alongside speed_test_server.py and the main receiver.
"""

import socket
import threading

HOST = "0.0.0.0"
PORT = 8789
DISCOVER_MSG = "PHONECAM_DISCOVER"
REPLY_MSG = "PHONECAM_HERE"


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((HOST, PORT))
    print(f"[discovery] listening for broadcasts on port {PORT}")

    while True:
        data, addr = sock.recvfrom(256)
        msg = data.decode("utf-8", errors="ignore").strip()
        if msg == DISCOVER_MSG:
            print(f"[discovery] phone at {addr[0]} is looking for us")
            reply = REPLY_MSG.encode("utf-8")
            sock.sendto(reply, addr)
            print(f"[discovery] replied to {addr[0]}")


if __name__ == "__main__":
    main()
