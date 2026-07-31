package com.phonecam.streamer.streaming

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The debounce that decides when a physical turn is real, driven with an
 * explicit clock so the timing cases run instantly and deterministically.
 *
 * The first two tests pin the bug this class was extracted to fix: the logic
 * used to compare the incoming device bucket against VideoCapture.targetRotation,
 * which holds a quarter-turn-offset value, so the two could never be compared
 * meaningfully.
 */
class RotationDebouncerTest {

    private val debounceMs = 400L

    /** Feeds one reading repeatedly until the debounce elapses, returning what
     * was applied - what physically turning the phone and holding it does. */
    private fun RotationDebouncer.hold(degrees: Int, startMs: Long): Int? {
        onOrientationChanged(degrees, startMs)
        onOrientationChanged(degrees, startMs + debounceMs / 2)
        return onOrientationChanged(degrees, startMs + debounceMs)
    }

    // Raw accelerometer angles, one comfortably inside each bucket.
    private val portrait = 0
    private val landscapeLeft = 90     // -> ROTATION_270
    private val upsideDown = 180       // -> ROTATION_180
    private val landscapeRight = 270   // -> ROTATION_90

    // ---------- the extracted bug ----------

    @Test
    fun turningFromPortraitToLandscapeRightIsApplied() {
        // ROTATION_90 is exactly the value the (since removed) quarter-turn
        // shift used to store for portrait, so the old comparison saw "no
        // change" and dropped this turn entirely: one of the two directions
        // never rotated the stream. The debouncer's own-space tracking fixed
        // it, and stays valid under the raw reference.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_0, d.hold(portrait, 0))
        assertEquals(Surface.ROTATION_90, d.hold(landscapeRight, 1_000))
    }

    @Test
    fun everyOneStepAnticlockwiseTurnIsApplied() {
        // The same collision exists at each bucket, since the offset is uniform.
        val sequence = listOf(
            portrait to Surface.ROTATION_0,
            landscapeRight to Surface.ROTATION_90,
            upsideDown to Surface.ROTATION_180,
            landscapeLeft to Surface.ROTATION_270,
        )
        val d = RotationDebouncer(debounceMs)
        var now = 0L
        for ((degrees, expected) in sequence) {
            assertEquals(expected, d.hold(degrees, now))
            now += 1_000
        }
    }

    @Test
    fun aStationaryPhoneStopsReportingOnceApplied() {
        // The old guard could not match, so a phone lying still re-applied its
        // rotation every debounce window, pushing a fresh TransformationInfo
        // through CameraX forever.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_0, d.hold(portrait, 0))
        for (i in 1..50) {
            assertNull(d.onOrientationChanged(portrait, 1_000 + i * 100L))
        }
    }

    // ---------- debounce timing ----------

    @Test
    fun aReadingHeldForLessThanTheWindowIsNotApplied() {
        val d = RotationDebouncer(debounceMs)
        assertNull(d.onOrientationChanged(landscapeRight, 0))
        assertNull(d.onOrientationChanged(landscapeRight, debounceMs - 1))
    }

    @Test
    fun aReadingHeldForExactlyTheWindowIsApplied() {
        val d = RotationDebouncer(debounceMs)
        d.onOrientationChanged(landscapeRight, 0)
        assertEquals(Surface.ROTATION_90, d.onOrientationChanged(landscapeRight, debounceMs))
    }

    @Test
    fun jitterAcrossABoundaryNeverApplies() {
        // 44/45 straddles the portrait/landscape-left boundary. Flipping back
        // and forth restarts the window each time, which is the whole point.
        val d = RotationDebouncer(debounceMs)
        var now = 0L
        repeat(40) {
            assertNull(d.onOrientationChanged(if (it % 2 == 0) 44 else 45, now))
            now += 100
        }
    }

    @Test
    fun aTurnThatSettlesAfterJitterIsStillApplied() {
        val d = RotationDebouncer(debounceMs)
        d.onOrientationChanged(44, 0)
        d.onOrientationChanged(45, 100)
        d.onOrientationChanged(44, 200)
        // Now it settles for real.
        assertEquals(Surface.ROTATION_270, d.hold(landscapeLeft, 300))
    }

    @Test
    fun aBriefTurnThatReturnsIsNeverApplied() {
        // Glancing at the phone sideways and putting it back must not rotate it.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_0, d.hold(portrait, 0))
        assertNull(d.onOrientationChanged(landscapeRight, 1_000))
        assertNull(d.onOrientationChanged(landscapeRight, 1_100))
        assertNull(d.onOrientationChanged(portrait, 1_200))
        assertNull(d.onOrientationChanged(portrait, 2_000))
    }

    // ---------- first reading ----------

    @Test
    fun theFirstStableReadingIsAlwaysApplied() {
        // Nothing has been applied yet, so even portrait has to be pushed -
        // the use case's default is not necessarily where the phone is.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_0, d.hold(portrait, 0))
    }

    // ---------- flat phone ----------

    @Test
    fun unknownOrientationIsIgnored() {
        // ORIENTATION_UNKNOWN is -1, reported when the phone is flat on a desk.
        // bucketFor would classify it as portrait and turn the stream.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_90, d.hold(landscapeRight, 0))
        for (i in 0..20) {
            assertNull(d.onOrientationChanged(-1, 1_000 + i * 100L))
        }
    }

    @Test
    fun unknownReadingsDoNotCancelATurnInProgress() {
        val d = RotationDebouncer(debounceMs)
        d.onOrientationChanged(landscapeRight, 0)
        assertNull(d.onOrientationChanged(-1, 100))
        assertEquals(Surface.ROTATION_90, d.onOrientationChanged(landscapeRight, debounceMs))
    }

    // ---------- reset ----------

    @Test
    fun resetMakesTheNextStableReadingApplyAgain() {
        // A camera rebind starts from the use case's own default, so the
        // debouncer must not think its value is still in effect.
        val d = RotationDebouncer(debounceMs)
        assertEquals(Surface.ROTATION_90, d.hold(landscapeRight, 0))
        assertNull(d.onOrientationChanged(landscapeRight, 1_000))
        d.reset()
        assertEquals(Surface.ROTATION_90, d.hold(landscapeRight, 2_000))
    }

    @Test
    fun resetDiscardsATurnInProgress() {
        val d = RotationDebouncer(debounceMs)
        d.onOrientationChanged(landscapeRight, 0)
        d.reset()
        // The pending window is gone, so this reading starts a fresh one.
        assertNull(d.onOrientationChanged(landscapeRight, debounceMs))
    }

    // ---------- full sweep ----------

    @Test
    fun aSlowFullTurnAppliesEveryBucketExactlyOnce() {
        val d = RotationDebouncer(debounceMs)
        val applied = mutableListOf<Int>()
        var now = 0L
        for (degrees in 0..359) {
            // Each degree held long enough to clear the window, as in a slow turn.
            repeat(2) {
                d.onOrientationChanged(degrees, now)?.let { applied.add(it) }
                now += debounceMs
            }
        }
        assertEquals(
            listOf(
                Surface.ROTATION_0,    // 0..44
                Surface.ROTATION_270,  // 45..134
                Surface.ROTATION_180,  // 135..224
                Surface.ROTATION_90,   // 225..314
                Surface.ROTATION_0,    // 315..359, wrapping back to portrait
            ),
            applied,
        )
    }
}
