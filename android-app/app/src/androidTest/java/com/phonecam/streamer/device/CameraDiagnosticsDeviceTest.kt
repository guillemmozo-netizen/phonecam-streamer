package com.phonecam.streamer.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real probe against the real HAL of whatever device this is
 * connected to.
 *
 * A JVM test cannot check this: there is no CameraManager to answer, and the
 * whole question the report exists to settle — *does this phone tell the truth
 * about itself* — only has meaning on hardware. So this asserts the report's
 * shape (every requested section present, on every camera, with no section
 * having fallen back to its error branch) and prints the report, which is how
 * the JSON gets off a device whose screen is locked.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 * and read the full report back with:
 *   adb shell run-as com.phonecam.streamer cat files/diagnostics-dump.json
 */
@RunWith(AndroidJUnit4::class)
class CameraDiagnosticsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Every section the diagnostics screen and the export promise, per camera. */
    private val requiredCameraSections = listOf(
        "identification", "sensor", "exposure", "fpsAndVideo", "resolutions",
        "lens", "focus", "whiteBalance", "flash", "capabilities",
        "stabilization", "outputFormats",
    )

    @Test
    fun theReportCoversEveryCameraOnThisDevice() {
        val report = CameraDiagnostics.probe(context)
        val json = report.json

        assertTrue("no camerasError", !json.has("camerasError"))
        val cameras = json.optJSONArray("cameras") ?: JSONArray()
        assertTrue("at least one camera was found", cameras.length() > 0)
        assertEquals("a summary per camera", cameras.length(), report.cameras.size)

        for (i in 0 until cameras.length()) {
            val cam = cameras.getJSONObject(i)
            val id = cam.getJSONObject("identification").optString("cameraId")
            requiredCameraSections.forEach { name ->
                assertTrue("camera $id has section $name", cam.has(name))
                val section = cam.getJSONObject(name)
                // A section that fell into its catch branch is a section that
                // reported nothing; the point of the diagnostic is that this
                // does not happen silently.
                assertTrue(
                    "camera $id section $name did not fail: ${section.optString("error")}",
                    !section.has("error"),
                )
            }
        }
    }

    @Test
    fun deviceAndDisplaySectionsArePresent() {
        val json = CameraDiagnostics.probe(context).json
        listOf("system", "display").forEach { name ->
            assertTrue("has $name", json.has(name))
            assertTrue("$name did not fail", !json.getJSONObject(name).has("error"))
        }
        val system = json.getJSONObject("system")
        listOf("model", "manufacturer", "androidVersion", "sdkInt", "abis", "ramTotalMb").forEach {
            assertTrue("system.$it present", system.has(it))
        }
        val display = json.getJSONObject("display")
        listOf("widthPx", "heightPx", "densityDpi", "refreshRateHz", "hdrSupported").forEach {
            assertTrue("display.$it present", display.has(it))
        }
        // Not just present — actually answered. This ran as null for a while:
        // Context.getDisplay() throws on the non-visual context an
        // instrumentation test hands over, so the report's refresh rate
        // depended on who called it. It describes the device; it must not.
        assertTrue(
            "refresh rate was actually read, not swallowed",
            display.optDouble("refreshRateHz", 0.0) > 0.0,
        )
    }

    @Test
    fun absentValuesStayInTheReportAsNull() {
        // The rule the whole thing rests on: a field this HAL does not publish
        // must still appear, as an explicit null, so "unsupported" and "never
        // asked" stay distinguishable. JSONObject.put(key, null) removes the
        // key, which is exactly the trap.
        val cameras = CameraDiagnostics.probe(context).json.getJSONArray("cameras")
        val sensor = cameras.getJSONObject(0).getJSONObject("sensor")
        listOf(
            "physicalSizeMm", "pixelArraySize", "activeArraySize", "pixelPitchMicrons",
            "sensorOrientation", "colorFilterArrangement", "whiteLevel", "blackLevelPattern",
        ).forEach { assertTrue("sensor.$it is present (may be null)", sensor.has(it)) }
    }

    /**
     * Not an assertion — a delivery mechanism. Writes the full report where
     * `adb shell run-as` can read it, which is the only way to see it when the
     * device's screen cannot be driven.
     */
    @Test
    fun dumpTheWholeReportForInspection() {
        val report = CameraDiagnostics.probe(context)
        val json = CameraDiagnostics.toJson(report)
        context.openFileOutput("diagnostics-dump.json", android.content.Context.MODE_PRIVATE).use {
            it.write(json.toByteArray())
        }
        println("PHONECAM_DIAGNOSTICS_BYTES=${json.length}")
        println("PHONECAM_DIAGNOSTICS_CAMERAS=${report.cameras.size}")
        report.cameras.forEach {
            println("PHONECAM_CAMERA id=${it.id} ${it.facing} physical=${it.isPhysical} ${it.hardwareLevel} ${it.megapixels}MP iso=${it.isoRange} shutter=${it.shutterRange}")
        }
    }
}
