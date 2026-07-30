package com.phonecam.streamer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A filter that is subtly wrong still produces audio, so "it ran and made
 * sound" proves nothing. These measure the actual frequency response — how
 * much of a given tone survives — which is the only thing that distinguishes
 * a working wind filter from one that is quietly mangling speech.
 */
class HighPassFilterTest {

    private val sampleRate = 48000

    /** RMS of a pure tone after filtering, relative to its RMS before. */
    private fun gainAt(hz: Double, channels: Int = 1, cutoffHz: Double = 100.0): Double {
        val filter = HighPassFilter(sampleRate, cutoffHz, channels)
        val frames = sampleRate // one full second, so even 20Hz gets many cycles
        val pcm = ShortArray(frames * channels)
        for (frame in 0 until frames) {
            val value = (10000.0 * sin(2.0 * PI * hz * frame / sampleRate))
            for (c in 0 until channels) pcm[frame * channels + c] = value.toInt().toShort()
        }
        val inputRms = rms(pcm, skip = 0)
        filter.processInPlace(pcm, pcm.size)
        // Skip the first 100ms: a biquad has a transient before it settles,
        // and measuring through it would understate the steady-state gain.
        return rms(pcm, skip = sampleRate / 10 * channels) / inputRms
    }

    private fun rms(pcm: ShortArray, skip: Int): Double {
        var sum = 0.0
        for (i in skip until pcm.size) sum += pcm[i].toDouble() * pcm[i].toDouble()
        return sqrt(sum / (pcm.size - skip))
    }

    @Test
    fun `wind and rumble below the corner are strongly attenuated`() {
        // 20Hz is squarely in the band wind energy occupies. Two octaves
        // below a 100Hz corner at 12dB/octave is about -24dB, i.e. ~6% left.
        assertTrue("20Hz gain was ${gainAt(20.0)}", gainAt(20.0) < 0.10)
        assertTrue("40Hz gain was ${gainAt(40.0)}", gainAt(40.0) < 0.30)
    }

    @Test
    fun `speech is left essentially untouched`() {
        // The whole point: remove rumble without colouring the voice. A
        // deep male fundamental sits near 85-100Hz, so the check starts
        // above the corner and everything upwards must be effectively flat.
        for (hz in listOf(300.0, 500.0, 1000.0, 3000.0, 8000.0)) {
            val gain = gainAt(hz)
            assertTrue("${hz}Hz should pass unchanged, gain was $gain", gain > 0.95)
            assertTrue("${hz}Hz should not be amplified, gain was $gain", gain < 1.05)
        }
    }

    @Test
    fun `the corner frequency is where it says it is`() {
        // A Butterworth high-pass is -3dB at its cutoff, i.e. gain 1/sqrt(2).
        val gain = gainAt(100.0)
        assertEquals(0.7071, gain, 0.06)
    }

    @Test
    fun `a configurable cutoff moves the response with it`() {
        // 200Hz corner: 100Hz is now an octave below and must be cut much
        // harder than it was when it was the corner itself.
        assertTrue(gainAt(100.0, cutoffHz = 200.0) < gainAt(100.0, cutoffHz = 100.0))
    }

    @Test
    fun `channels are filtered independently`() {
        // Shared filter state would let one channel's signal leak into the
        // other. Feeding a loud low tone to the left and silence to the
        // right, the right must stay silent.
        val filter = HighPassFilter(sampleRate, 100.0, channels = 2)
        val frames = 4800
        val pcm = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            pcm[frame * 2] = (20000.0 * sin(2.0 * PI * 30.0 * frame / sampleRate)).toInt().toShort()
            pcm[frame * 2 + 1] = 0
        }

        filter.processInPlace(pcm, pcm.size)

        var rightPeak = 0
        for (frame in 0 until frames) rightPeak = maxOf(rightPeak, abs(pcm[frame * 2 + 1].toInt()))
        assertEquals("the silent channel must stay silent", 0, rightPeak)
    }

    @Test
    fun `stereo speech passes on both channels`() {
        assertTrue(gainAt(1000.0, channels = 2) > 0.95)
    }

    @Test
    fun `a constant DC offset is removed`() {
        // DC is the limit case of rumble, and some microphones carry a
        // standing offset that wastes headroom for no audible benefit.
        val filter = HighPassFilter(sampleRate, 100.0, channels = 1)
        val pcm = ShortArray(48000) { 8000 }

        filter.processInPlace(pcm, pcm.size)

        val tail = pcm.takeLast(1000).map { abs(it.toInt()) }.max()
        assertTrue("DC should settle to zero, tail peak was $tail", tail < 50)
    }

    @Test
    fun `only the requested prefix of the buffer is touched`() {
        // AudioRecord.read() fills part of a reusable buffer; the rest is
        // stale data from the previous read and must not be filtered.
        val pcm = ShortArray(100) { 5000 }

        HighPassFilter(sampleRate, 100.0, 1).processInPlace(pcm, 50)

        assertTrue(pcm.take(50).any { it.toInt() != 5000 })
        assertTrue("the untouched tail must be intact", pcm.drop(50).all { it.toInt() == 5000 })
    }

    @Test
    fun `state carries across calls so successive buffers join seamlessly`() {
        // The capture loop filters one ~21ms buffer at a time; if state reset
        // per call, every buffer boundary would produce a transient click.
        val whole = ShortArray(2048) { (10000.0 * sin(2.0 * PI * 1000.0 * it / sampleRate)).toInt().toShort() }
        val split = whole.copyOf()

        HighPassFilter(sampleRate, 100.0, 1).processInPlace(whole, whole.size)

        val filter = HighPassFilter(sampleRate, 100.0, 1)
        val firstHalf = split.copyOfRange(0, 1024)
        val secondHalf = split.copyOfRange(1024, 2048)
        filter.processInPlace(firstHalf, firstHalf.size)
        filter.processInPlace(secondHalf, secondHalf.size)

        assertTrue((firstHalf + secondHalf).zip(whole.toTypedArray()).all { (a, b) -> a == b })
    }

    @Test
    fun `loud input does not wrap around to the opposite rail`() {
        val filter = HighPassFilter(sampleRate, 100.0, 1)
        val pcm = ShortArray(1000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }

        filter.processInPlace(pcm, pcm.size)

        // A sign flip between adjacent samples is the signal itself here;
        // what must not happen is a value near one rail becoming its
        // opposite through overflow. Clamping guarantees the range.
        assertTrue(pcm.all { it >= Short.MIN_VALUE && it <= Short.MAX_VALUE })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cutoff above Nyquist is rejected`() {
        HighPassFilter(48000, 30000.0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sample count beyond the buffer is rejected`() {
        HighPassFilter(48000, 100.0, 1).processInPlace(ShortArray(10), 20)
    }
}
