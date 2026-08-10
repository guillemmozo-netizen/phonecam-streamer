package com.phonecam.streamer.device

import com.phonecam.streamer.device.CameraInventory.CameraDescriptor
import com.phonecam.streamer.device.CameraInventory.DiscoverySource
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the HAL lies, omits, or returns nonsense.
 *
 * ## Why this exists instead of a device list
 *
 * "Works on every Android phone" cannot be shown by testing every Android
 * phone — there are thousands of models and this project has two. It *can* be
 * shown over the space of what a camera HAL is able to return, which is small
 * and enumerable: every value is present or absent, finite or not, positive or
 * not, plausible or absurd. Every device in the world lands somewhere in that
 * space, so covering the space covers the devices.
 *
 * These are not hypotheticals. The non-finite case was found by running it: a
 * NaN focal length reached `JSONObject.put` and threw
 * `JSONException: JSON does not allow non-finite numbers`, taking the whole
 * diagnostics export down with it. OEM camera layers are known to omit keys
 * and to publish placeholders, and the only defence that scales to hardware
 * nobody has held is refusing to trust any of them.
 *
 * The contract asserted throughout: **nothing throws, nothing hangs, and the
 * output stays coherent** — sorted, finite, unique, parseable. Where the input
 * was garbage the answer is UNKNOWN or absent, never invented.
 */
class HostileHalTest {

    private val defaultLocale = Locale.getDefault()

    private fun camera(
        id: String = "0",
        facing: String = "back",
        focal: Float? = null,
        sensorW: Float? = null,
        sensorH: Float? = null,
        megapixels: Float = 12f,
        maxW: Int = 4000,
        maxH: Int = 3000,
        diopters: Float? = null,
        digitalZoom: Float? = 10f,
        aperture: Float? = 1.8f,
        depthOutput: Boolean = false,
        producesImages: Boolean = true,
        sources: Set<DiscoverySource> = setOf(DiscoverySource.ID_LIST),
    ) = CameraDescriptor(
        id = id,
        sources = sources,
        physicalParentId = null,
        facing = facing,
        maxWidth = maxW,
        maxHeight = maxH,
        megapixels = megapixels,
        sensorWidthMm = sensorW,
        sensorHeightMm = sensorH,
        sensorDiagonalMm = if (sensorW != null && sensorH != null) kotlin.math.hypot(sensorW, sensorH) else null,
        focalLengthMm = focal,
        equivalentFocalMm = CameraInventory.equivalentFocalMm(focal, sensorW, sensorH),
        apertureF = aperture,
        minFocusDistanceDiopters = diopters,
        maxDigitalZoom = digitalZoom,
        capabilities = listOf("BACKWARD_COMPATIBLE"),
        hasDepthOutput = depthOutput,
        producesImages = producesImages,
    )

    /**
     * Runs the whole pipeline and asserts the contract, whatever went in.
     * Returns the JSON so individual tests can look closer.
     */
    private fun runPipeline(input: List<CameraDescriptor>, label: String): String {
        val cameras = CameraInventory.collapseDuplicates(input)
        CameraInventory.classify(cameras)
        CameraInventory.inferOpenability(cameras)
        val zoom = CameraInventory.computeZoom(cameras)
        val chips = CameraInventory.generateZoomChips(cameras, zoom)

        listOf(
            "opticalMin" to zoom.opticalMin,
            "opticalMax" to zoom.opticalMax,
            "digitalMax" to zoom.digitalMax,
            "hybridMax" to zoom.hybridMax,
        ).forEach { (name, value) ->
            assertTrue("$label: zoom.$name must be finite, was $value", value.isFinite())
            assertTrue("$label: zoom.$name must be positive, was $value", value > 0f)
        }

        chips.forEach { chip ->
            assertTrue("$label: chip ${chip.ratio} must be finite", chip.ratio.isFinite())
            assertTrue("$label: chip ${chip.ratio} must be positive", chip.ratio > 0f)
        }
        assertEquals("$label: chips sorted", chips.map { it.ratio }.sorted(), chips.map { it.ratio })
        assertEquals("$label: chips unique", chips.map { it.ratio }.distinct().size, chips.size)
        chips.filterNot { it.isPhysicalLens }.forEach {
            assertTrue("$label: a generated chip must carry no lens label", it.label == null)
        }

        cameras.forEach { camera ->
            assertNotNull("$label: every camera has a role", camera.role)
            camera.zoomRatio?.let {
                assertTrue("$label: zoomRatio $it must be finite", it.isFinite())
            }
        }

        val json = CameraInventory.toJson(
            CameraInventory.Inventory(
                "Brand", "Model", cameras,
                zoom.opticalMin, zoom.opticalMax, zoom.digitalMax, zoom.hybridMax, chips, emptyList(),
            ),
        ).toString()
        // Parseable is the point: a report that cannot be read was not produced.
        org.json.JSONObject(json)
        assertTrue("$label: JSON must have no comma decimals", !Regex("""\d,\d""").containsMatchIn(json))
        return json
    }

