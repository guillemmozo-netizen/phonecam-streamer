package com.framecast.streamer.streaming

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.Socket

/**
 * Wire protocol counterpart to pc_receiver/protocol.py. Every message is a
 * 4-byte big-endian length prefix followed by that many bytes: first a JSON
 * "hello", then a stream of encoded frames in whatever [Hello.codec] names.
 * See docs/PROTOCOL.md.
 */
data class Hello(
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: String,
    val watermark: Boolean,
    val deviceName: String = "unknown",
    // "jpeg" or "h264" — see StreamConnection/H264Encoder. Real devices now
    // always send "h264" (CameraStreamer); "jpeg" is kept for the PC-only
    // demo path (pc_receiver/demo_sender.py) and tests.
    val codec: String = "jpeg",
    val videoBitrateBps: Int = 0,
    // Settings > "Sync OBS settings" — see pc_receiver/obs_sync.py.
    val syncObs: Boolean = true,
    // Shared secret fetched from the PC over USB (PcControl.fetchToken). The
    // receiver requires it for non-loopback senders, so Wi-Fi sessions must
    // carry it; the USB path arrives on loopback and is exempt.
    val authToken: String = "",
) {
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("quality", quality)
        put("watermark", watermark)
        put("device_name", deviceName)
        put("codec", codec)
        put("video_bitrate_bps", videoBitrateBps)
        put("sync_obs", syncObs)
        put("auth_token", authToken)
    }.toString().toByteArray(Charsets.UTF_8)
}

class ProtocolException(message: String) : Exception(message)

private const val MAX_FRAME_BYTES = 32 * 1024 * 1024

object StreamProtocol {

    fun sendMessage(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun sendHello(out: DataOutputStream, hello: Hello) {
        sendMessage(out, hello.toJsonBytes())
    }

    fun sendFrame(out: DataOutputStream, frameBytes: ByteArray) {
        sendMessage(out, frameBytes)
    }
}

/** Opens a TCP connection to [host]:[port] and returns ready-to-use streams. */
class StreamConnection private constructor(
    private val socket: Socket,
    val out: DataOutputStream,
) : AutoCloseable {

    fun sendHello(hello: Hello) = StreamProtocol.sendHello(out, hello)

    /** One access unit (H.264) or one whole image (the legacy JPEG demo path). */
    fun sendFrame(frameBytes: ByteArray) = StreamProtocol.sendFrame(out, frameBytes)

    override fun close() {
        socket.close()
    }

    companion object {
         /**
          * The PC receiver (pc_receiver/receiver.py) is the TCP server; the
          * phone app is always the client. For USB mode, connect to
          * [host] = "127.0.0.1" — the PC side runs
          * `adb reverse tcp:<port> tcp:<port>` so the phone's own loopback
          * connection is tunneled to the receiver listening on the PC, no
          * Wi-Fi and no special drivers required. For Wi-Fi mode, [host] is
          * simply the PC's LAN IP instead, same protocol either way.
          */
        fun connect(host: String, port: Int): StreamConnection {
            val socket = Socket(host, port)
            // Every frame is sent as two writes (length prefix, then payload)
            // before a flush — with Nagle's algorithm on (the JVM default),
            // that tiny first write can sit waiting on an ACK for previously
            // unacked data before the payload goes out, adding latency to
            // every single frame instead of just the rare one that needs it.
            socket.tcpNoDelay = true
            val out = DataOutputStream(socket.getOutputStream())
            return StreamConnection(socket, out)
        }
    }
}
