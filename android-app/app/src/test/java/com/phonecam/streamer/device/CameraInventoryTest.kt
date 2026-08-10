package com.phonecam.streamer.device

import com.phonecam.streamer.device.CameraInventory.CameraDescriptor
import com.phonecam.streamer.device.CameraInventory.DiscoverySource
import com.phonecam.streamer.device.CameraInventory.LensRole
import com.phonecam.streamer.device.CameraInventory.Confidence
import com.phonecam.streamer.device.CameraInventory.Openability
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lens identification against real device numbers.
 *
 * Two grades of fixture, and the difference is stated rather than blurred:
 *
 * - **Measured** — read off the hardware. The Redmi Note 11S came from
 *   `dumpsys media.camera` on the device; the Galaxy S23 Ultra from this
 *   app's own diagnostics export. These are the ones that count.
 * - **Spec-derived** — built from published figures for a shape this project
 *   has no unit of (Pixel). Marked as such at each use, because a fixture
 *   nobody measured can encode the same mistake as the code it tests: an
 *   earlier guess at the S23 Ultra's ultra-wide sensor produced 0.46x for a
 *   lens that really is 0.6x, and only the measured numbers caught it.
 *
 * Nothing here touches an Android framework class, which is what makes the
 * classifier testable at all. That is a property of the design, not luck:
 * [CameraDescriptor] carries its resolution as two `Int`s precisely because
 * `android.util.Size` is a stub on the JVM — it constructs happily and then
 * throws from every getter, which is how these tests first failed.
 */
class CameraInventoryTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun camera(
        id: String,
        facing: String = "back",
        focal: Float? = null,
        sensorW: Float? = null,
        sensorH: Float? = null,
        megapixels: Float = 12f,
        maxW: Int = 4000,
        maxH: Int = 3000,
        diopters: Float? = null,
        digitalZoom: Float? = 10f,
        depthOutput: Boolean = false,
        producesImages: Boolean = true,
        source: DiscoverySource = DiscoverySource.ID_LIST,
        extraSources: Set<DiscoverySource> = emptySet(),
        parent: String? = null,
    ) = CameraDescriptor(
        id = id,
        physicalParentId = parent,
        sources = setOf(source) + extraSources,
        facing = facing,
        maxWidth = maxW,
        maxHeight = maxH,
        megapixels = megapixels,
        sensorWidthMm = sensorW,
        sensorHeightMm = sensorH,
        sensorDiagonalMm = if (sensorW != null && sensorH != null) kotlin.math.hypot(sensorW, sensorH) else null,
        focalLengthMm = focal,
        equivalentFocalMm = CameraInventory.equivalentFocalMm(focal, sensorW, sensorH),
        apertureF = 1.8f,
        minFocusDistanceDiopters = diopters,
        maxDigitalZoom = digitalZoom,
        capabilities = listOf("BACKWARD_COMPATIBLE"),
        hasDepthOutput = depthOutput,
        producesImages = producesImages,
    )

    /** The real pipeline order: collapse, classify, then infer openability. */
    private fun analyse(cameras: List<CameraDescriptor>): Map<String, CameraDescriptor> =
        analysed(cameras).associateBy { it.id }

    private fun analysed(cameras: List<CameraDescriptor>): List<CameraDescriptor> {
        val collapsed = CameraInventory.collapseDuplicates(cameras)
        CameraInventory.classify(collapsed)
        CameraInventory.inferOpenability(collapsed)
        return collapsed
    }

    // ── MEASURED: Redmi Note 11S (Xiaomi 2201117SY), via dumpsys ──
    private fun redmiNote11s() = listOf(
        camera("0", focal = 5.89f, sensorW = 8.25f, sensorH = 7.07f, megapixels = 12f),
        camera("1", facing = "front", focal = 3.49f, sensorW = 4.55f, sensorH = 3.52f, megapixels = 4f),
        camera("2", focal = 1.65f, sensorW = 3.58f, sensorH = 2.99f, megapixels = 8f,
            maxW = 3264, maxH = 2448, source = DiscoverySource.HIDDEN_ID_SCAN),
        camera("3", focal = 1.915f, sensorW = 2.81f, sensorH = 2.11f, megapixels = 1.92f,
            maxW = 1600, maxH = 1200, source = DiscoverySource.HIDDEN_ID_SCAN),
        camera("4", focal = 2.00f, sensorW = 1.61f, sensorH = 1.21f, megapixels = 1.92f,
            maxW = 1600, maxH = 1200, source = DiscoverySource.HIDDEN_ID_SCAN),
    )

    // ── MEASURED: Galaxy S23 Ultra, via this app's diagnostics export ──
    // main 22.3mm, ultra-wide 13.6mm, 3x tele 66.9mm, periscope 230.4mm.
    // Ids 0 and 5 are the same sensor behind two logical ids.
    private fun galaxyS23Ultra() = listOf(
        camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f, megapixels = 12.5f),
        camera("1", facing = "front", focal = 3.3f, sensorW = 4.48f, sensorH = 3.36f, megapixels = 12f),
        // Measured: the same selfie camera exposed a second time as a
        // cropped mode — 30.1mm against 25.5mm, 8.6MP against 12MP.
        camera("3", facing = "front", focal = 3.3f, sensorW = 3.799f, sensorH = 2.849f, megapixels = 8.6f),
        camera("2", focal = 2.2f, sensorW = 5.60f, sensorH = 4.20f, megapixels = 12f,
            source = DiscoverySource.PHYSICAL_SUB_CAMERA, parent = "0"),
        camera("5", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f, megapixels = 12.5f,
            source = DiscoverySource.PHYSICAL_SUB_CAMERA, parent = "0"),
        camera("6", focal = 7.9f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 10f,
            source = DiscoverySource.PHYSICAL_SUB_CAMERA, parent = "0"),
        camera("7", focal = 27.2f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 10f,
            source = DiscoverySource.PHYSICAL_SUB_CAMERA, parent = "0"),
    )

    // ── SPEC-DERIVED: Pixel 8 Pro shape. Not measured; no unit here. ──
    private fun pixelProShape() = listOf(
        camera("0", focal = 6.9f, sensorW = 9.8f, sensorH = 7.3f, megapixels = 12.5f),
        camera("1", facing = "front", focal = 2.7f, sensorW = 3.6f, sensorH = 2.7f, megapixels = 10.5f),
        camera("2", focal = 2.0f, sensorW = 5.6f, sensorH = 4.2f, megapixels = 12f),
        camera("3", focal = 19.0f, sensorW = 5.6f, sensorH = 4.2f, megapixels = 12f),
    )

    // ── geometry ────────────────────────────────────────────────────

    @Test
    fun equivalentFocalNormalisesForSensorSize() {
        assertEquals(23.5f, CameraInventory.equivalentFocalMm(5.89f, 8.25f, 7.07f)!!, 0.5f)
        assertEquals(15.3f, CameraInventory.equivalentFocalMm(1.65f, 3.58f, 2.99f)!!, 0.5f)
        assertEquals(23.6f, CameraInventory.equivalentFocalMm(1.915f, 2.81f, 2.11f)!!, 0.5f)
        assertEquals(43.0f, CameraInventory.equivalentFocalMm(2.00f, 1.61f, 1.21f)!!, 0.5f)
        assertEquals(230.4f, CameraInventory.equivalentFocalMm(27.2f, 4.09f, 3.06f)!!, 1f)
    }

    @Test
    fun missingSensorSizeYieldsNoEquivalentRatherThanAGuess() {
        assertNull(CameraInventory.equivalentFocalMm(1.65f, null, 2.99f))
        assertNull(CameraInventory.equivalentFocalMm(null, 3.58f, 2.99f))
        assertNull(CameraInventory.equivalentFocalMm(1.65f, 0f, 0f))
    }

    @Test
    fun theBandsCoverEveryFocalLengthWithNoGap() {
        assertEquals(LensRole.ULTRA_WIDE, CameraInventory.roleForEquivalent(13f))
        assertEquals(LensRole.ULTRA_WIDE, CameraInventory.roleForEquivalent(18f))
        assertEquals(LensRole.WIDE, CameraInventory.roleForEquivalent(24f))
        assertEquals(LensRole.WIDE, CameraInventory.roleForEquivalent(35f))
        // 35-50 was undefined in the brief. A 48mm "2x" is one of the
        // commonest tele designs shipping, and the Redmi's depth helper sits
        // at 43mm — an unassigned band drops real hardware into UNKNOWN.
        assertEquals(LensRole.TELEPHOTO, CameraInventory.roleForEquivalent(43f))
        assertEquals(LensRole.TELEPHOTO, CameraInventory.roleForEquivalent(48f))
        assertEquals(LensRole.TELEPHOTO, CameraInventory.roleForEquivalent(119f))
        assertEquals(LensRole.SUPER_TELEPHOTO, CameraInventory.roleForEquivalent(230f))
        // Nothing may ever come back UNKNOWN from a real focal length.
        var mm = 1f
        while (mm < 400f) {
            assertTrue("$mm mm classified", CameraInventory.roleForEquivalent(mm) != LensRole.UNKNOWN)
            mm += 0.5f
        }
    }

    // ── MEASURED device: Redmi Note 11S ──────────────────────────────

    @Test
    fun redmiIsIdentifiedLensByLens() {
        val cams = analyse(redmiNote11s())
        assertEquals(LensRole.WIDE, cams.getValue("0").role)
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
        assertEquals(LensRole.ULTRA_WIDE, cams.getValue("2").role)
        assertEquals(LensRole.MACRO, cams.getValue("3").role)
        assertEquals(LensRole.DEPTH, cams.getValue("4").role)
        assertEquals(0.6f, cams.getValue("2").zoomRatio!!, 0.001f)
    }

    @Test
    fun macroIsToldFromDepthByFramingWhenNeitherIsDeclared() {
        // Neither declares DEPTH_OUTPUT and neither publishes a focus
        // distance, so the only signal left is what they frame: a macro is
        // built to see what the main lens sees, up close; the depth helper
        // looks at 43mm against the main's 23.5mm.
        val cams = analyse(redmiNote11s())
        assertTrue(cams.getValue("3").evidence.contains("frames_like_main_lens"))
        assertTrue(cams.getValue("4").evidence.contains("no_photographic_use"))
    }

    @Test
    fun hiddenScanIsRecordedSoOemHidingIsDistinguishable() {
        val cams = analyse(redmiNote11s())
        assertTrue(DiscoverySource.HIDDEN_ID_SCAN in cams.getValue("2").sources)
        assertTrue(DiscoverySource.ID_LIST in cams.getValue("0").sources)
        // Recovered ids are not claimed openable without proof.
        assertEquals(Openability.UNKNOWN, cams.getValue("2").openability)
        assertEquals(Openability.OPENABLE, cams.getValue("0").openability)
    }

    // ── MEASURED device: Galaxy S23 Ultra ────────────────────────────

    @Test
    fun galaxyUltraGetsEveryLensAndItsPeriscope() {
        val cams = analyse(galaxyS23Ultra())
        assertEquals(LensRole.WIDE, cams.getValue("0").role)
        assertEquals(LensRole.ULTRA_WIDE, cams.getValue("2").role)
        assertEquals(LensRole.TELEPHOTO, cams.getValue("6").role)
        assertEquals(LensRole.PERISCOPE, cams.getValue("7").role)
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
    }

    @Test
    fun theDuplicateLogicalCameraCollapsesIntoOneEntry() {
        // Ids 0 and 5 report the same focal length on the same sensor: one
        // physical camera behind two logical ids. Measured, not invented.
        // The user must be offered it once, and nothing may be lost.
        val cams = analysed(galaxyS23Ultra())
        assertNull("the duplicate id is gone", cams.firstOrNull { it.id == "5" })
        val primary = cams.first { it.id == "0" }
        assertEquals(listOf("5"), primary.duplicateIds)
        // The survivor is the one the app can actually bind to.
        assertTrue(DiscoverySource.ID_LIST in primary.sources)
    }

    @Test
    fun aCameraFoundByTwoRoutesKeepsBothSources() {
        // Measured on the S23 Ultra: id 2 arrives from the id list *and* as a
        // physical sub-camera. Both facts are true and both are worth having.
        val cams = analysed(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("2", focal = 2.2f, sensorW = 5.6f, sensorH = 4.2f,
                    source = DiscoverySource.ID_LIST,
                    extraSources = setOf(DiscoverySource.PHYSICAL_SUB_CAMERA), parent = "0"),
            ),
        )
        val ultraWide = cams.first { it.id == "2" }
        assertEquals(
            setOf(DiscoverySource.ID_LIST, DiscoverySource.PHYSICAL_SUB_CAMERA),
            ultraWide.sources,
        )
        // Present in the id list means it can be opened directly.
        assertEquals(Openability.OPENABLE, ultraWide.openability)
    }

    @Test
    fun duplicatesAreExcludedFromZoomAndFromTheUsableList() {
        val cams = analysed(galaxyS23Ultra())
        val zoom = CameraInventory.computeZoom(cams)
        assertEquals(0.6f, zoom.opticalMin, 0.05f)
        assertEquals(10f, zoom.opticalMax, 0.5f)
        assertEquals(10f, zoom.digitalMax, 0.5f)
        // 10x optical times the 10x crop that lens allows: the "100x" a spec
        // sheet quotes.
        assertEquals(100f, zoom.hybridMax, 5f)
        assertTrue("no duplicate survives", cams.none { it.duplicateIds.isNotEmpty() && it.id == "5" })
    }

    @Test
    fun physicalSubCamerasAreNotClaimedOpenable() {
        // openCamera takes a logical id; a sub-camera is reached by binding
        // its parent and calling setPhysicalCameraId. Saying otherwise would
        // send the UI to a chip that cannot bind.
        val cams = analyse(galaxyS23Ultra())
        assertEquals(Openability.NOT_OPENABLE, cams.getValue("6").openability)
        assertTrue(cams.getValue("6").openabilityReason.contains("setPhysicalCameraId"))
        assertEquals("0", cams.getValue("6").physicalParentId)
    }

    // ── SPEC-DERIVED: Pixel-shaped device (not measured) ─────────────

    @Test
    fun aPixelShapedDeviceGetsUltraWideWideAndTele() {
        val cams = analyse(pixelProShape())
        assertEquals(LensRole.WIDE, cams.getValue("0").role)
        assertEquals(LensRole.ULTRA_WIDE, cams.getValue("2").role)
        // ~117mm equivalent: a long tele, but at 5x it is folded optics.
        assertTrue(cams.getValue("3").role in setOf(LensRole.TELEPHOTO, LensRole.PERISCOPE))
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
    }

    // ── periscope ────────────────────────────────────────────────────

    @Test
    fun aLongLensWithRealReachIsAPeriscopeNotJustASuperTele() {
        val cams = analyse(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("9", focal = 27.2f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 10f),
            ),
        )
        assertEquals(LensRole.PERISCOPE, cams.getValue("9").role)
        assertTrue(cams.getValue("9").evidence.any { it.startsWith("zoom_ratio=") })
    }

    @Test
    fun aLongEquivalentWithoutRealReachIsNotCalledAPeriscope() {
        // A long equivalent on a tiny sensor beside an equally long main is
        // not a folded lens; the zoom ratio is what separates the two.
        val cams = analyse(
            listOf(
                camera("0", focal = 20f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 12f),
                camera("9", focal = 21f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 10f),
            ),
        )
        assertTrue(cams.getValue("9").role != LensRole.PERISCOPE)
    }

    // ── depth and macro ──────────────────────────────────────────────

    @Test
    fun aDeclaredDepthOutputIsBelievedImmediately() {
        val cams = analyse(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("8", focal = 2.0f, sensorW = 1.6f, sensorH = 1.2f, megapixels = 2f, depthOutput = true),
            ),
        )
        assertEquals(LensRole.DEPTH, cams.getValue("8").role)
        assertTrue(cams.getValue("8").evidence.contains("capability=DEPTH_OUTPUT"))
    }

    @Test
    fun aDeclaredCloseFocusBeatsTheFramingHeuristic() {
        val cams = analyse(
            listOf(
                camera("0", focal = 5.89f, sensorW = 8.25f, sensorH = 7.07f),
                camera("3", focal = 2.0f, sensorW = 1.61f, sensorH = 1.21f, megapixels = 2f, diopters = 25f),
            ),
        )
        assertEquals(LensRole.MACRO, cams.getValue("3").role)
        assertEquals(Confidence.INFERRED, cams.getValue("3").confidence)
        assertTrue(cams.getValue("3").evidence.any { it.startsWith("min_focus=") })
    }

    @Test
    fun aCameraWithNoColourOutputIsNotOfferedAsALens() {
        val cams = analyse(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("8", focal = 6.0f, sensorW = 5f, sensorH = 4f, megapixels = 8f, producesImages = false),
            ),
        )
        assertEquals(LensRole.DEPTH, cams.getValue("8").role)
    }

    @Test
    fun aLargeSensorLensIsNeverMistakenForAHelper() {
        // Both tests must fail together: a genuine ultra-wide can be 8MP and
        // a genuine periscope sits on a small sensor.
        assertTrue(!CameraInventory.isAuxiliarySensor(
            camera("2", focal = 1.65f, sensorW = 3.58f, sensorH = 2.99f, megapixels = 8f)))
        assertTrue(!CameraInventory.isAuxiliarySensor(
            camera("7", focal = 27.2f, sensorW = 4.09f, sensorH = 3.06f, megapixels = 10f)))
        assertTrue(CameraInventory.isAuxiliarySensor(
            camera("4", focal = 2f, sensorW = 1.61f, sensorH = 1.21f, megapixels = 1.92f)))
    }

    // ── selfies ──────────────────────────────────────────────────────

    @Test
    fun aCroppedModeOfTheSameSelfieIsNotAnUltraWide() {
        // Measured on an S23 Ultra, which has exactly one selfie camera: the
        // OS exposes it at 25.5mm and again at 30.1mm as a cropped mode.
        // Being the wider of the two is not enough — 25.5mm is an ordinary
        // selfie framing, and calling it ultra-wide was a false positive.
        val cams = analyse(galaxyS23Ultra())
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
        assertEquals(LensRole.SELFIE, cams.getValue("3").role)
    }

    @Test
    fun aGenuineSelfieUltraWideIsStillFound() {
        // A real one is a different lens, far wider than its companion.
        val cams = analyse(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("1", facing = "front", focal = 3.2f, sensorW = 4.32f, sensorH = 3.24f),
                camera("9", facing = "front", focal = 1.8f, sensorW = 4.32f, sensorH = 3.24f),
            ),
        )
        assertEquals(LensRole.SELFIE_ULTRA_WIDE, cams.getValue("9").role)
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
    }

    @Test
    fun aLoneFrontCameraIsNeverCalledAnUltraWide() {
        val cams = analyse(
            listOf(
                camera("0", focal = 6.3f, sensorW = 9.79f, sensorH = 7.34f),
                camera("1", facing = "front", focal = 1.9f, sensorW = 4.32f, sensorH = 3.24f),
            ),
        )
        assertEquals(LensRole.SELFIE, cams.getValue("1").role)
    }

    // ── degenerate hardware ──────────────────────────────────────────

    @Test
    fun aSingleCameraPhoneGetsAUsableAnswer() {
        val cams = analyse(
            listOf(
                camera("0", focal = 4.6f, sensorW = 5.6f, sensorH = 4.2f, megapixels = 13f),
                camera("1", facing = "front", focal = 3.0f, sensorW = 3.6f, sensorH = 2.7f, megapixels = 8f),
            ),
        )
        assertEquals(LensRole.WIDE, cams.getValue("0").role)
        assertEquals(1f, cams.getValue("0").zoomRatio!!, 0.001f)
    }

    @Test
    fun aPhoneThatPublishesNoSensorSizesStillProducesALens() {
        val cams = analysed(listOf(camera("0", focal = 4.6f, sensorW = null, sensorH = null, megapixels = 13f)))
        // No sensor size published: the band cannot be computed, so the
        // honest answer is UNKNOWN rather than a guessed WIDE.
        assertEquals(LensRole.UNKNOWN, cams.single().role)
        assertTrue(cams.single().evidence.contains("no_sensor_size_published"))
        assertNull(cams.single().zoomRatio)
    }

    @Test
    fun noCamerasAtAllYieldsOneXRatherThanACrash() {
        val zoom = CameraInventory.computeZoom(emptyList())
        assertEquals(1f, zoom.opticalMin, 0.001f)
        assertEquals(1f, zoom.opticalMax, 0.001f)
        assertEquals(1f, zoom.digitalMax, 0.001f)
        assertEquals(1f, zoom.hybridMax, 0.001f)
    }

    @Test
    fun everyBackCameraBeingAHelperDoesNotCrash() {
        val cams = analysed(listOf(camera("3", focal = 1.9f, sensorW = 2.8f, sensorH = 2.1f, megapixels = 2f)))
        assertTrue(cams.single().role in setOf(LensRole.MACRO, LensRole.DEPTH, LensRole.UNKNOWN))
        assertEquals(1f, CameraInventory.computeZoom(cams).opticalMax, 0.001f)
    }

    // ── JSON ─────────────────────────────────────────────────────────

    @Test
    fun theJsonCarriesEveryRequestedFieldPerCamera() {
        val cams = analysed(galaxyS23Ultra())
        val zoom = CameraInventory.computeZoom(cams)
        val json = CameraInventory.toJson(
            CameraInventory.Inventory(
                "Samsung", "SM-S918B", cams,
                zoom.opticalMin, zoom.opticalMax, zoom.digitalMax, zoom.hybridMax,
                CameraInventory.generateZoomChips(cams, zoom), emptyList(),
            ),
        )
        assertEquals("Samsung", json.getString("brand"))
        assertEquals("SM-S918B", json.getString("model"))
        assertEquals(0.6, json.getDouble("opticalZoomMin"), 0.05)
        assertEquals(10.0, json.getDouble("opticalZoomMax"), 0.5)
        assertEquals(100.0, json.getDouble("hybridZoomMax"), 5.0)

        val first = json.getJSONArray("cameras").getJSONObject(0)
        listOf(
            "id", "role", "openability", "equivalent35mm", "megapixels", "sources",
            "evidence", "confidence", "duplicateIds",
        ).forEach {
            assertTrue("field $it present", first.has(it))
        }
        val ultraWide = (0 until json.getJSONArray("cameras").length())
            .map { json.getJSONArray("cameras").getJSONObject(it) }
            .first { it.getString("id") == "2" }
        assertEquals("ULTRA_WIDE", ultraWide.getString("role"))
        assertEquals(13.6, ultraWide.getDouble("equivalent35mm"), 0.2)
        assertEquals("MEASURED", ultraWide.getString("confidence"))
        assertTrue(
            "evidence names the deciding number",
            (0 until ultraWide.getJSONArray("evidence").length())
                .map { ultraWide.getJSONArray("evidence").getString(it) }
                .any { it.startsWith("equivalent35mm=") },
        )
    }

    @Test
    fun theJsonIsValidUnderACommaDecimalLocale() {
        // A Spanish or German phone formats 0.6 as "0,6". JSON is not
        // localised, so a number written that way is unparseable everywhere
        // downstream. This is the regression that caught it.
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val cams = analysed(galaxyS23Ultra())
        val zoom = CameraInventory.computeZoom(cams)
        val json = CameraInventory.toJson(
            CameraInventory.Inventory(
                "Samsung", "SM-S918B", cams,
                zoom.opticalMin, zoom.opticalMax, zoom.digitalMax, zoom.hybridMax,
                CameraInventory.generateZoomChips(cams, zoom), emptyList(),
            ),
        )
        val text = json.toString()
        assertTrue("no comma decimals anywhere in the JSON", !Regex("""\d,\d""").containsMatchIn(text))
        // Still parseable, and still the right numbers.
        val reparsed = org.json.JSONObject(text)
        assertEquals(0.6, reparsed.getDouble("opticalZoomMin"), 0.05)
        assertNotNull(reparsed.getJSONArray("cameras"))
        assertEquals("0.6x", CameraInventory.formatZoom(0.6f))
    }


    // ── zoom chips ───────────────────────────────────────────────────

    private fun chipRatios(cameras: List<CameraDescriptor>): List<Float> {
        val analysedCameras = analysed(cameras)
        val zoom = CameraInventory.computeZoom(analysedCameras)
        return CameraInventory.generateZoomChips(analysedCameras, zoom).map { it.ratio }
    }

    /**
     * A device shaped to a given set of optical ratios and one hybrid reach.
     *
     * The 1x lens gets the largest sensor, because that is how the classifier
     * finds the main camera and how every real phone is built. An earlier
     * version of this helper gave every lens the same sensor, so the main was
     * picked arbitrarily and every ratio came out measured from the wrong
     * lens — the fixture was wrong, not the code.
     */
    private fun deviceWithLenses(ratios: List<Float>, hybridMax: Float): List<CameraDescriptor> {
        val mainEquivalent = 24f
        val longest = ratios.max()
        return ratios.mapIndexed { index, ratio ->
            val isMain = ratio == 1f
            val sensorW = if (isMain) 9.8f else 5.6f
            val sensorH = if (isMain) 7.3f else 4.2f
            val diagonal = kotlin.math.hypot(sensorW, sensorH)
            // Chosen so equivalent35mm lands exactly on mainEquivalent * ratio.
            val focal = mainEquivalent * ratio * diagonal / 43.267f
            camera(
                id = index.toString(),
                focal = focal,
                sensorW = sensorW,
                sensorH = sensorH,
                megapixels = 12f,
                // The crop that produces the stated hybrid reach belongs to
                // the longest lens.
                digitalZoom = if (ratio == longest) hybridMax / longest else 2f,
            )
        }
    }

    @Test
    fun chipsForAFourLensFlagshipMatchTheDocumentedProgression() {
        // optical 0.6/1/3/10, hybrid 100 -> 0.6 1 2 3 5 10 30 100
        val ratios = chipRatios(deviceWithLenses(listOf(0.6f, 1f, 3f, 10f), hybridMax = 100f))
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 30f, 100f), ratios)
    }

    @Test
    fun chipsForAFiveXDeviceMatchTheDocumentedProgression() {
        // optical 0.5/1/5, hybrid 100 -> 0.5 1 2 3 5 20 50 100
        val ratios = chipRatios(deviceWithLenses(listOf(0.5f, 1f, 5f), hybridMax = 100f))
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f, 20f, 50f, 100f), ratios)
    }

    @Test
    fun chipsForAThreeXDeviceMatchTheDocumentedProgression() {
        // optical 0.5/1/3, hybrid 30 -> 0.5 1 2 3 10 30
        val ratios = chipRatios(deviceWithLenses(listOf(0.5f, 1f, 3f), hybridMax = 30f))
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 10f, 30f), ratios)
    }

    @Test
    fun nothingIsGeneratedBelowOneX() {
        // Below 1x there is no digital zoom — only a wider lens gets there —
        // so a generated 0.6 beside a 0.5 lens would be a lens that does not
        // exist. The only sub-1x chips are real lenses.
        val cameras = analysed(deviceWithLenses(listOf(0.5f, 1f, 3f), hybridMax = 30f))
        val zoom = CameraInventory.computeZoom(cameras)
        CameraInventory.generateZoomChips(cameras, zoom)
            .filter { it.ratio < 1f }
            .forEach { assertTrue("sub-1x chip ${it.ratio} must be a real lens", it.isPhysicalLens) }
    }

    @Test
    fun everyPhysicalLensGetsAChipAndALabel() {
        val cameras = analysed(galaxyS23Ultra())
        val zoom = CameraInventory.computeZoom(cameras)
        val chips = CameraInventory.generateZoomChips(cameras, zoom)
        val physical = chips.filter { it.isPhysicalLens }
        assertEquals(4, physical.size)
        assertEquals(listOf("UW", "W", "T", "ST"), physical.map { it.label })
        // Each points at the camera it will bind.
        assertTrue(physical.all { it.cameraId != null })
    }

    @Test
    fun generatedChipsCarryNoLensLabel() {
        // A crop is not a lens; labelling 30x as "ST" would claim hardware
        // that is not there.
        val cameras = analysed(deviceWithLenses(listOf(0.6f, 1f, 3f, 10f), hybridMax = 100f))
        val zoom = CameraInventory.computeZoom(cameras)
        CameraInventory.generateZoomChips(cameras, zoom)
            .filterNot { it.isPhysicalLens }
            .forEach {
                assertNull("generated chip ${it.ratio} must have no label", it.label)
                assertNull("generated chip ${it.ratio} binds no camera", it.cameraId)
            }
    }

    @Test
    fun chipsAreSortedUniqueAndFreeOfAbsurdJumps() {
        listOf(
            deviceWithLenses(listOf(0.6f, 1f, 3f, 10f), 100f),
            deviceWithLenses(listOf(0.5f, 1f, 5f), 100f),
            deviceWithLenses(listOf(0.5f, 1f, 3f), 30f),
            deviceWithLenses(listOf(1f), 8f),
        ).forEach { device ->
            val ratios = chipRatios(device)
            assertEquals("sorted", ratios.sorted(), ratios)
            assertEquals("unique", ratios.distinct().size, ratios.size)
            ratios.zipWithNext().forEach { (low, high) ->
                assertTrue("no absurd jump $low -> $high", high / low <= 4f)
            }
        }
    }

    @Test
    fun aSingleLensPhoneWithNoDigitalReachShowsOneChip() {
        val cameras = analysed(
            listOf(camera("0", focal = 4.6f, sensorW = 5.6f, sensorH = 4.2f, digitalZoom = 1f)),
        )
        val zoom = CameraInventory.computeZoom(cameras)
        val chips = CameraInventory.generateZoomChips(cameras, zoom)
        assertEquals(1, chips.size)
        assertEquals("W", chips.single().label)
    }

    @Test
    fun noCamerasProduceNoChipsRatherThanAFakeOneX() {
        assertTrue(
            CameraInventory.generateZoomChips(emptyList(), CameraInventory.computeZoom(emptyList())).isEmpty(),
        )
    }

    @Test
    fun zoomIsPrintedTheWayACameraAppPrintsIt() {
        assertEquals("10x", CameraInventory.formatZoom(10f))
        assertEquals("0.6x", CameraInventory.formatZoom(0.6f))
        assertEquals("1.4x", CameraInventory.formatZoom(1.4f))
        assertEquals("100x", CameraInventory.formatZoom(100f))
    }

    @Test
    fun zoomRatiosSnapOnlyWhenNearlyConventional() {
        assertEquals(0.6f, CameraInventory.roundZoom(0.6489f), 0.001f)
        assertEquals(3f, CameraInventory.roundZoom(2.9976f), 0.001f)
        assertEquals(1.4f, CameraInventory.roundZoom(1.42f), 0.001f)
    }
}
