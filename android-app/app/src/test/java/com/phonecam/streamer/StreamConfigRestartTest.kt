package com.phonecam.streamer

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may change live and what forces a session restart.
 *
 * The line this defends: the running session's encoder shape and its Hello
 * announcement are built once from the config the session started with. A
 * Settings change that alters that geometry cannot be hot-applied (the
 * receiver sized its decoder and the virtual camera from the Hello), so
 * onResume restarts the session for exactly these fields — and must NOT
 * restart for anything else, or every cosmetic toggle would drop the stream.
 */
class StreamConfigRestartTest {

    private val base = StreamConfig(
        qualityLabel = "1080p",
        fps = 30,
        videoBitrateBps = 20_000_000,
        hdr = false,
        stabilization = true,
        whiteBalanceMode = 1,
        lensFacing = CameraSelector.LENS_FACING_BACK,
        lensType = "wide",
        mirror = false,
        grid = false,
        autofocusMode = StreamConfig.AutofocusMode.CONTINUOUS,
        aspectRatio = "16:9",
        autofocusSpeed = StreamConfig.AutofocusSpeed.STANDARD,
        audioMeterEnabled = false,
        audioEnabled = true,
        audioSampleRate = 48000,
        audioBitrateBps = 192_000,
        audioCodec = "aac",
        noiseReduction = false,
        windFilter = false,
        syncObs = true,
    )

    @Test
    fun anUnchangedConfigNeverRestarts() {
        assertFalse(StreamConfig.requiresSessionRestart(base, base.copy()))
    }

    @Test
    fun everyGeometryFieldForcesARestart() {
        val geometryChanges = mapOf(
            "resolución" to base.copy(qualityLabel = "720p"),
            "composición" to base.copy(aspectRatio = "4:3"),
            "fps" to base.copy(fps = 60),
            "facing" to base.copy(lensFacing = CameraSelector.LENS_FACING_FRONT),
            "lente" to base.copy(lensType = "ultra-wide"),
        )
        for ((label, changed) in geometryChanges) {
            assertTrue(label, StreamConfig.requiresSessionRestart(base, changed))
        }
    }

    @Test
    fun liveTogglesKeepApplyingWithoutARestart() {
        // Each of these reconfigures nothing downstream of the camera — a
        // restart here would be a regression (the stream dropping because the
        // user toggled the grid).
        val liveChanges = mapOf(
            "grid" to base.copy(grid = true),
            "mirror" to base.copy(mirror = true),
            "audio meter" to base.copy(audioMeterEnabled = true),
            "estabilización" to base.copy(stabilization = false),
            "balance de blancos" to base.copy(whiteBalanceMode = 2),
            "enfoque" to base.copy(autofocusMode = StreamConfig.AutofocusMode.TAP),
            "reducción de ruido" to base.copy(noiseReduction = true),
            "sync OBS" to base.copy(syncObs = false),
        )
        for ((label, changed) in liveChanges) {
            assertFalse(label, StreamConfig.requiresSessionRestart(base, changed))
        }
    }

    @Test
    fun theAuditedReproIsCoveredExactly() {
        // The forensic repro that demonstrated F7: streaming at 16:9, change
        // composition to 4:3 in Settings, return without stopping. The camera
        // then cropped 1440x1080 into a 1920x1080 encoder — a 1.33x stretch
        // that only OBS showed. This exact transition must restart.
        assertTrue(
            StreamConfig.requiresSessionRestart(base, base.copy(aspectRatio = "4:3")),
        )
    }
}
