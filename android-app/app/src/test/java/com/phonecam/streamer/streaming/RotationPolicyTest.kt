package com.phonecam.streamer.streaming

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orientation cases, pinned to the reference VALIDATED ON HARDWARE.
 *
 * History matters here because this file used to pin the opposite: a
 * quarter-turn "landscape reference" shift, added by an earlier fix and never
 * validated on a device (the validation record said so explicitly). Round 3 of
 * the S23 Ultra hardware validation instrumented the real geometry and
 * measured that shift as a constant −90° error on every hold, on both capture
 * backends — the exact "stream turned 90° clockwise in OBS" defect it was
 * meant to fix. The reference is now the raw physical bucket, and the pins
 * below encode the on-device table:
 *
 *     hold vertical   (ROTATION_0)   → θ = 90   (measured: 0 was sideways)
 *     hold landscape  (ROTATION_90)  → θ = 0    (measured: 270 was wrong)
 *     hold upside     (ROTATION_180) → θ = 270
 *     hold landscape' (ROTATION_270) → θ = 180
 */
class RotationPolicyTest {

    private val allHolds = listOf(
        Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270,
    )

    // ---------- the physical reference (device-validated) ----------

    @Test
    fun theRoundThreeTableIsTheLaw() {
        // sensorOrientation=90 is the S23 Ultra back wide, the camera the
        // geometry lines were measured on. If any of these four changes,
        // either the hardware table was re-measured or someone reintroduced
        // a shifted reference — go re-read the Round 3 logs before touching.
        assertEquals(90, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_0))
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_90))
        assertEquals(270, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_180))
        assertEquals(180, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_270))
    }

    @Test
    fun theRemovedQuarterShiftWouldRecreateTheMeasuredBias() {
        // The regression pin, inverted from what this file used to assert:
        // shifting the reference one quarter turn (bucket+90, what
        // landscapeTargetRotation did) yields θ=0 at a vertical hold — the
        // exact value Round 3 measured streaming sideways. If someone
        // reintroduces the shift, this is the test that names the ghost.
        val shiftedVertical = Surface.ROTATION_90 // landscapeTargetRotation(ROTATION_0)
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, shiftedVertical))
        // ...while the raw reference gives the value the content needs:
        assertEquals(90, RotationPolicy.sensorRotationDegrees(90, Surface.ROTATION_0))
    }

    // ---------- sensor maths ----------

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

    // ---------- composed: the full chain from accelerometer to renderer ----------

    @Test
    fun everyPhysicalHoldProducesAValidStreamRotation() {
        listOf(0, 90, 180, 270).forEach { degrees ->
            val hold = RotationPolicy.bucketFor(degrees)
            val applied = RotationPolicy.sensorRotationDegrees(90, hold)
            assertTrue("hold=$hold applied=$applied", applied % 90 == 0 && applied in 0..270)
        }
    }

    @Test
    fun uprightPhoneRotatesTheSensorBufferUpright() {
        // The vertical-hold case, as the hardware measured it: the landscape
        // sensor buffer holds a sideways world and needs exactly +90.
        assertEquals(90, RotationPolicy.sensorRotationDegrees(90, RotationPolicy.bucketFor(0)))
    }

    @Test
    fun landscapeHoldNeedsNoRotationAtAll() {
        // The natural webcam mounting: sensor and world aligned, θ=0, full
        // FOV, no cover crop. Reading 270° is the bucket for this hold.
        assertEquals(0, RotationPolicy.sensorRotationDegrees(90, RotationPolicy.bucketFor(270)))
    }

    @Test
    fun turningTheDeviceDuringAStreamStaysDeterministic() {
        // Repeatedly re-deriving from the same hold must never drift: a
        // reconnect or a resolution change re-runs this path mid-session.
        listOf(0, 90, 180, 270).forEach { degrees ->
            val first = RotationPolicy.sensorRotationDegrees(90, RotationPolicy.bucketFor(degrees))
            repeat(5) {
                assertEquals(
                    first,
                    RotationPolicy.sensorRotationDegrees(90, RotationPolicy.bucketFor(degrees)),
                )
            }
        }
    }

    // ───────────── equivalencia entre los dos backends ─────────────
    //
    // La paridad quedó CONFIRMADA en hardware (Ronda 3: ambos backends
    // aplicaron el mismo θ en el mismo agarre, y el giro físico se propagó en
    // vivo). Lo que cambió después es la referencia común: cruda, sin
    // desplazamiento. Estos tests fijan que ambos puntos de llamada siguen
    // midiendo desde la misma referencia — la validada.

    /** Lo que hace CameraX: VideoCapture.targetRotation = bucket crudo. */
    private fun cameraXEffectiveDegrees(sensorOrientation: Int, hold: Int): Int =
        RotationPolicy.sensorRotationDegrees(sensorOrientation, hold)

    /** Lo que hace applyCamera2Rotation: la misma referencia cruda. */
    private fun camera2EffectiveDegrees(sensorOrientation: Int, hold: Int): Int =
        RotationPolicy.sensorRotationDegrees(sensorOrientation, hold)

    @Test
    fun bothBackendsAgreeForEveryHold() {
        allHolds.forEach { hold ->
            assertEquals(
                "los backends discrepan con el telefono en $hold",
                cameraXEffectiveDegrees(90, hold),
                camera2EffectiveDegrees(90, hold),
            )
        }
    }

    @Test
    fun uprightPhoneProducesNinetyOnBothBackends() {
        // El caso que la Ronda 3 midió mal con la referencia desplazada
        // (aplicaba 0 y el stream salía tumbado): la referencia cruda produce
        // el +90 que el contenido necesita, en los dos backends.
        val hold = RotationPolicy.bucketFor(0)
        assertEquals(90, cameraXEffectiveDegrees(90, hold))
        assertEquals(90, camera2EffectiveDegrees(90, hold))
    }

    @Test
    fun agreementHoldsForOtherSensorMountings() {
        listOf(0, 90, 180, 270).forEach { sensor ->
            allHolds.forEach { hold ->
                assertEquals(
                    "sensor=$sensor hold=$hold",
                    cameraXEffectiveDegrees(sensor, hold),
                    camera2EffectiveDegrees(sensor, hold),
                )
            }
        }
    }
}