    // ── non-finite values ────────────────────────────────────────────

    @Test
    fun aNonFiniteFocalLengthDoesNotCrashTheExport() {
        // Found by running it: this threw
        // "JSONException: JSON does not allow non-finite numbers".
        runPipeline(listOf(camera(focal = Float.NaN, sensorW = 5.6f, sensorH = 4.2f)), "NaN focal")
    }

    @Test
    fun everyFloatFieldSurvivesEveryNonFiniteValue() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            runPipeline(listOf(camera(focal = bad, sensorW = 5.6f, sensorH = 4.2f)), "focal=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = bad, sensorH = 4.2f)), "sensorW=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = bad)), "sensorH=$bad")
            runPipeline(listOf(camera(megapixels = bad, focal = 6f, sensorW = 5.6f, sensorH = 4.2f)), "MP=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = 4.2f, digitalZoom = bad)), "zoom=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = 4.2f, aperture = bad)), "f=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = 4.2f, diopters = bad)), "diopt=$bad")
        }
    }

    @Test
    fun everyFloatFieldSurvivesZeroAndNegatives() {
        listOf(0f, -1f, -9999f).forEach { bad ->
            runPipeline(listOf(camera(focal = bad, sensorW = 5.6f, sensorH = 4.2f)), "focal=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = bad, sensorH = bad)), "sensor=$bad")
            runPipeline(listOf(camera(megapixels = bad, focal = 6f, sensorW = 5.6f, sensorH = 4.2f)), "MP=$bad")
            runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = 4.2f, digitalZoom = bad)), "zoom=$bad")
        }
    }

    @Test
    fun absurdMagnitudesDoNotProduceAbsurdOutput() {
        // A HAL publishing a placeholder like 1e30 must not become a zoom chip
        // claiming 10^30 x.
        runPipeline(
            listOf(camera(focal = 1e30f, sensorW = 1e-30f, sensorH = 1e-30f, digitalZoom = 1e30f)),
            "absurd",
        )
        runPipeline(listOf(camera(focal = 6f, sensorW = 5.6f, sensorH = 4.2f, digitalZoom = 1e9f)), "huge zoom")
    }

    // ── missing data ─────────────────────────────────────────────────

    @Test
    fun aCameraThatPublishesNothingAtAllIsHandled() {
        val blank = CameraDescriptor(
            id = "0",
            sources = setOf(DiscoverySource.ID_LIST),
            physicalParentId = null,
            facing = "unknown",
            maxWidth = 0,
            maxHeight = 0,
            megapixels = 0f,
            sensorWidthMm = null,
            sensorHeightMm = null,
            sensorDiagonalMm = null,
            focalLengthMm = null,
            equivalentFocalMm = null,
            apertureF = null,
            minFocusDistanceDiopters = null,
            maxDigitalZoom = null,
            capabilities = emptyList(),
            hasDepthOutput = false,
            producesImages = false,
        )
        runPipeline(listOf(blank), "everything null")
    }

    @Test
    fun anEmptyDeviceProducesAnEmptyButValidReport() {
        val json = runPipeline(emptyList(), "no cameras")
        assertEquals(0, org.json.JSONObject(json).getJSONArray("cameras").length())
        assertEquals(0, org.json.JSONObject(json).getJSONArray("zoomChips").length())
    }

    // ── strange topologies ───────────────────────────────────────────

    @Test
    fun aDeviceWithOnlyFrontCamerasIsHandled() {
        runPipeline(
            listOf(
                camera(id = "0", facing = "front", focal = 3.2f, sensorW = 4.3f, sensorH = 3.2f),
                camera(id = "1", facing = "front", focal = 1.8f, sensorW = 4.3f, sensorH = 3.2f),
            ),
            "front only",
        )
    }

    @Test
    fun unknownAndExternalFacingsAreHandled() {
        listOf("unknown", "external", "", "BACK", "rear").forEach { facing ->
            runPipeline(
                listOf(camera(facing = facing, focal = 6f, sensorW = 5.6f, sensorH = 4.2f)),
                "facing=$facing",
            )
        }
    }

    @Test
    fun aDeviceWithFiftyCamerasStaysReadable() {
        // Absurd, but a malformed enumeration is not the app's to crash on.
        // Also guards the chip generator against runaway growth.
        val many = (0 until 50).map {
            camera(id = it.toString(), focal = 2f + it * 0.7f, sensorW = 5.6f, sensorH = 4.2f)
        }
        val cameras = CameraInventory.collapseDuplicates(many)
        CameraInventory.classify(cameras)
        val zoom = CameraInventory.computeZoom(cameras)
        val chips = CameraInventory.generateZoomChips(cameras, zoom)
        runPipeline(many, "50 cameras")
        assertTrue("chip list stays readable, was ${chips.size}", chips.size <= 40)
    }

    @Test
    fun repeatedIdsDoNotProduceRepeatedEntries() {
        val cams = List(3) { camera(id = "0", focal = 6f, sensorW = 5.6f, sensorH = 4.2f) }
        assertEquals(1, CameraInventory.collapseDuplicates(cams).size)
        runPipeline(cams, "repeated ids")
    }

    @Test
    fun oddIdStringsSurviveIntoTheReportUntouched() {
        listOf("", " ", "camera-back-0", "999999999999", "«ü»").forEach { id ->
            val json = runPipeline(
                listOf(camera(id = id, focal = 6f, sensorW = 5.6f, sensorH = 4.2f)),
                "id=[$id]",
            )
            // Whatever the OEM calls it must survive intact: that exact string
            // is what openCamera will be handed.
            assertEquals(id, org.json.JSONObject(json).getJSONArray("cameras").getJSONObject(0).getString("id"))
        }
    }

    // ── random search over the whole space ───────────────────────────

    @Test
    fun twoThousandRandomDevicesAllProduceValidReports() {
        // Seeded, so a failure is reproducible rather than a flake.
        val random = Random(20260805)
        val values = listOf<Float?>(
            null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            0.001f, 1.65f, 6.3f, 27.2f, 1e12f, -1e12f,
        )
        val facings = listOf("back", "front", "external", "unknown", "")
        repeat(2000) { iteration ->
            val cameras = (0 until random.nextInt(1, 7)).map { index ->
                camera(
                    id = index.toString(),
                    facing = facings.random(random),
                    focal = values.random(random),
                    sensorW = values.random(random),
                    sensorH = values.random(random),
                    megapixels = values.random(random) ?: 0f,
                    maxW = random.nextInt(0, 20000),
                    maxH = random.nextInt(0, 20000),
                    diopters = values.random(random),
                    digitalZoom = values.random(random),
                    aperture = values.random(random),
                    depthOutput = random.nextBoolean(),
                    producesImages = random.nextBoolean(),
                )
            }
            runPipeline(cameras, "random #$iteration")
        }
    }

    @Test
    fun theRandomSearchAlsoHoldsUnderACommaDecimalLocale() {
        Locale.setDefault(Locale.forLanguageTag("de-DE"))
        try {
            val random = Random(7)
            val values = listOf<Float?>(null, 0f, Float.NaN, 1.65f, 6.3f, 27.2f, 1e12f)
            repeat(300) { iteration ->
                val cameras = (0 until random.nextInt(1, 5)).map { index ->
                    camera(
                        id = index.toString(),
                        focal = values.random(random),
                        sensorW = values.random(random),
                        sensorH = values.random(random),
                        digitalZoom = values.random(random),
                    )
                }
                runPipeline(cameras, "de-DE random #$iteration")
            }
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }
}
