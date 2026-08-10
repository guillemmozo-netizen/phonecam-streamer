package com.phonecam.streamer.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic and naming in the Camera2 report that does not need a HAL.
 *
 * The rest — that every section is present and populated on a real device —
 * cannot be asserted here at all and lives in the instrumentation test
 * `CameraDiagnosticsDeviceTest`, which runs the probe against actual hardware.
 * These are the pieces that would still be wrong on a perfect device.
 */
class CameraDiagnosticsTest {

    @Test
    fun pixelPitchIsSensorWidthOverPixelCount() {
        // S23 Ultra main camera, from its own report: 9.792mm across 4080
        // pixels is 2.4µm, which is the number that predicts low-light
        // behaviour and which Camera2 never publishes directly.
        val pitch = CameraDiagnostics.pixelPitchUm(9.792f, 4080)!!
        assertEquals(2.4f, pitch, 0.01f)
    }

    @Test
    fun pixelPitchIsAbsentRatherThanZeroWhenTheSensorDoesNotSay() {
        // The rule the whole report rests on: a missing input produces a
        // missing answer, never a plausible-looking 0.0 that a reader would
        // take for a measurement.
        assertNull(CameraDiagnostics.pixelPitchUm(null, 4080))
        assertNull(CameraDiagnostics.pixelPitchUm(9.792f, null))
        assertNull(CameraDiagnostics.pixelPitchUm(9.792f, 0))
    }

    @Test
    fun hardwareLevelsReadAsTheirDocumentedNames() {
        // The numbering is not ordered by capability — LIMITED is 0, FULL is
        // 1, LEGACY is 2, LEVEL_3 is 3 — so printing the raw int, or sorting
        // by it, is actively misleading.
        assertEquals("LIMITED", CameraDiagnostics.hardwareLevelName(0))
        assertEquals("FULL", CameraDiagnostics.hardwareLevelName(1))
        assertEquals("LEGACY", CameraDiagnostics.hardwareLevelName(2))
        assertEquals("LEVEL_3", CameraDiagnostics.hardwareLevelName(3))
        assertEquals("EXTERNAL", CameraDiagnostics.hardwareLevelName(4))
        assertEquals("UNKNOWN", CameraDiagnostics.hardwareLevelName(null))
    }

    @Test
    fun shutterLabelsMatchTheManualExposureControl() {
        // One formatter for both, so a range shown in diagnostics reads the
        // same as the stop the shutter slider offers.
        assertEquals("1/60", CameraDiagnostics.shutterLabel(16_666_666L))
        assertEquals("1/7", CameraDiagnostics.shutterLabel(150_001_124L))
    }
}
