package com.framecast.streamer.streaming

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orientation cases, including the three bugs that shipped together and the
 * combinations that only appear while the phone is being turned mid-session.
 */
class RotationPolicyTest {

    private val allHolds = listOf(
        Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270,
    )

    // ---------- landscape reference ----------

    @Test
    fun phoneUprightMeansLandscapeOutputWithNoRotation() {
        // The regression this pins: without the shift CameraX asked for 90
        // degrees and the stream came out rotated in OBS while the viewfinder
        // looked fine.
        assertEquals(Surface.ROTATION_90, RotationPolicy.landscapeTargetRotation(Surface.ROTATION_0))
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_90))
    }

    @Test
    fun everyOrientationShiftsByExactlyOneQuarterTurn() {
        listOf(
            Surface.ROTATION_0 to Surface.ROTATION_90,
            Surface.ROTATION_90 to Surface.ROTATION_180,
            Surface.ROTATION_180 to Surface.ROTATION_270,
            Surface.ROTATION_270 to Surface.ROTATION_0,
        ).forEach { (input, expected) ->
            assertEquals(expected, RotationPolicy.landscapeTargetRotation(input))
        }
    }

    @Test
    fun mappingIsABijectionSoNoOrientationIsLost() {
        assertEquals(4, allHolds.map { RotationPolicy.landscapeTargetRotation(it) }.toSet().size)
    }

    @Test
    fun fourSuccessiveTurnsReturnToTheStart() {
        var r = Surface.ROTATION_0
        repeat(4) { r = RotationPolicy.landscapeTargetRotation(r) }
        assertEquals(Surface.ROTATION_0, r)
    }

    @Test
    fun unexpectedValueFallsBackInsteadOfPropagatingGarbage() {
        assertEquals(Surface.ROTATION_0, RotationPolicy.landscapeTargetRotation(99))
        assertEquals(Surface.ROTATION_0, RotationPolicy.landscapeTargetRotation(-1))
    }

    // ---------- sensor maths ----------

    @Test
    fun sensorRotationCoversAllFourHoldsForATypicalBackCamera() {
        assertEquals(90, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_0))
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_90))
        assertEquals(270, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_180))
        assertEquals(180, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_270))
    }

    @Test
    fun sensorRotationHandlesOtherMountings() {
        // Not every device mounts at 90: 270 is common on front cameras, 0 on
        // some tablets.
        assertEquals(270, RotationPolicy.sensorRotationDegrees(270, Surface.ROTATION_0))
        assertEquals(0, RotationPolicy.sensorRotationDegrees(0, Surface.ROTATION_0))
        assertEquals(180, RotationPolicy.sensorRotationDegrees(0, Surface.ROTATION_180))
    }

    @Test
    fun sensorRotationIsAlwaysANormalisedQuarterTurn() {
        listOf(0, 90, 180, 270).forEach { mounting ->
            allHolds.forEach { hold ->
                val d = RotationPolicy.sensorRotationDegrees(mounting, hold)
                assertTrue("got $d for mounting=$mounting hold=$hold", d in 0..270 && d % 90 == 0)
            }
        }
    }

    // ---------- accelerometer buckets ----------

    @Test
    fun uprightIsPortrait() {
        listOf(0, 10, 350, 359, 44, 315, 330).forEach {
            assertEquals("degrees=$it", Surface.ROTATION_0, RotationPolicy.bucketFor(it))
        }
    }

    @Test
    fun landscapeLeftAndRightAreDistinct() {
        assertEquals(Surface.ROTATION_270, RotationPolicy.bucketFor(90))
        assertEquals(Surface.ROTATION_90, RotationPolicy.bucketFor(270))
    }

    @Test
    fun upsideDownIsItsOwnBucket() {
        assertEquals(Surface.ROTATION_180, RotationPolicy.bucketFor(180))
    }

    @Test
    fun bucketBoundariesAreExactAndDoNotOverlap() {
        // Off-by-one here silently rotates the recording, so pin every edge.
        assertEquals(Surface.ROTATION_0, RotationPolicy.bucketFor(44))
        assertEquals(Surface.ROTATION_270, RotationPolicy.bucketFor(45))
        assertEquals(Surface.ROTATION_270, RotationPolicy.bucketFor(134))
        assertEquals(Surface.ROTATION_180, RotationPolicy.bucketFor(135))
        assertEquals(Surface.ROTATION_180, RotationPolicy.bucketFor(224))
        assertEquals(Surface.ROTATION_90, RotationPolicy.bucketFor(225))
        assertEquals(Surface.ROTATION_90, RotationPolicy.bucketFor(314))
        assertEquals(Surface.ROTATION_0, RotationPolicy.bucketFor(315))
    }

    @Test
    fun everyDegreeMapsToAValidBucket() {
        val valid = allHolds.toSet()
        (0..359).forEach { assertTrue("degrees=$it", RotationPolicy.bucketFor(it) in valid) }
    }

    @Test
    fun rotatingThroughAFullCircleGivesStableRunsWithoutFlapping() {
        // Four boundaries across 0..359, not three: the portrait bucket wraps
        // (315..359 and 0..44), so sweeping the range crosses it twice. Any
        // other count would mean a bucket is fragmented, i.e. the orientation
        // would flap while the phone is held still near an edge.
        val transitions = (1..359).count {
            RotationPolicy.bucketFor(it) != RotationPolicy.bucketFor(it - 1)
        }
        assertEquals(4, transitions)
        assertEquals(RotationPolicy.bucketFor(359), RotationPolicy.bucketFor(0))
    }

    // ---------- composed: turning the phone mid-session ----------

    @Test
    fun everyPhysicalHoldProducesAValidStreamRotation() {
        listOf(0, 90, 180, 270).forEach { degrees ->
            val hold = RotationPolicy.bucketFor(degrees)
            val target = RotationPolicy.landscapeTargetRotation(hold)
            val applied = RotationPolicy.sensorRotationDegrees(90, target)
            assertTrue("hold=$hold applied=$applied", applied % 90 == 0 && applied in 0..270)
        }
    }

    @Test
    fun uprightPhoneNeedsNoCorrectionAtAll() {
        val target = RotationPolicy.landscapeTargetRotation(RotationPolicy.bucketFor(0))
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, target))
    }

    @Test
    fun turningTheDeviceDuringAStreamStaysDeterministic() {
        // Repeatedly re-deriving from the same hold must never drift: a
        // reconnect or a resolution change re-runs this path mid-session.
        listOf(0, 90, 180, 270).forEach { degrees ->
            val first = RotationPolicy.landscapeTargetRotation(RotationPolicy.bucketFor(degrees))
            repeat(5) {
                assertEquals(first, RotationPolicy.landscapeTargetRotation(RotationPolicy.bucketFor(degrees)))
            }
        }
    }

    @Test
    fun rotationIsIndependentOfResolutionAndFps() {
        // Resolution and fps changes rebind the camera; the orientation result
        // must depend only on how the phone is held.
        val expected = allHolds.map { RotationPolicy.landscapeTargetRotation(it) }
        repeat(3) {
            assertEquals(expected, allHolds.map { RotationPolicy.landscapeTargetRotation(it) })
        }
    }
}
