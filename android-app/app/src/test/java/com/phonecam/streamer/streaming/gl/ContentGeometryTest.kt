package com.phonecam.streamer.streaming.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distortion invariant for every composition and every rotation.
 *
 * The reference model is the forensic simulation that demonstrated the bug
 * (f1_renderer_sim): with the quad drawn full-surface, the on-screen
 * magnifications are mx = target/content per axis, remapped when the
 * rotation transposes the axes, and the visible distortion is mx/my. The
 * fix multiplies the quad by coverScale, so the corrected distortion must
 * be exactly 1 in every cell of the matrix that used to fail.
 */
class ContentGeometryTest {

    /** Content rects as the ViewPort delivers them for a 1080p FHD capture. */
    private val contentByRatio = mapOf(
        "16:9" to (1920 to 1080),
        "4:3" to (1440 to 1080),
        "1:1" to (1080 to 1080),
        "9:16" to (608 to 1080),
        "3:4" to (810 to 1080),
    )

    /** Encoder targets per composition — StreamConfig.outputSizeFor at 1080p. */
    private val targetByRatio = mapOf(
        "16:9" to (1920 to 1080),
        "4:3" to (1440 to 1080),
        "1:1" to (1080 to 1080),
        "9:16" to (1080 to 1920),
        "3:4" to (1080 to 1440),
    )

    /**
     * Displayed roundness after the corrective scale, derived independently
     * of ContentGeometry so the test cannot share a bug with the
     * implementation.
     *
     * Model (validated against the forensic simulation, which measured
     * 3.158 / 1.777 / 0.317 for the failing cells): a 90/270 rotation lays
     * the content's HEIGHT along the surface's horizontal axis, so the
     * horizontal magnification is tw/ch and the vertical one th/cw; without
     * rotation they are tw/cw and th/ch. The quad scale multiplies the
     * displayed extents directly, and an undistorted image has ratio 1.
     */
    private fun correctedDistortion(cw: Int, ch: Int, theta: Int, tw: Int, th: Int): Double {
        val scale = ContentGeometry.coverScale(cw, ch, theta, tw, th)
        val transposed = theta % 180 != 0
        val mh = if (transposed) tw.toDouble() / ch else tw.toDouble() / cw
        val mv = if (transposed) th.toDouble() / cw else th.toDouble() / ch
        return (scale[0] * mh) / (scale[1] * mv)
    }

    @Test
    fun theReferenceModelReproducesTheForensicMeasurements() {
        // Anchor the test's own model to the simulation's measured numbers
        // (scale factors forced to 1 = the old draw), so a future edit cannot
        // silently re-invert the axes.
        fun uncorrected(cw: Int, ch: Int, theta: Int, tw: Int, th: Int): Double {
            val transposed = theta % 180 != 0
            val mh = if (transposed) tw.toDouble() / ch else tw.toDouble() / cw
            val mv = if (transposed) th.toDouble() / cw else th.toDouble() / ch
            return mh / mv
        }
        assertEquals(3.158, uncorrected(1920, 1080, 90, 1920, 1080), 0.01)
        assertEquals(1.777, uncorrected(1440, 1080, 90, 1440, 1080), 0.01)
        assertEquals(0.317, uncorrected(608, 1080, 90, 1080, 1920), 0.01)
        assertEquals(1.333, uncorrected(1440, 1080, 0, 1920, 1080), 0.01)
    }

    @Test
    fun everyCompositionIsUndistortedAtEveryRotation() {
        for ((ratio, content) in contentByRatio) {
            val (cw, ch) = content
            val (tw, th) = targetByRatio.getValue(ratio)
            for (theta in listOf(0, 90, 180, 270)) {
                assertEquals(
                    "distorsión $ratio @ θ=$theta",
                    1.0,
                    correctedDistortion(cw, ch, theta, tw, th),
                    0.01,
                )
            }
        }
    }

    @Test
    fun matchingAspectsAreLeftBitIdentical() {
        // Every case that renders correctly today must stay untouched: both
        // factors exactly 1, no epsilon.
        for ((ratio, content) in contentByRatio) {
            val (cw, ch) = content
            val (tw, th) = targetByRatio.getValue(ratio)
            for (theta in listOf(0, 180)) {
                val s = ContentGeometry.coverScale(cw, ch, theta, tw, th)
                // Content and target aspects differ by sub-pixel rounding
                // (608/1080 vs 1080/1920); anything below half a percent is
                // the rounding, not a rescale.
                assertTrue("sx $ratio θ=$theta", s[0] in 0.995f..1.005f)
                assertTrue("sy $ratio θ=$theta", s[1] in 0.995f..1.005f)
            }
        }
    }

