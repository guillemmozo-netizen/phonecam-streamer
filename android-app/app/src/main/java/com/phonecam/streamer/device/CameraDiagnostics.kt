package com.phonecam.streamer.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CameraDiagnostics"

/**
 * Everything Camera2 will say about this phone, as structured JSON.
 *
 * Separate from [DeviceCapabilities] on purpose, and not a replacement for it.
 * That one answers "what should the app offer the user", and deliberately
 * *overrides* the runtime probe with [DeviceModelDatabase] where OEM HALs lie
 * about their own hardware. This one answers "what does this device actually
 * report", with no corrections and no curation — a diagnostic has to show the
 * lie too, or it cannot be used to find one.
 *
 * Design rules, both learned from the geometry campaign in this repo:
 *
 * - **Nothing here may throw.** A diagnostic that crashes on the one device
 *   whose HAL returns something unexpected is worse than useless, because that
 *   device is exactly the one worth diagnosing. Every characteristic read goes
 *   through [get], every section through [section]; a failure becomes a JSON
 *   field saying so and the rest of the report still lands.
 * - **Absent and unsupported are different things.** A field the HAL does not
 *   publish is `null`, not `0` or `false`. Guessing a default is how a
 *   diagnostic starts confirming assumptions instead of testing them.
 *
 * The UI renders the "technical" half straight from this JSON rather than from
 * a parallel set of getters, so what is on screen and what is exported cannot
 * disagree.
 */
object CameraDiagnostics {

    /** Schema version — bump when the shape changes, so exported files stay readable. */
    private const val SCHEMA_VERSION = 1

    /**
     * The handful of fields the diagnostics screen shows without being asked.
     * Everything else is one "see more" away, and everything, including these,
     * is in [toJson].
     */
    data class CameraSummary(
        val id: String,
        val facing: String,
        val isPhysical: Boolean,
        val hardwareLevel: String,
        val megapixels: Float?,
        val maxResolution: String?,
        val focalLengthMm: Float?,
        val apertureF: Float?,
        val sensorSizeMm: String?,
        val pixelPitchUm: Float?,
        val isoRange: String?,
        val shutterRange: String?,
        val maxFps: Int?,
        val hasOis: Boolean,
        val hasFlash: Boolean,
        val capabilities: List<String>,
    )

    data class Report(
        val json: JSONObject,
        val cameras: List<CameraSummary>,
        val deviceLine: String,
        val systemLine: String,
        val displayLine: String,
    )

    // ──────────────────────────────────────────────────────────────────
    // Entry point
    // ──────────────────────────────────────────────────────────────────

    fun probe(context: Context): Report {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("generatedAt", System.currentTimeMillis())

        val system = section("system") { systemJson(context) }
        val display = section("display") { displayJson(context) }
        root.put("system", system)
        root.put("display", display)

        val summaries = mutableListOf<CameraSummary>()
        val camerasJson = JSONArray()
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Logical/top-level ids first, then every physical sub-camera they
            // declare. The sub-cameras are the interesting half on a modern
            // phone: the ultra-wide and the tele usually have no top-level id
            // at all, so a probe that stops at cameraIdList reports a
            // four-lens phone as one camera.
            manager.cameraIdList.forEach { id ->
                val chars = characteristics(manager, id) ?: return@forEach
                camerasJson.put(cameraJson(manager, id, chars, physicalParent = null, summaries))
                physicalIdsOf(chars).forEach { physicalId ->
                    val sub = characteristics(manager, physicalId) ?: return@forEach
                    camerasJson.put(cameraJson(manager, physicalId, sub, physicalParent = id, summaries))
                }
            }
        } catch (e: Throwable) {
            root.put("camerasError", e.toString())
            Log.e(TAG, "camera enumeration failed", e)
        }
        root.put("cameras", camerasJson)

