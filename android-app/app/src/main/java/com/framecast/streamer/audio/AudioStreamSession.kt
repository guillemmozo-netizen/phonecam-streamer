package com.framecast.streamer.audio

import android.content.Context
import android.util.Log

private const val TAG = "AudioStreamSession"

/**
 * The microphone half of a streaming session: capture a chosen input, encode
 * it to AAC, hand access units to whoever is doing the sending.
 *
 * Owns the pairing of [AudioCapture] and [AacEncoder] so CameraStreamer deals
 * with one object that is either there or not, rather than with two lifecycles
 * that have to be started and stopped in the right order.
 *
 * [create] returns null when audio cannot be captured at all — no permission,
 * no microphone, an encoder the device refuses to configure. That is deliberate:
 * a null session makes the whole stream fall back to the original video-only
 * framing, so a phone that can't do audio produces exactly the stream it always
 * did rather than a negotiated-audio session that never sends any.
 */
class AudioStreamSession private constructor(
    private val capture: AudioCapture,
    private val encoder: AacEncoder,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrateBps: Int,
    val plan: AudioPlan,
) {
    /** AAC's AudioSpecificConfig, once the encoder has emitted it. */
    @Volatile
    var codecConfig: ByteArray? = null
        private set

    /** What was really routed and really enabled, for the UI to show. */
    val routedDeviceId: Int? get() = capture.routedDeviceId
    val actualSampleRate: Int get() = capture.actualSampleRate
    val noiseReductionActive: Boolean get() = capture.noiseReductionActive

    fun start(onConfig: (ByteArray) -> Unit, onFrame: (ByteArray) -> Unit): Boolean {
        val started = capture.start { buffer, length ->
            encoder.encode(buffer, length, onFrame)
        }
        if (!started) {
            encoder.release()
            return false
        }
        pendingConfigListener = onConfig
        codecConfig?.let(onConfig)
        return true
    }

    fun stop() {
        capture.stop()
        encoder.release()
    }

    private var pendingConfigListener: ((ByteArray) -> Unit)? = null

    private fun onEncoderConfig(config: ByteArray) {
        codecConfig = config
        pendingConfigListener?.invoke(config)
    }

    companion object {
        /**
         * Builds a session for [mic] under the user's requested settings,
         * resolving them against what the device can actually do first.
         *
         * The returned session's [plan] is what was really configured, which is
         * not necessarily what was asked for — see [MicrophonePolicy].
         */
        fun create(
            context: Context,
            mic: MicInput?,
            requestedSampleRate: Int,
            requestedChannelCount: Int,
            requestedBitrateBps: Int,
            noiseReduction: Boolean,
        ): AudioStreamSession? {
            val target = mic ?: AudioDeviceInventory.defaultInput(context)
            if (target == null) {
                Log.w(TAG, "no microphone available")
                return null
            }

            val plan = AudioDeviceInventory.planFor(
                mic = target,
                requestedSampleRate = requestedSampleRate,
                requestedChannelCount = requestedChannelCount,
                requestedBitrateBps = requestedBitrateBps,
            )
            Log.i(
                TAG,
                "audio plan for ${target.productName}: ${plan.sampleRate}Hz " +
                    "${plan.channelCount}ch ${plan.bitrateBps / 1000}kbps " +
                    "(max ${plan.maxBitrateBps / 1000}kbps) warnings=${plan.warnings}",
            )

            var session: AudioStreamSession? = null
            val encoder = try {
                AacEncoder(
                    sampleRate = plan.sampleRate,
                    channelCount = plan.channelCount,
                    bitrateBps = plan.bitrateBps,
                    // Routed through the session so codecConfig is remembered:
                    // a reconnect needs to resend it, and MediaCodec only ever
                    // emits it once.
                    onConfig = { config -> session?.onEncoderConfig(config) },
                )
            } catch (e: Exception) {
                Log.w(TAG, "no AAC encoder for ${plan.sampleRate}Hz/${plan.channelCount}ch", e)
                return null
            }

            val capture = AudioCapture(context, target, plan, noiseReduction)
            return AudioStreamSession(
                capture = capture,
                encoder = encoder,
                sampleRate = plan.sampleRate,
                channelCount = plan.channelCount,
                bitrateBps = plan.bitrateBps,
                plan = plan,
            ).also { session = it }
        }
    }
}
