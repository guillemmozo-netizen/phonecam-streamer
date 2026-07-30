package com.phonecam.streamer.streaming

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
    // "" means this session has no audio and every message after this hello
    // is a bare video payload, exactly as before audio existed. Non-empty
    // ("aac"/"pcm_s16le") switches the whole connection to MediaChunk-tagged
    // messages. Decided once, before the hello goes out, and never changed
    // mid-connection — the receiver picks its framing from this one field.
    val audioCodec: String = "",
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val audioBitrateBps: Int = 0,
) {
    val hasAudio: Boolean get() = audioCodec.isNotEmpty()

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
        put("audio_codec", audioCodec)
        put("audio_sample_rate", audioSampleRate)
        put("audio_channels", audioChannels)
        put("audio_bitrate_bps", audioBitrateBps)
    }.toString().toByteArray(Charsets.UTF_8)
}

class ProtocolException(message: String) : Exception(message)

private const val MAX_FRAME_BYTES = 32 * 1024 * 1024

/**
 * Long enough for a sluggish `adb reverse` tunnel to come up, short enough
 * that a PC which is simply asleep is retried rather than waited on.
 */
const val CONNECT_TIMEOUT_MS = 4_000

/**
 * How long a single write may be outstanding before the connection is
 * treated as dead.
 *
 * Java has no write timeout — SO_TIMEOUT governs reads only — so a blocking
 * write to a peer that has stopped reading blocks forever once the TCP send
 * buffer fills. That is precisely what a suspended PC looks like, and it
 * wedges the one thread that owns the socket: video stops, audio stops, and
 * no exception is ever thrown, so the reconnect logic never runs either. The
 * only way out is for another thread to close the socket underneath it (see
 * CameraStreamer's stall check), which makes the blocked write throw and
 * puts the session back on the normal reconnect path.
 */
const val SEND_STALL_TIMEOUT_MS = 8_000

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

    /**
     * One video access unit, tagged for a connection that also carries audio.
     *
     * Every write on this connection — video, audio and the hello — happens
     * on CameraStreamer's single networkExecutor thread. That is what makes
     * a shared socket safe without a lock: two threads writing here would
     * interleave a length prefix with someone else's payload and destroy the
     * framing for the rest of the session.
     */
    fun sendVideoChunk(ptsUs: Long, frameBytes: ByteArray) =
        StreamProtocol.sendFrame(out, MediaChunk.pack(MediaKind.VIDEO, ptsUs, frameBytes))

    /** One encoded audio packet — see [sendVideoChunk] for the threading rule. */
    fun sendAudioChunk(ptsUs: Long, packet: ByteArray) =
        StreamProtocol.sendFrame(out, MediaChunk.pack(MediaKind.AUDIO, ptsUs, packet))

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
        fun connect(host: String, port: Int, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): StreamConnection {
            // Explicit connect timeout, rather than `Socket(host, port)`'s
            // OS default. That default is tens of seconds to minutes on a
            // network that black-holes packets (PC asleep, wrong subnet
            // after a Wi-Fi switch), and ConnectionSupervisor cannot poll its
            // `stopped` flag while parked inside connect() — so stopping a
            // stream appeared to hang, and the retry backoff never got a
            // chance to run.
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
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
