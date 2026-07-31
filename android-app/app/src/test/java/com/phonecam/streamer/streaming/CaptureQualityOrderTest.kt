package com.phonecam.streamer.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two bucket-selection rules: the one that ships (short edge) and M2's
 * covering rule, which is kept as diagnostic only.
 *
 * M2 was implemented, measured on device across all five compositions, and
 * REFUTED: the crossed composition×hold pixel deficit it targeted (predicted
 * 44-68%) measured 0% — CameraX already delivers exact-size buffers. Its
 * order would have escalated those cases to a 4K sensor mode for no gain, so
 * shipping selection stayed on the short-edge rule. Both are pinned here:
 * the shipping one so it cannot drift, the covering one so the diagnostic
 * stays trustworthy on other devices.
 *
 * Indices into StreamingVideoOutput.BUCKET_SIZES: 0=SD 1=HD 2=FHD 3=UHD.
 * (Quality's own constants can't initialize on the JVM, which is why the
 * order is pure indices and the device path maps them at use time.)
 */
class CaptureQualityOrderTest {

    private val SD = 0
    private val HD = 1
    private val FHD = 2
    private val UHD = 3

    @Test
    fun aStandardLandscapeNeedStaysOnFhd() {
        // 16:9 held landscape: no change from the old behavior, no wasted
        // sensor bandwidth.
        assertEquals(
            listOf(FHD, UHD, HD, SD),
            StreamingVideoOutput.captureOrderIndices(1920, 1080),
        )
    }

    @Test
    fun aCrossedNeedEscalatesToUhd() {
        // 16:9 held vertical, or 9:16 held landscape (both need 1080x1920):
        // only UHD's 2160-tall frame covers a 1920-tall crop.
        assertEquals(UHD, StreamingVideoOutput.captureOrderIndices(1080, 1920).first())
    }

    @Test
    fun nineSixteenHeldVerticalStaysOnFhd() {
        // The happy vertical case: the crop transposes to exactly 1920x1080.
        assertEquals(FHD, StreamingVideoOutput.captureOrderIndices(1920, 1080).first())
    }

    @Test
    fun squareNeedsOnlyFhd() {
        // 1:1 at 1080p needs 1080x1080 in either parity.
        assertEquals(FHD, StreamingVideoOutput.captureOrderIndices(1080, 1080).first())
    }

    @Test
    fun aNeedBeyondUhdDegradesToTheLargestBucket() {
        // Crossed 4K: nothing covers 2160x3840; UHD (largest) leads the
        // fallback instead of streaming nothing.
        assertEquals(
            listOf(UHD, FHD, HD, SD),
            StreamingVideoOutput.captureOrderIndices(2160, 3840),
        )
    }

    @Test
    fun everyOrderContainsAllFourBucketsExactlyOnce() {
        for ((w, h) in listOf(720 to 480, 1920 to 1080, 1080 to 1920, 2160 to 3840, 1080 to 1080)) {
            val order = StreamingVideoOutput.captureOrderIndices(w, h)
            assertEquals("necesidad ${w}x$h", 4, order.size)
            assertEquals("sin duplicados ${w}x$h", 4, order.toSet().size)
        }
    }

    // ---------- la regla que SÍ se embarca ----------

    @Test
    fun theShippingRuleIsTheShortEdgeOne() {
        // 1080p en cualquier composición -> FHD primero. Ningún caso
        // escala a UHD por composición: el comportamiento de captura no
        // cambió con M2, que es justamente lo acordado.
        assertEquals(listOf(FHD, HD, SD, UHD), StreamingVideoOutput.shortEdgeOrderIndices(1080))
        assertEquals(listOf(UHD, FHD, HD, SD), StreamingVideoOutput.shortEdgeOrderIndices(1440))
        assertEquals(listOf(HD, SD, FHD, UHD), StreamingVideoOutput.shortEdgeOrderIndices(720))
        assertEquals(listOf(SD, HD, FHD, UHD), StreamingVideoOutput.shortEdgeOrderIndices(480))
    }

    // ---------- captureOrder: la regla embarcada, escalada solo donde duele ----------

    @Test
    fun onlyTheMeasuredDeficitCellsEscalate() {
        // La matriz medida en dispositivo (agarres confirmados en log).
        // needW/needH salen de StreamConfig.neededCaptureFor.
        val cases = listOf(
            Triple("16:9 apaisado", 1920 to 1080, FHD),
            Triple("9:16 vertical", 1920 to 1080, FHD),   // need transpuesto
            Triple("1:1 cualquiera", 1080 to 1080, FHD),
            Triple("3:4 vertical", 1440 to 1080, FHD),
            Triple("9:16 apaisado", 1080 to 1920, UHD),   // 68.3% de déficit medido
            Triple("3:4 apaisado", 1080 to 1440, UHD),    // misma geometría
        )
        for ((label, need, expected) in cases) {
            assertEquals(
                label,
                expected,
                StreamingVideoOutput.captureOrder(need.first, need.second).first(),
            )
        }
    }

    @Test
    fun everyZeroDeficitCellKeepsTodaysExactOrder() {
        // Sin cambio de comportamiento donde no hay nada que ganar: la lista
        // completa, no solo el primer bucket.
        for (need in listOf(1920 to 1080, 1080 to 1080, 1440 to 1080)) {
            assertEquals(
                "need=${need.first}x${need.second}",
                StreamingVideoOutput.shortEdgeOrderIndices(minOf(need.first, need.second)),
                StreamingVideoOutput.captureOrder(need.first, need.second),
            )
        }
    }

    @Test
    fun anEscalatedCellStillCoversWhatItNeeds() {
        val order = StreamingVideoOutput.captureOrder(1080, 1920)
        val pick = StreamingVideoOutput.BUCKET_SIZES[order.first()]
        assertEquals("cubre el ancho", true, pick.first >= 1080)
        assertEquals("cubre el alto", true, pick.second >= 1920)
    }

    @Test
    fun fourteenFortyStillPrefersUhdAsAlways() {
        // 1440p no tiene bucket propio: la regla de borde corto ya prefería
        // UHD, y captureOrder no debe alterarlo.
        assertEquals(UHD, StreamingVideoOutput.captureOrder(2560, 1440).first())
    }

    @Test
    fun theFullM2MatrixPicksTheExpectedBucket() {
        // 5 composiciones x 2 paridades, encoder 1080p — la tabla de diseño.
        val cases = listOf(
            // (encoderW, encoderH, crossed?, bucket esperado)
            arrayOf(1920, 1080, false, FHD),  // 16:9 apaisado
            arrayOf(1920, 1080, true, UHD),   // 16:9 vertical
            arrayOf(1080, 1920, false, UHD),  // 9:16 apaisado
            arrayOf(1080, 1920, true, FHD),   // 9:16 vertical
            arrayOf(1080, 1080, false, FHD),  // 1:1 ambos
            arrayOf(1080, 1080, true, FHD),
            arrayOf(1440, 1080, false, FHD),  // 4:3 apaisado
            arrayOf(1440, 1080, true, UHD),   // 4:3 vertical
            arrayOf(1080, 1440, false, UHD),  // 3:4 apaisado
            arrayOf(1080, 1440, true, FHD),   // 3:4 vertical
        )
        for ((ew, eh, crossed, expected) in cases) {
            val needW = if (crossed as Boolean) eh as Int else ew as Int
            val needH = if (crossed) ew as Int else eh as Int
            assertEquals(
                "encoder=${ew}x$eh crossed=$crossed",
                expected,
                StreamingVideoOutput.captureOrderIndices(needW, needH).first(),
            )
        }
    }
}
