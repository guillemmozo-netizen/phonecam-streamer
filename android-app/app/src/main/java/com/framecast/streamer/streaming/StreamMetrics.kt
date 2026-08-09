package com.framecast.streamer.streaming

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

private const val WINDOW_NANOS = 2_000_000_000L

/**
 * Per-session pipeline instrumentation, shared by both capture backends.
 *
 * Replaces the ad-hoc counters CameraStreamer used to keep inline. The point
 * is to be able to answer "where did the frames go?" from a log line alone,
 * because every stage can independently be the bottleneck and they look
 * identical from the outside (the PC just sees a slow stream):
 *
 *  - capture fps   : what the camera's own capture callback reports completing
 *  - frame fps     : what actually reached our SurfaceTexture
 *  - encode fps    : frames the encoder accepted, plus how long encode() took
 *  - send fps      : frames written to the socket, plus how long the write took
 *
 * A blocking socket write is the honest measure of network backpressure: when
 * the far side can't keep up, TCP's send buffer fills and write() starts
 * taking milliseconds it never took before. That shows up here as send latency
 * climbing while encode latency stays flat.
 *
 * Every counter is atomic — these are written from three different threads
 * (camera callback, GL/encode, network) and read from a fourth.
 */
class StreamMetrics(private val tag: String) {

    /** Why a latched camera frame never became a sent frame. */
    enum class Drop { THROTTLED, NO_CONNECTION, ENCODER_FAILED, ENCODE_EXCEPTION, SEND_FAILED }

    private val captureCompleted = AtomicInteger()
    private val cameraFrames = AtomicInteger()
    private val encodedFrames = AtomicInteger()
    private val encodeNanos = AtomicLong()
    private val sentFrames = AtomicInteger()
    private val sentBytes = AtomicLong()
    private val sendNanos = AtomicLong()
    private val drops = AtomicIntegerArray(Drop.entries.size)

    private val windowStartNanos = AtomicLong(0)

    /** Actual capture geometry/rate, as negotiated — not what was requested. */
    @Volatile var captureSize: String = "?"
    @Volatile var negotiatedFps: String = "?"
    @Volatile var encoderSize: String = "?"
    @Volatile var targetFps: Int = 0
    @Volatile var backend: String = "?"

    fun onCaptureCompleted() { captureCompleted.incrementAndGet() }

    fun onCameraFrame() { cameraFrames.incrementAndGet() }

    fun onEncoded(latencyNanos: Long) {
        encodedFrames.incrementAndGet()
        encodeNanos.addAndGet(latencyNanos)
    }

    fun onSent(latencyNanos: Long, bytes: Int) {
        sentFrames.incrementAndGet()
        sendNanos.addAndGet(latencyNanos)
        sentBytes.addAndGet(bytes.toLong())
    }

    fun onDrop(reason: Drop) { drops.incrementAndGet(reason.ordinal) }

    /** One-line summary of what the session actually negotiated — logged once at start. */
    fun describeSession(): String =
        "backend=$backend capture=$captureSize@$negotiatedFps encoder=$encoderSize target=${targetFps}fps"

    /**
     * Logs and resets once per window. Safe to call on every frame from the GL
     * thread; the CAS on the window start means a concurrent caller can't emit
     * a second line for the same window.
     */
    fun logIfWindowElapsed() {
        val now = System.nanoTime()
        val start = windowStartNanos.get()
        if (start == 0L) {
            windowStartNanos.compareAndSet(0L, now)
            return
        }
        val elapsed = now - start
        if (elapsed < WINDOW_NANOS) return
        if (!windowStartNanos.compareAndSet(start, now)) return

        val seconds = elapsed / 1e9
        val captured = captureCompleted.getAndSet(0)
        val frames = cameraFrames.getAndSet(0)
        val encoded = encodedFrames.getAndSet(0)
        val encNanos = encodeNanos.getAndSet(0)
        val sent = sentFrames.getAndSet(0)
        val sndNanos = sendNanos.getAndSet(0)
        val bytes = sentBytes.getAndSet(0)

        val dropText = Drop.entries.joinToString(",") { "${it.name.lowercase()}=${drops.getAndSet(it.ordinal, 0)}" }

        Log.i(
            tag,
            "metrics[$backend] capture=${fps(captured, seconds)} frames=${fps(frames, seconds)} " +
                "encode=${fps(encoded, seconds)} send=${fps(sent, seconds)} | " +
                "enc_lat=${ms(encNanos, encoded)} net_lat=${ms(sndNanos, sent)} | " +
                "bitrate=${"%.1f".format(bytes * 8 / seconds / 1_000_000)}Mbps | " +
                "size=$captureSize->$encoderSize@$negotiatedFps | dropped: $dropText",
        )
    }

    private fun fps(count: Int, seconds: Double): String = "%.1f".format(count / seconds)

    private fun ms(totalNanos: Long, count: Int): String =
        if (count <= 0) "n/a" else "%.1fms".format(totalNanos / count / 1_000_000.0)
}
