package com.phonecam.streamer.streaming

import android.util.Log
import com.phonecam.streamer.audio.AudioCapture
import com.phonecam.streamer.audio.AudioEncoder
import com.phonecam.streamer.audio.AudioTimeline
import com.phonecam.streamer.audio.HighPassFilter
import com.phonecam.streamer.audio.createAudioEncoder

private const val TAG = "AudioStreamer"

/**
 * The microphone half of a streaming session: capture -> encode -> send,
 * on one dedicated thread.
 *
 * ## Why one thread, and why it blocks
 *
 * AudioRecord.read() blocks until samples exist, which is exactly the pacing
 * this loop wants — the microphone's own rate is the clock, and anything
 * else (a timer, a poll) would either burn CPU or overrun the capture
 * buffer. Encoding is fast enough (a 21ms AAC frame costs well under a
 * millisecond) that doing it inline on the same thread keeps the whole path
 * to a single, obvious sequence.
 *
 * Sending, though, is *not* done on this thread: [send] hands the packet to
 * CameraStreamer's network executor, the one thread that owns the socket.
 * Two threads writing to the same connection would interleave a length
 * prefix with someone else's payload and destroy the framing for the rest of
 * the session.
 *
 * ## The format is decided before the first byte goes out
 *
 * [start] opens the microphone and the encoder and returns the [Format] that
 * actually resulted — which is not always the one requested: stereo can come
 * back mono, AAC can come back PCM. Hello carries that real format, and the
 * PC sizes its decoder and output device from it, so it has to be known
 * before the hello is sent and must not change afterwards. Audio failing
 * later in a session therefore stops the audio but does not renegotiate
 * anything: the connection keeps its tagged framing and simply carries no
 * more audio packets.
 */
class AudioStreamer(
    private val captureConfig: AudioCapture.Config,
    private val requestedCodec: String,
    private val bitrateBps: Int,
    /** Settings' "wind filter" — a low-cut applied before encoding. */
    private val windFilter: Boolean = false,
    /** Delivers one encoded packet with its timestamp to whoever owns the socket. */
    private val send: (ptsUs: Long, packet: ByteArray) -> Unit,
) {

    /** What the microphone and encoder really produced, for Hello. */
    data class Format(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitrateBps: Int,
    )

    private val capture = AudioCapture(captureConfig)
    private val timeline = AudioTimeline(captureConfig.sampleRate)

    private var encoder: AudioEncoder? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Non-null once [start] has succeeded; the format Hello should announce. */
    @Volatile var format: Format? = null
        private set

    var packetsSent: Long = 0L
        private set

    /**
     * Opens the microphone and encoder and starts capturing. Returns the
     * negotiated [Format], or null if this session will have no audio —
     * permission not granted, no usable microphone configuration, or the
     * user turned audio off.
     *
     * Null is an ordinary outcome, not an error: the session continues as
     * video-only, which is what the product is for.
     */
    fun start(): Format? {
        if (running) return format
        if (!capture.start()) return null

        val activeEncoder = try {
            createAudioEncoder(
                codecName = requestedCodec,
                sampleRate = capture.sampleRate,
                channels = capture.channels,
                bitrateBps = bitrateBps,
            )
        } catch (e: Exception) {
            Log.w(TAG, "no audio encoder could be created — streaming without audio", e)
            capture.stop()
            return null
        }

        encoder = activeEncoder
        val negotiated = Format(
            codec = activeEncoder.codecName,
            sampleRate = capture.sampleRate,
            channels = capture.channels,
            bitrateBps = bitrateBps,
        )
        format = negotiated
        running = true
        thread = Thread({ captureLoop(activeEncoder) }, "AudioStreamer").apply {
            // Above normal: an overrun in AudioRecord's buffer is lost audio
            // that nothing downstream can reconstruct, unlike a late video
            // frame, which the PC just skips.
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(TAG, "audio session: $negotiated")
        return negotiated
    }

    private fun captureLoop(activeEncoder: AudioEncoder) {
        // Interleaved shorts, so a stereo buffer holds half as many sample
        // frames as it does entries.
        val buffer = ShortArray(capture.frameSamples * capture.channels)
        // Built here rather than in start(), because it has to be sized from
        // the channel count the microphone really opened, which stereo->mono
        // fallback can change.
        val lowCut = if (windFilter) {
            HighPassFilter(capture.sampleRate, channels = capture.channels)
        } else {
            null
        }

        while (running) {
            val read = capture.read(buffer)
            if (read < 0) break
            if (read == 0) continue

            // Before encoding, not after: removing rumble the listener would
            // never hear anyway stops the encoder spending bitrate coding it.
            lowCut?.processInPlace(buffer, read)

            // Anchored on the first buffer that actually arrives, not when
            // recording was requested — see AudioTimeline.start.
            if (!timeline.isStarted) timeline.start(System.nanoTime())

            val ptsUs = timeline.nextPtsUs()
            val packets = try {
                activeEncoder.encode(buffer, read, ptsUs)
            } catch (e: Exception) {
                Log.e(TAG, "audio encode failed — ending audio for this session", e)
                break
            }
            timeline.advance(read / capture.channels)

            for (packet in packets) {
                // The send callback crosses into whatever owns the socket, and
                // at teardown that component may already be shutting down.
                // Nothing it can throw is worth killing capture over — and
                // when it did, it killed the whole app: an uncaught
                // RejectedExecutionException here surfaced as
                // "FATAL EXCEPTION: AudioStreamer" on every stop of a session
                // with audio, on real hardware.
                try {
                    send(ptsUs, packet)
                    packetsSent++
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "audio send rejected — dropping packet", e)
                }
            }
        }
        Log.i(TAG, "audio capture ended after ${"%.1f".format(timeline.capturedSeconds())}s, $packetsSent packet(s)")
    }

    fun stop() {
        running = false
        // Released before joining: the loop is almost always parked inside
        // AudioRecord.read(), and releasing the record is what wakes it. The
        // read then returns an error, which the loop treats as end-of-capture
        // (see AudioCapture.read's catch).
        capture.stop()
        thread?.let { worker ->
            try {
                worker.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        // After the join, so release() can never land while encode() is still
        // running on the capture thread — the same ordering rule
        // CameraStreamer applies to the video encoder, for the same reason.
        encoder?.release()
        encoder = null
    }
}
