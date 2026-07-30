package com.phonecam.streamer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These timestamps are the only thing letting the PC line audio up with
 * video (pc_receiver/av_sync.py), and a mistake in them shows up as lip-sync
 * that is subtly off — the hardest kind of bug to pin down from a device.
 * The arithmetic is therefore checked directly rather than inferred from how
 * a stream sounds.
 */
class AudioTimelineTest {

    private val anchorNanos = 1_000_000_000L // 1s on the nanoTime clock

    @Test
    fun `first timestamp is the anchor itself`() {
        val timeline = AudioTimeline(48000)
        timeline.start(anchorNanos)

        assertEquals(1_000_000L, timeline.nextPtsUs())
    }

    @Test
    fun `timestamps advance by exactly the duration of the samples captured`() {
        val timeline = AudioTimeline(48000)
        timeline.start(anchorNanos)

        timeline.advance(48000) // one second
        assertEquals(2_000_000L, timeline.nextPtsUs())

        timeline.advance(1024) // one AAC frame, 21333us
        assertEquals(2_021_333L, timeline.nextPtsUs())
    }

    @Test
    fun `spacing is even regardless of how unevenly buffers arrive`() {
        // The point of counting samples instead of reading the clock: thread
        // scheduling jitter must not reach the timestamps. A 1024-sample AAC
        // frame is 21333.33us, so consecutive gaps alternate between 21333
        // and 21334 — that rounding is the only variation permitted.
        val timeline = AudioTimeline(48000)
        timeline.start(anchorNanos)

        val stamps = mutableListOf<Long>()
        repeat(10) {
            stamps += timeline.nextPtsUs()
            timeline.advance(1024)
        }

        for (gap in stamps.zipWithNext { a, b -> b - a }) {
            assertTrue("gap $gap should be 21333 or 21334", gap in 21_333L..21_334L)
        }
    }

    @Test
    fun `rounding does not accumulate over a long session`() {
        // Each timestamp is computed from the total sample count, not by
        // adding a rounded increment to the last one. Stepping instead would
        // lose up to a microsecond per frame — around 8 seconds of drift over
        // an hour, which is lip-sync gone completely.
        val timeline = AudioTimeline(48000)
        timeline.start(0)

        repeat(48000 / 1024 * 600) { timeline.advance(1024) } // ~10 minutes

        val expectedUs = timeline.samplesCaptured * 1_000_000 / 48000
        assertEquals(expectedUs, timeline.nextPtsUs())
        assertEquals(timeline.samplesCaptured / 48000.0, timeline.capturedSeconds(), 1e-9)
    }

    @Test
    fun `a different sample rate scales the timeline`() {
        val timeline = AudioTimeline(44100)
        timeline.start(anchorNanos)

        timeline.advance(44100)
        assertEquals(2_000_000L, timeline.nextPtsUs())
    }

    @Test
    fun `a negative anchor is carried through rather than clamped`() {
        // System.nanoTime()'s origin is arbitrary and starts negative on some
        // devices. Only differences matter, so the sign must survive intact —
        // the wire format carries it as a signed value for the same reason.
        val timeline = AudioTimeline(48000)
        timeline.start(-2_000_000_000L)

        assertEquals(-2_000_000L, timeline.nextPtsUs())
        timeline.advance(48000)
        assertEquals(-1_000_000L, timeline.nextPtsUs())
    }

    @Test
    fun `restarting re-anchors and resets the sample count`() {
        val timeline = AudioTimeline(48000)
        timeline.start(anchorNanos)
        timeline.advance(96000)

        timeline.start(5_000_000_000L)

        assertEquals(0L, timeline.samplesCaptured)
        assertEquals(5_000_000L, timeline.nextPtsUs())
    }

    @Test
    fun `not started until the first buffer actually arrives`() {
        val timeline = AudioTimeline(48000)
        assertFalse(timeline.isStarted)

        timeline.start(anchorNanos)
        assertTrue(timeline.isStarted)
    }

    @Test
    fun `captured seconds tracks the sample count`() {
        val timeline = AudioTimeline(48000)
        timeline.start(anchorNanos)
        timeline.advance(24000)

        assertEquals(0.5, timeline.capturedSeconds(), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive sample rate is rejected`() {
        AudioTimeline(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `advancing backwards is rejected`() {
        AudioTimeline(48000).advance(-1)
    }
}