    @Test
    fun coverNeverLetterboxes() {
        // Cover semantics: no factor may shrink below 1 — a factor under 1
        // would paint black bars inside the encoded frame.
        for ((ratio, content) in contentByRatio) {
            val (cw, ch) = content
            val (tw, th) = targetByRatio.getValue(ratio)
            for (theta in listOf(0, 90, 180, 270)) {
                val s = ContentGeometry.coverScale(cw, ch, theta, tw, th)
                assertTrue("sx>=1 $ratio θ=$theta", s[0] >= 0.999f)
                assertTrue("sy>=1 $ratio θ=$theta", s[1] >= 0.999f)
                assertTrue(
                    "un eje anclado $ratio θ=$theta",
                    s[0] in 0.999f..1.005f || s[1] in 0.999f..1.005f,
                )
            }
        }
    }

    @Test
    fun theOldFailuresAreTheOnesBeingCorrected() {
        // Pin the magnitude of what the fix corrects, straight from the
        // forensic simulation: 16:9@90 stretched 3.16x, 4:3@90 1.78x. The
        // scale must be exactly that factor, on exactly one axis.
        val s169 = ContentGeometry.coverScale(1920, 1080, 90, 1920, 1080)
        assertEquals(3.16f, s169[1] / s169[0], 0.01f)

        val s43 = ContentGeometry.coverScale(1440, 1080, 90, 1440, 1080)
        assertEquals(1.78f, s43[1] / s43[0], 0.01f)

        // And the square stays identity at every rotation — the invariant the
        // device can falsify fastest if this model were wrong.
        for (theta in listOf(0, 90, 180, 270)) {
            val s = ContentGeometry.coverScale(1080, 1080, theta, 1080, 1080)
            assertEquals(1f, s[0], 0f)
            assertEquals(1f, s[1], 0f)
        }
    }

    // ---------- vertexRotation: what the texture matrix already did ----------

    @Test
    fun camera2VerticalNeedsNoVertexRotationBecauseTheTextureCarriesIt() {
        // The live regression this pins, in the user's words: "el giro de 90,
        // quítalo: ya estaba recto". Content needs 90 at a vertical hold; the
        // Camera2 texture matrix already applies the sensor's 90; the vertex
        // matrix must add nothing.
        assertEquals(0, ContentGeometry.vertexRotation(90, 90))
    }

    @Test
    fun camera2HorizontalSubtractsTheCarriedRotation() {
        // Content needs 0 at the landscape hold; the texture still carries 90;
        // the vertex matrix must undo it (270 ≡ −90).
        assertEquals(270, ContentGeometry.vertexRotation(0, 90))
    }

    @Test
    fun cameraXPassesThroughUntouched() {
        // CameraX's processed stream carries no texture rotation: the vertex
        // rotation IS the content rotation, exactly as before this field
        // existed.
        for (theta in listOf(0, 90, 180, 270)) {
            assertEquals(theta, ContentGeometry.vertexRotation(theta, 0))
        }
    }

    @Test
    fun vertexRotationIsAlwaysANormalisedQuarterTurn() {
        for (content in listOf(0, 90, 180, 270)) {
            for (tex in listOf(0, 90, 180, 270)) {
                val v = ContentGeometry.vertexRotation(content, tex)
                assertTrue("content=$content tex=$tex → $v", v in 0..270 && v % 90 == 0)
            }
        }
    }

    @Test
    fun coverStaysKeyedOnTheTotalRotationNotTheVertexOne() {
        // Camera2 at the landscape hold: total rotation 0 (content already
        // aligned), vertex rotation 270 (undoing the texture). The axes'
        // transposition on screen follows the TOTAL — so the scale must be
        // identity here, not the 3.16x transposed-cover zoom that keying on
        // the vertex value would produce (the exact "deformado en horizontal"
        // the hardware round measured).
        val s = ContentGeometry.coverScale(1920, 1080, 0, 1920, 1080)
        assertEquals(1f, s[0], 0f)
        assertEquals(1f, s[1], 0f)
    }

