package com.phonecam.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition geometry: what shape a given resolution preset plus
 * composition ratio actually streams.
 *
 * This is the arithmetic behind a bug that reached OBS looking like an OBS
 * problem. Composition really crops the camera (the ViewPort in MainActivity),
 * but the encoder was sized from the resolution preset alone, so a 4:3
 * composition cropped to 1440x1080 and was then stretched onto a 1920x1080
 * encoder surface — EncoderSurfaceRenderer draws a fixed full-surface quad and
 * corrects nothing. The stream went out 33% too wide and told the PC it was
 * 1920x1080, so OBS matched its canvas perfectly to an already-deformed frame.
 */
class StreamConfigCompositionTest {

    // ---------- the sizes the feature request named ----------

    @Test
    fun sixteenByNineAtTenEightyIsUnchanged() {
        assertEquals(1920 to 1080, StreamConfig.outputSizeFor("1080p", "16:9"))
    }

    @Test
    fun fourByThreeAtTenEightyIsFourteenFortyWide() {
        // The case that was stretched: the camera really delivers this, the
        // encoder used to be built at 1920x1080 anyway.
        assertEquals(1440 to 1080, StreamConfig.outputSizeFor("1080p", "4:3"))
    }

    @Test
    fun squareAtTenEightyIsSquare() {
        assertEquals(1080 to 1080, StreamConfig.outputSizeFor("1080p", "1:1"))
    }

    // ---------- the short edge is what a preset name means ----------

    @Test
    fun verticalKeepsTheShortEdgeAndGrowsTheLongOne() {
        // "1080p vertical" means 1080 across, not a 608-pixel-wide sliver —
        // 1080 is the short edge in every one of these.
        assertEquals(1080 to 1920, StreamConfig.outputSizeFor("1080p", "9:16"))
        assertEquals(1080 to 1440, StreamConfig.outputSizeFor("1080p", "3:4"))
    }

    @Test
    fun everyRatioAtOnePresetSharesTheSameShortEdge() {
        for (ratio in listOf("16:9", "4:3", "1:1", "9:16", "3:4")) {
            val (w, h) = StreamConfig.outputSizeFor("720p", ratio)
            assertEquals("short edge for $ratio", 720, minOf(w, h))
        }
    }

    @Test
    fun theRatioIsActuallyHonoured() {
        for ((ratio, expected) in listOf(
            "16:9" to 16.0 / 9, "4:3" to 4.0 / 3, "1:1" to 1.0,
            "9:16" to 9.0 / 16, "3:4" to 3.0 / 4,
        )) {
            val (w, h) = StreamConfig.outputSizeFor("2160p", ratio)
            assertEquals("aspect for $ratio", expected, w.toDouble() / h, 0.01)
        }
    }

    // ---------- custom "WxH" labels ----------

    @Test
    fun aCustomSizeThatAlreadyMatchesItsRatioSurvivesIntact() {
        // Someone who typed 1440x1080 and picked 4:3 asked for exactly that,
        // and must get exactly that back.
        assertEquals(1440 to 1080, StreamConfig.outputSizeFor("1440x1080", "4:3"))
        assertEquals(1600 to 900, StreamConfig.outputSizeFor("1600x900", "16:9"))
        assertEquals(1080 to 1920, StreamConfig.outputSizeFor("1080x1920", "9:16"))
    }

    @Test
    fun compositionReshapesACustomSizeThatContradictsIt() {
        // Composition decides the shape, resolution decides the size. The
        // alternative is streaming a 4:3 crop inside a 16:9 frame, which is
        // the stretch this whole change exists to remove.
        assertEquals(1440 to 1080, StreamConfig.outputSizeFor("1920x1080", "4:3"))
    }

    // ---------- encodability ----------

    @Test
    fun everyDimensionIsEvenBecauseFourTwoZeroCannotEncodeOddOnes() {
        val labels = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p", "4320p")
        val ratios = listOf("16:9", "4:3", "1:1", "9:16", "3:4")
        for (label in labels) {
            for (ratio in ratios) {
                val (w, h) = StreamConfig.outputSizeFor(label, ratio)
                assertEquals("$label $ratio width parity", 0, w % 2)
                assertEquals("$label $ratio height parity", 0, h % 2)
            }
        }
    }

