package com.phonecam.streamer.streaming

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Forensic reproduction of the Camera2 rotation-state divergence, built from
 * the real [RotationDebouncer] and [RotationPolicy] plus the literal wiring in
 * MainActivity:
 *
 *  - the orientation listener feeds the debouncer and, when it emits, pushes
 *    the raw bucket to CameraX's targetRotation and applyCamera2Rotation(bucket)
 *    to Camera2 (the quarter-turn shift was removed after Round 3 of the
 *    hardware validation measured it as a constant −90° bias);
 *  - startCamera2Backend seeds Camera2 with a hardcoded
 *    applyCamera2Rotation(Surface.ROTATION_0) (MainActivity:1110);
 *  - rotationDebouncer.reset() exists ONLY inside startCamera()'s CameraX bind
 *    (MainActivity:1508); the Camera2 branch of startStreaming
 *    (MainActivity:954-956) never resets it.
 *
 * The consequence pinned here: if the phone is already held in a bucket the
 * debouncer has previously applied, Camera2 starts on the upright guess and
 * the debouncer's dedup guarantees the correction NEVER arrives — a
 * quarter-turn error that lasts the whole session, on exactly the one lens
 * that can use Camera2. The CameraX branch heals itself because its rebind
 * resets the debouncer; the missing reset in the Camera2 branch is the bug.
 */
class Camera2RotationStateForensicsTest {

    // S23 Ultra back wide — Camera2CaptureSource reads 90 for this sensor.
    private val sensorOrientation = 90
    private val debounceMs = 400L

    /** What MainActivity's listener applies to Camera2 when the debouncer emits [bucket]. */
    private fun camera2ThetaFor(bucket: Int): Int =
        RotationPolicy.sensorRotationDegrees(sensorOrientation, bucket)

    /** Feeds a steady physical hold until the debouncer emits, returning the emission. */
    private fun saturate(debouncer: RotationDebouncer, orientationDegrees: Int, startMs: Long): Pair<Int?, Long> {
        var now = startMs
        var emitted: Int? = null
        repeat(20) {
            debouncer.onOrientationChanged(orientationDegrees, now)?.let { emitted = it }
            now += 100
        }
        return emitted to now
    }

    @Test
    fun theIdlePreviewSaturatesTheDebouncerWithTheRealHold() {
        // Landscape mount: OrientationEventListener reads ~270°, which buckets
        // to ROTATION_90 — the debouncer sees it and records it as applied.
        val debouncer = RotationDebouncer(debounceMs)
        val (emitted, _) = saturate(debouncer, 270, startMs = 0)
        assertEquals(Surface.ROTATION_90, emitted)
    }

    /**
     * The fixed start sequence, as now wired in startCamera2Backend: capture
     * the known hold, reset the debouncer, apply. Replaces the old hardcoded
     * applyCamera2Rotation(ROTATION_0) whose failure mode the test below
     * documents.
     */
    private fun camera2StartTheta(debouncer: RotationDebouncer): Int {
        val knownBucket = debouncer.lastAppliedBucket() ?: Surface.ROTATION_0
        debouncer.reset()
        return camera2ThetaFor(knownBucket)
    }

    @Test
    fun theStartSequenceUsesTheRealHoldForEveryBucket() {
        // (orientation reading, expected bucket) for the four mounts.
        val holds = listOf(
            0 to Surface.ROTATION_0,
            270 to Surface.ROTATION_90,
            180 to Surface.ROTATION_180,
            90 to Surface.ROTATION_270,
        )
        for ((reading, bucket) in holds) {
            val debouncer = RotationDebouncer(debounceMs)
            saturate(debouncer, reading, startMs = 0)
            // Parity: Camera2 starts with exactly the θ CameraX applies for
            // the same hold — the divergence that streamed the 1x a quarter
            // turn off cannot open at attach time any more.
            assertEquals("hold $reading°", camera2ThetaFor(bucket), camera2StartTheta(debouncer))
        }
    }

    @Test
    fun theStartSequenceFallsBackToUprightWhenNoHoldWasEverSeen() {
        // Fresh launch, phone flat (ORIENTATION_UNKNOWN): nothing applied yet.
        val debouncer = RotationDebouncer(debounceMs)
        assertEquals(camera2ThetaFor(Surface.ROTATION_0), camera2StartTheta(debouncer))
    }

    @Test
    fun afterTheStartResetTheNextStableReadingReEmits() {
        // The second half of the fix: even if the captured hold were stale,
        // the reset guarantees the listener path re-delivers the current
        // bucket — the dedup can no longer seal a wrong start value in.
        val debouncer = RotationDebouncer(debounceMs)
        var (_, now) = saturate(debouncer, 270, startMs = 0)
        camera2StartTheta(debouncer)

        var corrected: Int? = null
        repeat(10) {
            debouncer.onOrientationChanged(270, now)?.let { corrected = it }
            now += 100
        }
        assertEquals(Surface.ROTATION_90, corrected)
    }

    @Test
    fun theRawReferenceGivesTheDeviceValidatedValues() {
        // Round 3's measured table, camera2 flavor: vertical needs +90 (the
        // shifted reference applied 0 and the stream lay sideways), landscape
        // needs 0 (the shifted reference applied 270).
        assertEquals(90, camera2ThetaFor(Surface.ROTATION_0))
        assertEquals(0, camera2ThetaFor(Surface.ROTATION_90))
        assertEquals(270, camera2ThetaFor(Surface.ROTATION_180))
        assertEquals(180, camera2ThetaFor(Surface.ROTATION_270))
    }

    @Test
    fun theDefectThisReplaced_aHardcodedUprightWasSealedInByTheDedup() {
        // Kept as documentation of the failure mode (still real RotationDebouncer
        // semantics): with the OLD wiring — hardcoded ROTATION_0, no reset — a
        // saturated debouncer swallowed the correction forever, so a session
        // started at any non-upright hold kept the upright θ for its whole
        // life: a persistent quarter turn on exactly the one lens Camera2
        // serves. The arithmetic below is reference-independent — under the
        // (now removed) shifted reference the same mechanism produced the same
        // one-quarter divergence, just biased.
        val debouncer = RotationDebouncer(debounceMs)
        var (_, now) = saturate(debouncer, 270, startMs = 0)

        val oldWiringTheta = camera2ThetaFor(Surface.ROTATION_0)   // no capture, no reset
        repeat(100) {
            assertNull(debouncer.onOrientationChanged(270, now))
            now += 100
        }

        val correctTheta = camera2ThetaFor(Surface.ROTATION_90)
        assertEquals(90, oldWiringTheta)
        assertEquals(0, correctTheta)
        // The stream carries a full quarter-turn error until the process dies:
        assertEquals(90, (oldWiringTheta - correctTheta + 360) % 360)
    }

    @Test
    fun theCameraXBranchHealsBecauseItsRebindResetsTheDebouncer() {
        val debouncer = RotationDebouncer(debounceMs)
        var (_, now) = saturate(debouncer, 270, startMs = 0)

        // startCamera()'s bind lambda — the CameraX branch — calls reset()
        // (MainActivity:1508). The identical steady readings now re-emit.
        debouncer.reset()
        var corrected: Int? = null
        repeat(10) {
            debouncer.onOrientationChanged(270, now)?.let { corrected = it }
            now += 100
        }
        assertEquals(Surface.ROTATION_90, corrected)
        // One line of difference between the two branches; this emission is
        // exactly what the Camera2 branch never receives.
    }
}