    // ---------- cropTexCoords ----------

    @Test
    fun aFullBufferCropIsTheClassicUnitQuad() {
        val tc = ContentGeometry.cropTexCoords(0, 0, 1920, 1080, 1920, 1080)
        assertEquals(
            listOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f),
            tc.toList(),
        )
    }

    @Test
    fun aCenteredCompositionCropSamplesExactlyThatRect() {
        // 4:3 centered inside FHD: x in [240, 1680].
        val tc = ContentGeometry.cropTexCoords(240, 0, 1680, 1080, 1920, 1080)
        assertEquals(0.125f, tc[0], 1e-6f)   // left u
        assertEquals(0.875f, tc[4], 1e-6f)   // right u
        assertEquals(0f, tc[1], 0f)          // top v
        assertEquals(1f, tc[9], 0f)          // bottom v
    }

    @Test
    fun anInvalidOrUndeclaredCropFallsBackToTheFullBuffer() {
        for (tc in listOf(
            ContentGeometry.cropTexCoords(0, 0, 0, 0, 0, 0),        // nothing declared
            ContentGeometry.cropTexCoords(10, 10, 10, 20, 100, 100), // zero width
            ContentGeometry.cropTexCoords(10, 10, 5, 5, 100, 100),   // inverted
        )) {
            assertEquals(0f, tc[0], 0f)
            assertEquals(1f, tc[4], 0f)
            assertEquals(1f, tc[13], 0f)
        }
    }

    @Test
    fun degenerateContentNeverProducesANonFiniteScale() {
        for (s in listOf(
            ContentGeometry.coverScale(0, 1080, 90, 1920, 1080),
            ContentGeometry.coverScale(1920, 0, 0, 1920, 1080),
            ContentGeometry.coverScale(1920, 1080, 90, 0, 0),
        )) {
            assertEquals(1f, s[0], 0f)
            assertEquals(1f, s[1], 0f)
        }
    }

    // ─── textureMatrixRotation: the classifier behind the 9:16/1:1/3:4 fix ───

    private fun matrix(m0: Float, m1: Float, m4: Float, m5: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = m0; m[1] = m1; m[4] = m4; m[5] = m5
        m[10] = 1f; m[15] = 1f
        return m
    }

    @Test
    fun theIdentityWithFlipMatrixCarriesNoRotation() {
        // SurfaceTexture's standard no-rotation transform: y flipped, x kept.
        assertEquals(0, ContentGeometry.textureMatrixRotation(matrix(1f, 0f, 0f, -1f), fallback = 7))
    }

    @Test
    fun theS23UltraHintMatrixCarries90Degrees() {
        // Measured on-device while fixing the sideways 9:16 sessions:
        // produced by BOTH the direct CameraX path and the Camera2 backend,
        // whose declared 90 had already been hardware-validated. Misreading
        // this as 270 drew the frame exactly 180 degrees off.
        assertEquals(90, ContentGeometry.textureMatrixRotation(matrix(0f, -1f, -1f, 0f), fallback = 7))
    }

    @Test
    fun cropScalingInTheMatrixDoesNotChangeTheClassification() {
        // Real matrices fold the sensor crop into the linear part, so entries
        // are rarely exactly +-1; only the surviving axis and its sign count.
        assertEquals(90, ContentGeometry.textureMatrixRotation(matrix(0f, -0.9f, -0.97f, 0f), fallback = 7))
        assertEquals(0, ContentGeometry.textureMatrixRotation(matrix(0.94f, 0f, 0f, -0.88f), fallback = 7))
    }

    @Test
    fun theRemainingTwoQuadrantsClassifySymmetrically() {
        assertEquals(180, ContentGeometry.textureMatrixRotation(matrix(-1f, 0f, 0f, 1f), fallback = 7))
        assertEquals(270, ContentGeometry.textureMatrixRotation(matrix(0f, 1f, 1f, 0f), fallback = 7))
    }

    @Test
    fun anAllZeroMatrixMeansNoFrameYetAndYieldsTheFallback() {
        assertEquals(7, ContentGeometry.textureMatrixRotation(FloatArray(16), fallback = 7))
    }
}