    // ---------- fitWithin: the ceiling must not reshape ----------

    @Test
    fun somethingThatAlreadyFitsIsReturnedUntouched() {
        assertEquals(1440 to 1080, StreamConfig.fitWithin(1440, 1080, 1920, 1080))
    }

    @Test
    fun aVerticalFrameIsScaledDownRatherThanSquashedIntoASquare() {
        // The regression this pins: clamping each axis on its own turned a
        // free-tier 1080x1920 vertical stream into 1080x1080 — a square that
        // was neither what the user picked nor any valid composition.
        val (w, h) = StreamConfig.fitWithin(1080, 1920, 1920, 1080)
        assertEquals(9.0 / 16, w.toDouble() / h, 0.01)
        assertEquals(1080, h)
    }

    @Test
    fun scalingDownPreservesTheRatioForEveryComposition() {
        for (ratio in listOf("16:9", "4:3", "1:1", "9:16", "3:4")) {
            val (w, h) = StreamConfig.outputSizeFor("2160p", ratio)
            val (fw, fh) = StreamConfig.fitWithin(w, h, 1920, 1080)
            assertEquals("ratio held for $ratio", w.toDouble() / h, fw.toDouble() / fh, 0.01)
            assertEquals("$ratio width parity", 0, fw % 2)
            assertEquals("$ratio height parity", 0, fh % 2)
        }
    }

    // ---------- fitPixelBudget: the tier ceiling is aspect-neutral ----------

    @Test
    fun sixteenNineBehavesExactlyAsTheOldBox() {
        // The migration invariant: for landscape requests the pixel budget is
        // bit-identical to the old 1920x1080 box, so no existing 16:9 user
        // sees any change.
        val budget = 1920L * 1080
        assertEquals(1920 to 1080, StreamConfig.fitPixelBudget(3840, 2160, budget))
        assertEquals(1920 to 1080, StreamConfig.fitPixelBudget(1920, 1080, budget))
        assertEquals(1280 to 720, StreamConfig.fitPixelBudget(1280, 720, budget))
    }

    @Test
    fun verticalNoLongerPaysThePixelTax() {
        // THE fix for "9:16 se ve mucho más pixelado": the old box clamped
        // 1080x1920 to 608x1080 (68% fewer pixels than the 16:9 sibling).
        // Same budget, full size now.
        val budget = 1920L * 1080
        assertEquals(1080 to 1920, StreamConfig.fitPixelBudget(1080, 1920, budget))
        assertEquals(1080 to 1080, StreamConfig.fitPixelBudget(1080, 1080, budget))
        assertEquals(1440 to 1080, StreamConfig.fitPixelBudget(1440, 1080, budget))
        assertEquals(1080 to 1440, StreamConfig.fitPixelBudget(1080, 1440, budget))
    }

