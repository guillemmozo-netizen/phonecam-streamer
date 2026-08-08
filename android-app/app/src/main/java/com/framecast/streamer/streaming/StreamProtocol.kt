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
    // Audio, off by default. When false the stream is bare video frames, byte
    // for byte what it always was — which is what keeps demo_sender.py and the
    // whole PC-side test suite valid. When true every message after this Hello
    // carries a MessageType byte, because audio and video then share the socket.
    val audio: Boolean = false,
    val audioCodec: String = "aac",
    val audioSampleRate: Int = 48_000,
    val audioChannels: Int = 1,
    val audioBitrateBps: Int = 128_000,
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
        put("audio", audio)
        put("audio_codec", audioCodec)
        put("audio_sample_rate", audioSampleRate)
        put("audio_channels", audioChannels)
        put("audio_bitrate_bps", audioBitrateBps)
    }.toString().toByteArray(Charsets.UTF_8)
}

class ProtocolException(message: String) : Exception(message)

/**
 * First byte of every message once [Hello.audio] is true — the PC's
 * pc_receiver/protocol.py MessageType, and the two must not drift.
 *
 * The byte lives inside the length-prefixed payload rather than beside it, so
 * the framing itself is unchanged and only the interpretation of the bytes
 * differs between an audio and a video session.
 */
object MessageType {
    const val VIDEO: Byte = 0x01
    /** AAC's AudioSpecificConfig. Must reach the PC before the first audio frame. */
    const val AUDIO_CONFIG: Byte = 0x02
    const val AUDIO: Byte = 0x03
}

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

    /**
     * One message in the audio-enabled framing.
     *
     * Built into a single array rather than written as two calls: [sendMessage]
     * writes a length then a payload, and interleaving a separate one-byte
     * write between two of those from another thread would corrupt the stream.
     * CameraStreamer serialises sends anyway, but a framing primitive should
     * not depend on its caller's locking to stay well-formed.
     */
    fun sendTyped(out: DataOutputStream, messageType: Byte, payload: ByteArray) {
        val message = ByteArray(payload.size + 1)
        message[0] = messageType
        payload.copyInto(message, destinationOffset = 1)
        sendMessage(out, message)
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

    /**
     * The same video frame, tagged, for sessions that negotiated audio.
     *
     * Kept as a separate call rather than a flag on sendFrame so that a session
     * without audio cannot accidentally acquire a type byte the PC is not
     * expecting to strip.
     */
    fun sendVideoFrameTyped(frameBytes: ByteArray) =
        StreamProtocol.sendTyped(out, MessageType.VIDEO, frameBytes)

    fun sendAudioConfig(config: ByteArray) =
        StreamProtocol.sendTyped(out, MessageType.AUDIO_CONFIG, config)

    fun sendAudioFrame(frameBytes: ByteArray) =
        StreamProtocol.sendTyped(out, MessageType.AUDIO, frameBytes)

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
