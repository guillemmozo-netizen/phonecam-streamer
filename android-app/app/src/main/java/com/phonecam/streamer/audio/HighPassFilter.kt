package com.phonecam.streamer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Second-order high-pass filter, applied to captured PCM as the "wind
 * filter" Settings offers.
 *
 * ## Why this is what a wind filter is
 *
 * Wind across a microphone port is not really a sound the mic picks up — it
 * is turbulence hitting the diaphragm directly, and its energy sits almost
 * entirely below about 100Hz, well under the ~85Hz bottom of an adult
 * speaking voice. Rolling off that band removes the rumble while leaving
 * speech essentially untouched, which is exactly what a physical foam
 * windscreen does mechanically. There is no Android API for this — unlike
 * noise suppression, which [AudioCapture] hands to the platform's own
 * effect — so it is done here on the samples.
 *
 * It also earns its keep when there is no wind: [DEFAULT_CUTOFF_HZ] of
 * rumble is handling noise, desk thumps and air conditioning, none of which
 * survive it, and all of which otherwise cost bitrate the encoder could
 * spend on the voice.
 *
 * ## Implementation
 *
 * An RBJ-cookbook biquad at Q = 1/sqrt(2), which is the Butterworth case:
 * maximally flat in the passband, so speech is not coloured, with a 12
 * dB/octave slope below the corner.
 *
 * Each channel carries its own filter state, since they are independent
 * signals — sharing it would cross-talk one into the other. Interleaved
 * input is handled in place, which is what the capture loop has.
 *
 * Pure Kotlin, no Android types, so the response is measured in a unit test
 * rather than assumed from the coefficients looking right.
 */
class HighPassFilter(
    sampleRate: Int,
    cutoffHz: Double = DEFAULT_CUTOFF_HZ,
    private val channels: Int = 1,
) {

    companion object {
        /**
         * Low enough to leave a deep voice alone (an adult male fundamental
         * bottoms out around 85Hz), high enough to take out the bulk of wind
         * and handling rumble.
         */
        const val DEFAULT_CUTOFF_HZ = 100.0

        /** Butterworth: the flattest passband, i.e. no colouring of speech. */
        private const val BUTTERWORTH_Q = 0.70710678

        private const val SHORT_MIN = -32768
        private const val SHORT_MAX = 32767
    }

    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(channels > 0) { "channels must be positive, got $channels" }
        require(cutoffHz > 0 && cutoffHz < sampleRate / 2.0) {
            "cutoff ${cutoffHz}Hz must be between 0 and Nyquist (${sampleRate / 2.0}Hz)"
        }
    }

    // Coefficients, already normalised by a0.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    // Per-channel history: previous two inputs and outputs.
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    init {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha

        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = (-(1.0 + cosW0)) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    /**
     * Filters the first [sampleCount] entries of [pcm] in place. [pcm] is
     * interleaved, so entry i belongs to channel `i % channels`.
     *
     * In place because this sits directly in the capture loop, between
     * AudioRecord.read() and the encoder, and allocating a second buffer per
     * ~21ms of audio would be pure garbage for no benefit.
     */
    fun processInPlace(pcm: ShortArray, sampleCount: Int) {
        require(sampleCount <= pcm.size) {
            "sampleCount $sampleCount exceeds the ${pcm.size}-entry buffer"
        }
        for (i in 0 until sampleCount) {
            val channel = i % channels
            val x0 = pcm[i].toDouble()
            val out = b0 * x0 + b1 * x1[channel] + b2 * x2[channel] -
                a1 * y1[channel] - a2 * y2[channel]

            x2[channel] = x1[channel]
            x1[channel] = x0
            y2[channel] = y1[channel]
            y1[channel] = out

            // A high-pass cannot amplify a bounded signal by much, but a
            // biquad does overshoot on transients — clamping keeps a loud
            // click from wrapping to the opposite rail, which is far more
            // audible than the clipping it replaces.
            pcm[i] = when {
                out >= SHORT_MAX -> SHORT_MAX.toShort()
                out <= SHORT_MIN -> SHORT_MIN.toShort()
                else -> out.toInt().toShort()
            }
        }
    }
}