    @Test
    fun overBudgetVerticalScalesPreservingItsShape() {
        // 1440p vertical on the free budget: shrunk, not squashed.
        val budget = 1920L * 1080
        val (w, h) = StreamConfig.fitPixelBudget(1440, 2560, budget)
        assertEquals(9.0 / 16, w.toDouble() / h, 0.02)
        assertTrue("dentro de presupuesto", w.toLong() * h <= (budget * 1.01).toLong())
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun effectiveTargetGivesVerticalTheFullTierBudget() {
        // End to end through the real tier table: a free-tier 9:16 1080p
        // session must encode at 1080x1920 — the product rule — where the box
        // ceiling produced 608x1080.
        val free = com.phonecam.streamer.rewards.StreamProfile(
            quality = "1080p60", watermark = true, adsEnabled = true,
            premiumActive = false, balanceSeconds = 0.0,
        )
        val cfg = baseConfig(qualityLabel = "1080p", aspectRatio = "9:16")
        val (w, h, fps) = com.phonecam.streamer.streaming.CameraStreamer.effectiveTarget(cfg, free)
        assertEquals(1080, w)
        assertEquals(1920, h)
        assertEquals(30, fps)

        // And the landscape sibling is untouched by the migration.
        val cfg169 = baseConfig(qualityLabel = "1080p", aspectRatio = "16:9")
        val (w2, h2, _) = com.phonecam.streamer.streaming.CameraStreamer.effectiveTarget(cfg169, free)
        assertEquals(1920, w2)
        assertEquals(1080, h2)
    }

    private fun baseConfig(qualityLabel: String, aspectRatio: String) = StreamConfig(
        qualityLabel = qualityLabel,
        fps = 30,
        videoBitrateBps = 20_000_000,
        hdr = false,
        stabilization = true,
        whiteBalanceMode = 1,
        lensFacing = 1,
        lensType = "wide",
        mirror = false,
        grid = false,
        autofocusMode = StreamConfig.AutofocusMode.CONTINUOUS,
        aspectRatio = aspectRatio,
        autofocusSpeed = StreamConfig.AutofocusSpeed.STANDARD,
        audioMeterEnabled = false,
        audioEnabled = false,
        audioSampleRate = 48000,
        audioBitrateBps = 192_000,
        audioCodec = "aac",
        noiseReduction = false,
        windFilter = false,
        syncObs = true,
    )

    // ---------- M2: neededCaptureFor — la captura que evita el upscale ----------

    @Test
    fun alignedHoldsKeepTheCompositionShape() {
        // Agarre apaisado (ROTATION_90/270): el crop en espacio de sensor tiene
        // la forma de la composición.
        assertEquals(1920 to 1080, StreamConfig.neededCaptureFor(1920, 1080, android.view.Surface.ROTATION_90))
        assertEquals(1080 to 1920, StreamConfig.neededCaptureFor(1080, 1920, android.view.Surface.ROTATION_270))
        assertEquals(1440 to 1080, StreamConfig.neededCaptureFor(1440, 1080, android.view.Surface.ROTATION_90))
    }

    @Test
    fun crossedHoldsTransposeTheNeed() {
        // Agarre vertical (ROTATION_0/180): el crop se transpone en el sensor.
        // 16:9 en vertical necesita 1080 de ancho x 1920 de alto -> solo UHD
        // lo cubre; 9:16 en vertical cabe exacto en FHD.
        assertEquals(1080 to 1920, StreamConfig.neededCaptureFor(1920, 1080, android.view.Surface.ROTATION_0))
        assertEquals(1920 to 1080, StreamConfig.neededCaptureFor(1080, 1920, android.view.Surface.ROTATION_0))
        assertEquals(1080 to 1440, StreamConfig.neededCaptureFor(1440, 1080, android.view.Surface.ROTATION_180))
        assertEquals(1080 to 1080, StreamConfig.neededCaptureFor(1080, 1080, android.view.Surface.ROTATION_0))
    }

    @Test
    fun theFullMatrixNeverUnderProvisions() {
        // Para las 5 composiciones x 2 paridades: el crop rotado al encoder
        // debe tener al menos los píxeles del encoder (déficit 0 por diseño).
        val holds = listOf(android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_90)
        for (ratio in listOf("16:9", "9:16", "1:1", "4:3", "3:4")) {
            val (ew, eh) = StreamConfig.outputSizeFor("1080p", ratio)
            for (hold in holds) {
                val (nw, nh) = StreamConfig.neededCaptureFor(ew, eh, hold)
                val crossed = hold == android.view.Surface.ROTATION_0
                val shownW = if (crossed) nh else nw
                val shownH = if (crossed) nw else nh
                assertTrue("$ratio hold=$hold", shownW >= ew && shownH >= eh)
            }
        }
    }

    @Test
    fun theCeilingIsNeverExceeded() {
        for (ratio in listOf("16:9", "4:3", "1:1", "9:16", "3:4")) {
            val (w, h) = StreamConfig.outputSizeFor("4320p", ratio)
            val (fw, fh) = StreamConfig.fitWithin(w, h, 1920, 1080)
            assertEquals("$ratio fits horizontally", true, fw <= 1920)
            assertEquals("$ratio fits vertically", true, fh <= 1080)
        }
    }
}