        return Report(
            json = root,
            cameras = summaries,
            deviceLine = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemLine = "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · " +
                (system.optString("soc").takeIf { it.isNotEmpty() && it != "null" } ?: "SoC ?"),
            displayLine = display.optString("summary", "?"),
        )
    }

    /** Pretty-printed, which is the only form worth exporting to a human. */
    fun toJson(report: Report): String = report.json.toString(2)

    // ──────────────────────────────────────────────────────────────────
    // Per camera
    // ──────────────────────────────────────────────────────────────────

    private fun cameraJson(
        manager: CameraManager,
        id: String,
        chars: CameraCharacteristics,
        physicalParent: String?,
        summaries: MutableList<CameraSummary>,
    ): JSONObject {
        val json = JSONObject()
        val facing = facingName(chars.get(CameraCharacteristics.LENS_FACING))
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        json.put("identification", section("identification") { identificationJson(id, chars, physicalParent, facing) })
        json.put("sensor", section("sensor") { sensorJson(chars) })
        json.put("exposure", section("exposure") { exposureJson(chars) })
        json.put("fpsAndVideo", section("fpsAndVideo") { fpsJson(chars, map) })
        json.put("resolutions", section("resolutions") { resolutionsJson(map) })
        json.put("lens", section("lens") { lensJson(chars) })
        json.put("focus", section("focus") { focusJson(chars) })
        json.put("whiteBalance", section("whiteBalance") { whiteBalanceJson(chars) })
        json.put("flash", section("flash") { flashJson(chars) })
        json.put("capabilities", section("capabilities") { capabilitiesJson(chars) })
        json.put("stabilization", section("stabilization") { stabilizationJson(chars) })
        json.put("outputFormats", section("outputFormats") { outputFormatsJson(map) })

        summaries += summarize(id, chars, map, facing, physicalParent != null)
        return json
    }

    private fun identificationJson(
        id: String,
        chars: CameraCharacteristics,
        physicalParent: String?,
        facing: String,
    ): JSONObject = JSONObject().apply {
        p("cameraId", id)
        p("logicalName", if (physicalParent == null) "logical:$id" else "physical:$id of $physicalParent")
        p("isPhysicalSubCamera", physicalParent != null)
        p("physicalParentId", physicalParent)
        p("physicalCameraIds", JSONArray(physicalIdsOf(chars).toList()))
        p("facing", facing)
        p("hardwareLevel", hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p("version", get { chars.get(CameraCharacteristics.INFO_VERSION) })
        }
    }

    private fun sensorJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        p("physicalSizeMm", physical?.let { JSONObject().put("width", it.width).put("height", it.height) })
        p("pixelArraySize", sizeJson(pixelArray))
        p("activeArraySize", activeArray?.let { sizeJson(Size(it.width(), it.height())) })
        p("activeArrayRect", activeArray?.let {
            JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)
        })
        p("resolutionMegapixels", pixelArray?.let { (it.width.toLong() * it.height) / 1_000_000f })
        // Pixel pitch is not published; it is physical width over pixel count,
        // and it is the number that actually predicts low-light behaviour.
        p("pixelPitchMicrons", pixelPitchUm(physical?.width, pixelArray?.width))
        p("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))
        p(
            "colorFilterArrangement",
            colorFilterName(chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)),
        )
        p("whiteLevel", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
        p("blackLevelPattern", get {
            chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
                val out = IntArray(4)
                pattern.copyTo(out, 0)
                JSONArray(out.toList())
            }
        })
        p("timestampSource", chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p("maxResolutionPixelArray", sizeJson(get {
                chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            }))
        }
    }

    private fun exposureJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        p("isoMin", iso?.lower)
        p("isoMax", iso?.upper)
        p("isoMaxAnalog", chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY))
        p("exposureTimeMinNs", exposure?.lower)
        p("exposureTimeMaxNs", exposure?.upper)
        p("exposureTimeMinLabel", exposure?.lower?.let { shutterLabel(it) })
        p("exposureTimeMaxLabel", exposure?.upper?.let { shutterLabel(it) })
        p("maxFrameDurationNs", chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION))
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        p("exposureCompensationMin", evRange?.lower)
        p("exposureCompensationMax", evRange?.upper)
        val step: Rational? = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        p("exposureCompensationStep", step?.toString())
        p("exposureCompensationStepEv", step?.toFloat())
        p("aeLockAvailable", chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))
        p("aeModes", namesArray(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), ::aeModeName))
        p(
            "aeAntibandingModes",
            namesArray(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)) { "ANTIBANDING_$it" },
        )
    }

    private fun fpsJson(chars: CameraCharacteristics, map: StreamConfigurationMap?): JSONObject = JSONObject().apply {
        val ranges: Array<Range<Int>>? = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        p("aeTargetFpsRanges", JSONArray((ranges ?: emptyArray()).map { "${it.lower}-${it.upper}" }))
        p("maxAeTargetFps", ranges?.maxOfOrNull { it.upper })

        // Per-resolution truth: min frame duration is what actually caps fps
        // for a given output size, and it is per format. PRIVATE is the one
        // the streaming path uses.
        val perSize = JSONArray()
        map?.let { m ->
            get { m.getOutputSizes(ImageFormat.PRIVATE) }?.forEach { size ->
                val minDuration = get { m.getOutputMinFrameDuration(ImageFormat.PRIVATE, size) } ?: 0L
                val stall = get { m.getOutputStallDuration(ImageFormat.PRIVATE, size) } ?: 0L
                perSize.put(
                    JSONObject()
                        .put("size", "${size.width}x${size.height}")
                        .put("minFrameDurationNs", minDuration)
                        .put("maxFps", if (minDuration > 0) (1_000_000_000.0 / minDuration).toInt() else null)
                        .put("stallDurationNs", stall),
                )
            }
        }
        p("privateOutputTiming", perSize)

        val highSpeed = JSONArray()
        map?.let { m ->
            get { m.highSpeedVideoSizes }?.forEach { size ->
                val fpsRanges = get { m.getHighSpeedVideoFpsRangesFor(size) }
                highSpeed.put(
                    JSONObject()
                        .put("size", "${size.width}x${size.height}")
                        .put("fpsRanges", JSONArray((fpsRanges ?: emptyArray()).map { "${it.lower}-${it.upper}" })),
                )
            }
        }
        p("highSpeedVideoModes", highSpeed)
        p("streamConfigurationMap", map?.toString())
    }

    private fun resolutionsJson(map: StreamConfigurationMap?): JSONObject = JSONObject().apply {
        if (map == null) {
            put("error", "no StreamConfigurationMap")
            return@apply
        }
        put("jpeg", sizesArray(get { map.getOutputSizes(ImageFormat.JPEG) }))
        put("raw", sizesArray(get { map.getOutputSizes(ImageFormat.RAW_SENSOR) }))
        put("yuv420888", sizesArray(get { map.getOutputSizes(ImageFormat.YUV_420_888) }))
        put("private", sizesArray(get { map.getOutputSizes(ImageFormat.PRIVATE) }))
        put("surfaceTexture", sizesArray(get { map.getOutputSizes(android.graphics.SurfaceTexture::class.java) }))
        put("mediaRecorder", sizesArray(get { map.getOutputSizes(MediaRecorder::class.java) }))
        put("heic", sizesArray(get { map.getOutputSizes(ImageFormat.HEIC) }))
        put("depth16", sizesArray(get { map.getOutputSizes(ImageFormat.DEPTH16) }))
        put("nv21", sizesArray(get { map.getOutputSizes(ImageFormat.NV21) }))
        put("yv12", sizesArray(get { map.getOutputSizes(ImageFormat.YV12) }))
        // Everything the map admits to, including formats not named above.
        val all = JSONObject()
        get { map.outputFormats }?.forEach { format ->
            all.put(imageFormatName(format), sizesArray(get { map.getOutputSizes(format) }))
        }
        put("allOutputFormats", all)
        put("highResolutionSizes", JSONObject().apply {
            get { map.outputFormats }?.forEach { format ->
                val sizes = get { map.getHighResolutionOutputSizes(format) }
                if (sizes != null && sizes.isNotEmpty()) put(imageFormatName(format), sizesArray(sizes))
            }
        })
    }

    private fun lensJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        p("aperturesF", JSONArray((chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)).toList()))
        p(
            "focalLengthsMm",
            JSONArray((chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)).toList()),
        )
        val minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        p("minimumFocusDistanceDiopters", minFocusDiopters)
        // Diopters are 1/metres, and 0 means "fixed focus at infinity" rather
        // than "can focus at 0m" — inverting it blindly gives an infinity.
        p("minimumFocusDistanceMeters", minFocusDiopters?.takeIf { it > 0f }?.let { 1f / it })
        val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        p("hyperfocalDistanceDiopters", hyperfocal)
        p("hyperfocalDistanceMeters", hyperfocal?.takeIf { it > 0f }?.let { 1f / it })
        p("focusDistanceCalibration", chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION))
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        p("opticalStabilizationModes", JSONArray((ois ?: IntArray(0)).toList()))
        p("opticalStabilizationAvailable", (ois ?: IntArray(0)).any { it != 0 })
        p("maxDigitalZoom", chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoom = get { chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
            p("zoomRatioRange", zoom?.let { "${it.lower}-${it.upper}" })
            // Below 1.0 means a wider-than-default lens is reachable through
            // the logical camera: real optical zoom-out, not a crop.
            p("opticalZoomOutAvailable", zoom?.let { it.lower < 1f })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p("distortion", JSONArray((get { chars.get(CameraCharacteristics.LENS_DISTORTION) } ?: FloatArray(0)).toList()))
        }
        p(
            "intrinsicCalibration",
            JSONArray((get { chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) } ?: FloatArray(0)).toList()),
        )
        p("facingIsExternal", chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_EXTERNAL)
    }

    private fun focusJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        put("afModes", namesArray(modes, ::afModeName))
        put("manualFocusSupported", modes.contains(CameraMetadata.CONTROL_AF_MODE_OFF))
        put("continuousVideo", modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
        put("continuousPicture", modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
        put("macro", modes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO))
        put("edof", modes.contains(CameraMetadata.CONTROL_AF_MODE_EDOF))
        put("maxRegionsAf", chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF))
    }

    private fun whiteBalanceJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val modes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        put("awbAvailable", modes.isNotEmpty())
        put("awbLockAvailable", chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))
        put("awbModes", namesArray(modes, ::awbModeName))
        put("maxRegionsAwb", chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB))
    }

    private fun flashJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val available = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        p("flashAvailable", available)
        // There is no FLASH_AVAILABLE_MODES: the flash modes a device really
        // offers are the flash-bearing AE modes plus torch, which is a
        // separate control entirely.
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
        p("flashModes", JSONArray(aeModes.filter { it != CameraMetadata.CONTROL_AE_MODE_ON && it != CameraMetadata.CONTROL_AE_MODE_OFF }.map(::aeModeName)))
        p("torchAvailable", available)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p("torchStrengthMaxLevel", get { chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) })
            p("torchStrengthDefaultLevel", get { chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) })
        }
    }

    private fun capabilitiesJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        put("raw", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
        put("burstCapture", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE))
        put("manualSensor", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        put(
            "manualPostProcessing",
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
        )
        put(
            "readSensorSettings",
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS),
        )
        put(
            "constrainedHighSpeedVideo",
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO),
        )
        put(
            "privateReprocessing",
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING),
        )
        put(
            "yuvReprocessing",
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING),
        )
        put("depthOutput", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT))
        put("motionTracking", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put(
                "logicalMultiCamera",
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            )
            put("monochrome", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put(
                "ultraHighResolutionSensor",
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR),
            )
        }
        put("rawCapabilityCodes", JSONArray(caps.toList()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val profiles = get { chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) }
            put("dynamicRangeProfiles", JSONArray((profiles?.supportedProfiles ?: emptySet<Long>()).map(::dynamicRangeName)))
            put("hdrProfiles", JSONArray(
                (profiles?.supportedProfiles ?: emptySet<Long>())
                    .filter { it != android.hardware.camera2.params.DynamicRangeProfiles.STANDARD }
                    .map(::dynamicRangeName),
            ))
            put("tenBitOutputSupported", caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT))
        }
    }

    private fun stabilizationJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val video = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        put("oisAvailable", ois.any { it != 0 })
        put("oisModes", JSONArray(ois.toList()))
        // "EIS" in the Camera2 vocabulary is video stabilization mode ON; the
        // PREVIEW_STABILIZATION mode added in API 33 is the stronger one.
        put("eisAvailable", video.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF })
        put("videoStabilizationModes", namesArray(video, ::videoStabilizationName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            put(
                "previewStabilizationSupported",
                video.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION),
            )
        }
    }

    private fun outputFormatsJson(map: StreamConfigurationMap?): JSONObject = JSONObject().apply {
        val formats = get { map?.outputFormats }?.toList() ?: emptyList()
        fun has(format: Int) = formats.contains(format)
        put("jpeg", has(ImageFormat.JPEG))
        put("raw", has(ImageFormat.RAW_SENSOR))
        // DNG is not an output format — it is RAW_SENSOR plus the metadata the
        // RAW capability guarantees, which is what DngCreator needs.
        put("dng", has(ImageFormat.RAW_SENSOR))
        put("yuv420888", has(ImageFormat.YUV_420_888))
        put("nv21", has(ImageFormat.NV21))
        // NV12 has no ImageFormat constant and is never enumerated here: it is
        // an internal/opaque layout reached through PRIVATE surfaces, which is
        // exactly how this app's encoder path consumes it.
        put("nv12", JSONObject.NULL)
        put("nv12Note", "not enumerable; reached via PRIVATE/opaque surfaces")
        put("private", has(ImageFormat.PRIVATE))
        put("heic", has(ImageFormat.HEIC))
        put("heif", has(ImageFormat.HEIC))
        put("depth16", has(ImageFormat.DEPTH16))
        put("y8", has(ImageFormat.Y8))
        put("rawPrivate", has(ImageFormat.RAW_PRIVATE))
        put("raw10", has(ImageFormat.RAW10))
        put("raw12", has(ImageFormat.RAW12))
        put("allFormatCodes", JSONArray(formats.map { imageFormatName(it) }))
    }

    // ──────────────────────────────────────────────────────────────────
    // Device-level
    // ──────────────────────────────────────────────────────────────────

    private fun systemJson(context: Context): JSONObject = JSONObject().apply {
        p("model", Build.MODEL)
        p("manufacturer", Build.MANUFACTURER)
        p("brand", Build.BRAND)
        p("device", Build.DEVICE)
        p("androidVersion", Build.VERSION.RELEASE)
        p("sdkInt", Build.VERSION.SDK_INT)
        p("fingerprint", Build.FINGERPRINT)
        p("soc", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOfNotNull(
                Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN },
                Build.SOC_MODEL.takeIf { it != Build.UNKNOWN },
            ).joinToString(" ").ifEmpty { null }
        } else {
            Build.HARDWARE
        })
        p("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        p("ramTotalMb", memInfo.totalMem / (1024 * 1024))
        p("ramAvailableMb", memInfo.availMem / (1024 * 1024))
        p("openGlEsVersion", get { am?.deviceConfigurationInfo?.glEsVersion })
        val pm = context.packageManager
        p("vulkanHardwareLevel", featureVersion(pm, PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
        p("vulkanHardwareVersion", featureVersion(pm, PackageManager.FEATURE_VULKAN_HARDWARE_VERSION))
        p("vulkanSupported", pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION))
        p("cameraManualSensorFeature", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR))
        p("cameraLevelFullFeature", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_LEVEL_FULL))
    }

    private fun displayJson(context: Context): JSONObject = JSONObject().apply {
        val metrics = context.resources.displayMetrics
        p("widthPx", metrics.widthPixels)
        p("heightPx", metrics.heightPixels)
        p("densityDpi", metrics.densityDpi)
        p("density", metrics.density)
        p("xdpi", metrics.xdpi)
        p("ydpi", metrics.ydpi)
        // Physical size from the real dot pitch, which is the only honest way:
        // densityDpi is a bucketed value, xdpi/ydpi are the measured ones.
        val inchesW = if (metrics.xdpi > 0) metrics.widthPixels / metrics.xdpi else 0f
        val inchesH = if (metrics.ydpi > 0) metrics.heightPixels / metrics.ydpi else 0f
        p("diagonalInches", if (inchesW > 0 && inchesH > 0) {
            kotlin.math.sqrt(inchesW * inchesW + inchesH * inchesH)
        } else null)

        // DisplayManager, not Context.getDisplay(): that one throws
        // UnsupportedOperationException on a non-visual context (an
        // application or service context), and this probe is deliberately
        // callable from anywhere — the instrumentation test that runs it on
        // real hardware passes exactly such a context, and got a report whose
        // refresh rate and HDR support were silently null. The report
        // describes the device, so it must not depend on who asked.
        val display: Display? = get {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        } ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            get { context.display }
        } else {
            @Suppress("DEPRECATION")
            get { (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay }
        }
        p("refreshRateHz", display?.refreshRate)
        p("supportedRefreshRates", JSONArray(
            (get { display?.supportedModes } ?: emptyArray()).map {
                "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}"
            },
        ))
        p("hdrSupported", get { display?.isHdr })
        p("hdrTypes", JSONArray(
            (get { display?.hdrCapabilities?.supportedHdrTypes } ?: IntArray(0)).map(::hdrTypeName),
        ))
        p(
            "summary",
            "${metrics.widthPixels}x${metrics.heightPixels} · ${metrics.densityDpi}dpi · " +
                "${"%.0f".format(display?.refreshRate ?: 0f)}Hz",
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Summary for the always-visible half of the screen
    // ──────────────────────────────────────────────────────────────────

    private fun summarize(
        id: String,
        chars: CameraCharacteristics,
        map: StreamConfigurationMap?,
        facing: String,
        isPhysical: Boolean,
    ): CameraSummary {
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxSize = get { map?.getOutputSizes(ImageFormat.JPEG) }?.maxByOrNull { it.width.toLong() * it.height }
            ?: get { map?.getOutputSizes(ImageFormat.PRIVATE) }?.maxByOrNull { it.width.toLong() * it.height }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

        return CameraSummary(
            id = id,
            facing = facing,
            isPhysical = isPhysical,
            hardwareLevel = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            megapixels = pixelArray?.let { (it.width.toLong() * it.height) / 1_000_000f },
            maxResolution = maxSize?.let { "${it.width}x${it.height}" },
            focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
            apertureF = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            sensorSizeMm = physical?.let { "%.2f×%.2f mm".format(it.width, it.height) },
            pixelPitchUm = pixelPitchUm(physical?.width, pixelArray?.width),
            isoRange = iso?.let { "${it.lower}–${it.upper}" },
            shutterRange = exposure?.let { "${shutterLabel(it.lower)} – ${shutterLabel(it.upper)}" },
            maxFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper },
            hasOis = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0))
                .any { it != 0 },
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            capabilities = buildList {
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) add("MANUAL")
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) add("RAW")
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)) add("BURST")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                ) {
                    add("MULTI")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
                ) {
                    add("UHR")
                }
            },
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Plumbing: nothing below may throw
    // ──────────────────────────────────────────────────────────────────

    /**
     * Runs one characteristic read, turning any failure into null.
     *
     * Not paranoia: `CameraCharacteristics.get` throws on some vendor HALs for
     * keys they half-declare, and a whole diagnostic report is not worth
     * losing to one of them.
     */
    private inline fun <T> get(block: () -> T?): T? = try {
        block()
    } catch (e: Throwable) {
        null
    }

    /**
     * `put` that keeps the key when the value is absent.
     *
     * [JSONObject.put] with a null *removes* the mapping, which would quietly
     * turn "this HAL does not publish a black level" into "this report never
     * looked". The whole point of the diagnostic is telling those two apart,
     * so an absent value is an explicit JSON `null` and the field still
     * appears — on screen as well, since the UI renders from this object.
     */
    private fun JSONObject.p(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    /** Runs one section, turning a failure into a JSON object that says so. */
    private inline fun section(name: String, block: () -> JSONObject): JSONObject = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "section $name failed", e)
        JSONObject().put("error", e.toString())
    }

    private fun characteristics(manager: CameraManager, id: String): CameraCharacteristics? =
        get { manager.getCameraCharacteristics(id) }

    private fun physicalIdsOf(chars: CameraCharacteristics): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            get { chars.physicalCameraIds } ?: emptySet()
        } else {
            emptySet()
        }

    private fun featureVersion(pm: PackageManager, feature: String): Int? =
        pm.systemAvailableFeatures.firstOrNull { it.name == feature }?.version

    private fun sizeJson(size: Size?): JSONObject? =
        size?.let { JSONObject().put("width", it.width).put("height", it.height) }

    private fun sizesArray(sizes: Array<Size>?): JSONArray =
        JSONArray((sizes ?: emptyArray()).map { "${it.width}x${it.height}" })

    private fun namesArray(codes: IntArray?, name: (Int) -> String): JSONArray =
        JSONArray((codes ?: IntArray(0)).map(name))

    /** Sensor width in mm over pixel count, expressed in microns. */
    fun pixelPitchUm(physicalWidthMm: Float?, pixelArrayWidth: Int?): Float? {
        if (physicalWidthMm == null || pixelArrayWidth == null || pixelArrayWidth <= 0) return null
        return physicalWidthMm * 1000f / pixelArrayWidth
    }

    /** `1/60` / `0.5"` / `2"`, matching the manual-exposure control's own formatter. */
    fun shutterLabel(ns: Long): String = com.phonecam.streamer.ExposureScale.formatShutter(ns)

    private fun facingName(value: Int?): String = when (value) {
        CameraMetadata.LENS_FACING_FRONT -> "FRONT"
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    fun hardwareLevelName(value: Int?): String = when (value) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun colorFilterName(value: Int?): String = when (value) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        null -> "UNKNOWN"
        else -> "UNKNOWN($value)"
    }

    private fun aeModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        else -> "MODE_$value"
    }

    private fun afModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF (manual)"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "MODE_$value"
    }

    private fun awbModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "MODE_$value"
    }

    private fun videoStabilizationName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON (EIS)"
        2 -> "PREVIEW_STABILIZATION"
        else -> "MODE_$value"
    }

    private fun dynamicRangeName(profile: Long): String = when (profile) {
        android.hardware.camera2.params.DynamicRangeProfiles.STANDARD -> "STANDARD"
        android.hardware.camera2.params.DynamicRangeProfiles.HLG10 -> "HLG10"
        android.hardware.camera2.params.DynamicRangeProfiles.HDR10 -> "HDR10"
        android.hardware.camera2.params.DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
        android.hardware.camera2.params.DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_OEM"
        android.hardware.camera2.params.DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_OEM"
        else -> "PROFILE_$profile"
    }

    private fun hdrTypeName(value: Int): String = when (value) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
        else -> "TYPE_$value"
    }

    private fun imageFormatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.YUV_422_888 -> "YUV_422_888"
        ImageFormat.YUV_444_888 -> "YUV_444_888"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.NV21 -> "NV21"
        ImageFormat.NV16 -> "NV16"
        ImageFormat.YV12 -> "YV12"
        ImageFormat.HEIC -> "HEIC"
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
        ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
        ImageFormat.Y8 -> "Y8"
        ImageFormat.FLEX_RGB_888 -> "FLEX_RGB_888"
        ImageFormat.FLEX_RGBA_8888 -> "FLEX_RGBA_8888"
        else -> "FORMAT_0x${Integer.toHexString(format)}"
    }
}
