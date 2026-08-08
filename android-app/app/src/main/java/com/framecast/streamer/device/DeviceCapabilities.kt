package com.framecast.streamer.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.util.Range
import android.util.Size
import kotlin.math.roundToInt

private const val TAG = "DeviceCapabilities"

/**
 * Queries the phone's hardware and returns what it can actually do.
 * Used by Settings UI to show device info and warn about incompatible choices.
 */
object DeviceCapabilities {

    data class CameraInfo(
        val id: String,
        val facing: String,             // "back" / "front" / "external"
        val lens: String,               // "wide" / "ultra-wide" / "telephoto" / "supertelephoto" / "unknown"
        val focalLengthMm: Float?,
        val sensorMegapixels: Float,
        val maxResolution: Size,
        val supportsHdr: Boolean,
        val supportsStabilization: Boolean,
        val supportedResolutions: List<ResolutionSupport>,
        val zoomFactor: Float? = null,  // 0.6 / 1 / 3 / 10 — known only when specs come from the model database
    )

    data class ResolutionSupport(
        val size: Size,                 // e.g. 1920×1080
        val label: String,              // "1080p"
        val maxFps: Int,                // e.g. 60
    )

    data class DeviceInfo(
        val model: String,
        val manufacturer: String,
        val androidVersion: String,
        val sdkInt: Int,
        val ramTotalMb: Long,
        val ramAvailableMb: Long,
        val storageTotalGb: Float,
        val storageFreeGb: Float,
        val cameras: List<CameraInfo>,
        val supportedCodecs: List<String>,   // "H.264", "H.265", "AV1"
        val notes: List<String> = emptyList(),  // caveats, e.g. "RAM estimated from device model"
    )

    /** Return a target list of resolution buckets we care about. */
    private val targetResolutions = listOf(
        Size(640, 360)   to "360p",
        Size(854, 480)   to "480p",
        Size(1280, 720)  to "720p",
        Size(1920, 1080) to "1080p",
        Size(2560, 1440) to "1440p",
        Size(3840, 2160) to "2160p",
        Size(7680, 4320) to "4320p",
    )

    fun probe(context: Context): DeviceInfo {
        val measuredRamMb = ramTotal(context)
        val ramValid = measuredRamMb > 256

        // For known models the database is authoritative over the runtime probe:
        // OEM HALs hide physical lenses behind the logical camera and misreport
        // HFR modes, so probing alone shows one camera at 30fps on a phone that
        // actually has four lenses and shoots 8K30/1080p240 (see the database doc).
        val known = DeviceModelDatabase.lookup(Build.MANUFACTURER, Build.MODEL)

        var ramMb = measuredRamMb
        var cameras: List<CameraInfo>
        val notes = mutableListOf<String>()

        if (known != null) {
            cameras = camerasFromSpec(known)
            notes += context.getString(com.framecast.streamer.R.string.device_note_camera_specs_db)
            if (!ramValid) ramMb = known.ramGb * 1024L
        } else {
            cameras = probeCameras(context)
            val camerasValid = cameras.any { it.supportedResolutions.isNotEmpty() }
            if (!ramValid || !camerasValid) {
                val fallback = DeviceModelDatabase.genericFallback(
                    if (ramValid) (measuredRamMb / 1024).toInt().coerceAtLeast(1) else 4,
                )
                if (!ramValid) {
                    ramMb = fallback.ramGb * 1024L
                    notes += context.getString(com.framecast.streamer.R.string.device_note_ram_estimated)
                }
                if (!camerasValid) {
                    cameras = camerasFromSpec(fallback)
                    notes += context.getString(com.framecast.streamer.R.string.device_note_camera_limits_estimated)
                }
            }
        }

        return DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            ramTotalMb = ramMb,
            ramAvailableMb = ramAvailable(context),
            storageTotalGb = storageTotalGb(),
            storageFreeGb = storageFreeGb(),
            cameras = cameras,
            supportedCodecs = probeCodecs(),
            notes = notes,
        )
    }

    private fun sizeForLabel(label: String): Size? =
        targetResolutions.firstOrNull { it.second == label }?.first

    private fun camerasFromSpec(known: DeviceModelDatabase.KnownSpec): List<CameraInfo> =
        known.cameras.mapIndexed { index, spec ->
            val resolutions = spec.fpsByResolution.mapNotNull { (label, fps) ->
                sizeForLabel(label)?.let { ResolutionSupport(size = it, label = label, maxFps = fps) }
            }.sortedBy { it.size.width.toLong() * it.size.height }
            val maxResolution = resolutions.lastOrNull()?.size ?: Size(1920, 1080)
            val facing = if (spec.lens == "front") "front" else "back"

            CameraInfo(
                id = index.toString(),
                facing = facing,
                lens = if (spec.lens == "front") "wide" else spec.lens,
                focalLengthMm = null,
                sensorMegapixels = spec.megapixels,
                maxResolution = maxResolution,
                supportsHdr = spec.supportsHdr,
                supportsStabilization = spec.lens == "wide",
                supportedResolutions = resolutions,
                zoomFactor = spec.zoomFactor,
            )
        }

    private val ramTiersGb = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32, 64)

    /**
     * ActivityManager's totalMem always reads somewhat below the marketed RAM figure
     * (memory reserved for radio/hardware isn't counted) — e.g. an "8GB" phone often
     * reports ~7.1-7.5GB. Snap to the nearest marketed tier so Settings shows what's
     * printed on the box instead of a confusing raw number.
     */
    fun formatRamGb(ramTotalMb: Long): String {
        val gb = ramTotalMb / 1024.0
        val nearestTier = ramTiersGb.minByOrNull { kotlin.math.abs(it - gb) } ?: gb.roundToInt()
        return if (kotlin.math.abs(nearestTier - gb) <= nearestTier * 0.15) {
            "$nearestTier GB"
        } else {
            "%.1f GB".format(gb)
        }
    }

    private fun ramTotal(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun ramAvailable(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun storageTotalGb(): Float {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.blockCountLong * stat.blockSizeLong) / 1_073_741_824f
    }

    private fun storageFreeGb(): Float {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / 1_073_741_824f
    }

    private fun probeCameras(context: Context): List<CameraInfo> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            mgr.cameraIdList.mapNotNull { id ->
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    buildCameraInfo(id, c)
                } catch (e: Exception) {
                    Log.w(TAG, "camera $id probe failed", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot list cameras", e)
            emptyList()
        }
    }

    private fun buildCameraInfo(id: String, c: CameraCharacteristics): CameraInfo {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focal = focalLengths?.firstOrNull()
        val lens = classifyLens(focal, facing)

        val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val megapixels = sensorSize?.let { (it.width.toLong() * it.height) / 1_000_000f } ?: 0f

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val absoluteMaxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30

        val supported = probeResolutions(map, absoluteMaxFps)
        val maxResolution = supported.maxByOrNull { it.size.width.toLong() * it.size.height }?.size
            ?: Size(1920, 1080)

        // REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT (18, API 33+) — the
        // previous constant here (15) was OFFLINE_PROCESSING, i.e. HDR support was
        // being detected against an unrelated capability.
        val hdr = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.any { it == 18 } ?: false

        val stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.any { it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF }
            ?: false

        return CameraInfo(
            id = id,
            facing = facing,
            lens = lens,
            focalLengthMm = focal,
            sensorMegapixels = megapixels,
            maxResolution = maxResolution,
            supportsHdr = hdr,
            supportsStabilization = stab,
            supportedResolutions = supported,
        )
    }

    private fun classifyLens(focalMm: Float?, facing: String): String {
        if (facing == "front") return "front"
        if (focalMm == null) return "wide"
        return when {
            focalMm < 3.0f -> "ultra-wide"
            // Periscope/folded zoom lenses (e.g. 10x on Ultra-tier flagships) run a
            // noticeably longer physical focal length than a standard 2-3x telephoto.
            focalMm > 9.0f -> "supertelephoto"
            focalMm > 6.0f -> "telephoto"
            else -> "wide"
        }
    }

    private fun probeResolutions(map: StreamConfigurationMap?, absoluteMaxFps: Int): List<ResolutionSupport> {
        if (map == null) return emptyList()
        val supportedSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toSet() ?: emptySet()
        val highSpeedSizes = try {
            map.highSpeedVideoSizes?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        return targetResolutions.mapNotNull { (size, label) ->
            val actualSize = supportedSizes.firstOrNull { it.width == size.width && it.height == size.height }
                ?: supportedSizes.firstOrNull { it.width >= size.width && it.height >= size.height &&
                    (it.width * it.height) < (size.width * size.height * 1.5) }
                ?: return@mapNotNull null

            val minDurationNs = map.getOutputMinFrameDuration(
                android.graphics.ImageFormat.YUV_420_888,
                actualSize,
            )
            val fpsFromDuration = if (minDurationNs > 0) (1_000_000_000.0 / minDurationNs).roundToInt() else 30
            val regularMaxFps = minOf(fpsFromDuration, absoluteMaxFps)

            // Slow-motion / high-frame-rate capture (120fps, 240fps) lives in a separate
            // capability array from CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, which rarely
            // lists anything above ~30-60. Without checking it too, every resolution was
            // getting silently clamped to that low ceiling even when the sensor supports
            // far more at that size (e.g. 1080p120 on many recent flagships).
            val highSpeedMaxFps = if (actualSize in highSpeedSizes) {
                try {
                    map.getHighSpeedVideoFpsRangesFor(actualSize)?.maxOfOrNull { it.upper } ?: 0
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }

            val maxFps = maxOf(regularMaxFps, highSpeedMaxFps).coerceAtMost(240)

            ResolutionSupport(size = size, label = label, maxFps = maxFps)
        }
    }

    private fun probeCodecs(): List<String> {
        val codecs = mutableSetOf<String>()
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "video/avc" -> codecs.add("H.264")
                        "video/hevc" -> codecs.add("H.265")
                        "video/av01" -> codecs.add("AV1")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "codec probe failed", e)
        }
        return codecs.toList().sorted()
    }

    /** Given user's picked resolution+fps, is it supported by the selected camera? */
    fun isCombinationSupported(
        camera: CameraInfo?,
        resolutionLabel: String,
        fps: Int,
    ): Boolean {
        val cam = camera ?: return false
        val support = cam.supportedResolutions.firstOrNull { it.label == resolutionLabel }
            ?: return false
        return fps <= support.maxFps
    }

    fun findBackCamera(info: DeviceInfo): CameraInfo? =
        info.cameras.firstOrNull { it.facing == "back" && it.lens == "wide" }
            ?: info.cameras.firstOrNull { it.facing == "back" }

    /** True when any camera on the device can capture 10-bit HDR (DB first, then live query). */
    fun supports10BitHdr(context: Context): Boolean {
        DeviceModelDatabase.lookup(Build.MANUFACTURER, Build.MODEL)?.let { spec ->
            return spec.cameras.any { it.supportsHdr }
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            mgr.cameraIdList.any { id ->
                mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.any { it == 18 } == true  // DYNAMIC_RANGE_TEN_BIT
            }
        } catch (e: Exception) {
            false
        }
    }

    /** A resolved lens target: physicalId is null when it IS the top-level id (no Camera2Interop routing needed). */
    data class LensTarget(val cameraId: String, val physicalId: String?)

    /**
     * Resolves the camera2 id for a specific facing+lens combo (e.g. back+"telephoto"),
     * so the camera pipeline can be pointed at that exact physical lens instead of
     * whatever the logical "back" camera defaults to (usually the wide sensor).
     * Returns null if this device has no such lens.
     *
     * Two-step search:
     *  1. Top-level ids in cameraIdList — some devices expose each lens as its own id.
     *  2. Physical sub-ids of a logical multi-camera (getPhysicalCameraIds) — most
     *     Samsung flagships hide tele/periscope lenses here instead. These ids aren't
     *     independently bindable via CameraSelector; the caller must apply them with
     *     Camera2Interop.Extender.setPhysicalCameraId() on the logical camera's UseCase.
     */
    fun findLensTarget(context: Context, facing: String, lens: String): LensTarget? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            for (id in mgr.cameraIdList) {
                val c = mgr.getCameraCharacteristics(id)
                val camFacing = facingOf(c)
                if (camFacing != facing) continue

                // Step 1: does this top-level id itself match the requested lens?
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                if (classifyLens(focal, camFacing) == lens) {
                    return LensTarget(cameraId = id, physicalId = null)
                }

                // Step 2: search physical sub-ids hidden behind this logical camera.
                if (Build.VERSION.SDK_INT >= 28) {
                    val physicalIds = try { c.physicalCameraIds } catch (e: Exception) { emptySet() }
                    for (physId in physicalIds) {
                        try {
                            val physChar = mgr.getCameraCharacteristics(physId)
                            val physFocal = physChar.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                            if (classifyLens(physFocal, camFacing) == lens) {
                                return LensTarget(cameraId = id, physicalId = physId)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "physical camera $physId probe failed", e)
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findLensTarget($facing, $lens) failed", e)
            null
        }
    }

    /**
     * For a device with a known camera database entry, resolves EVERY back lens by
     * focal-length ORDER instead of fixed mm thresholds.
     *
     * classifyLens's cutoffs (ultra-wide <3mm, wide 3-6mm, telephoto 6-9mm, super->9mm)
     * are guesses that don't hold for every sensor: on the S23 Ultra the 200MP main
     * sensor's physical focal length runs close enough to the "telephoto" band that the
     * REAL 3x tele lens's own focal length either collides with it or the periscope's,
     * so threshold matching found the unambiguously-longest periscope (10x) fine but
     * missed the 3x tele. Sorting candidates by focal length and zipping them against
     * the database's specs (also sorted by zoomFactor) sidesteps the guesswork
     * entirely — shortest-to-longest always means ultra-wide→wide→tele→supertele,
     * regardless of the exact mm values this particular sensor stack reports.
     *
     * That zip is index-based, so the candidate list must have exactly one entry per
     * real lens — a logical camera id's own focal length must NOT be added alongside
     * its physical sub-ids (see the candidate-gathering loop below), or the extra
     * duplicate shifts every lens after it by one position: on the S23/S24 Ultra this
     * was previously pairing "telephoto" with the wide sensor's candidate and
     * "supertelephoto" with the real 3x tele's candidate — the 10x chip silently
     * rebinding to the 3x lens at native (uncropped) zoom, and the 3x chip only ever
     * showing a digitally-cropped wide feed.
     */
    fun findLensTargetsForKnownDevice(context: Context, spec: DeviceModelDatabase.KnownSpec): Map<String, LensTarget> {
        data class Candidate(val cameraId: String, val physicalId: String?, val focal: Float)

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val candidates = mutableListOf<Candidate>()
        try {
            for (id in mgr.cameraIdList) {
                val c = mgr.getCameraCharacteristics(id)
                if (facingOf(c) != "back") continue

                val physicalIds = if (Build.VERSION.SDK_INT >= 28) {
                    try { c.physicalCameraIds } catch (e: Exception) { emptySet() }
                } else {
                    emptySet()
                }

                if (physicalIds.isEmpty()) {
                    // No hidden sub-cameras behind this id: it IS a distinct physical lens.
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { focal ->
                        candidates.add(Candidate(id, null, focal))
                    }
                } else {
                    // Logical multi-camera (e.g. Samsung Ultra back camera): its own
                    // top-level focal length just mirrors whichever physical sub-camera is
                    // currently active (usually wide) — counting it AND the sub-ids below as
                    // separate candidates double-counts that lens and shifts every candidate
                    // after it by one position once sorted, which is what made the 3x/10x
                    // chips resolve to the wrong physical lens. Only the sub-ids are real,
                    // independently-selectable lenses.
                    for (physId in physicalIds) {
                        try {
                            mgr.getCameraCharacteristics(physId)
                                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                ?.firstOrNull()?.let { physFocal ->
                                    candidates.add(Candidate(id, physId, physFocal))
                                }
                        } catch (e: Exception) {
                            Log.w(TAG, "physical camera $physId probe failed", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findLensTargetsForKnownDevice failed", e)
            return emptyMap()
        }

        val sortedCandidates = candidates.distinctBy { it.physicalId ?: it.cameraId }.sortedBy { it.focal }
        val sortedSpecs = spec.cameras.filter { it.lens != "front" }.sortedBy { it.zoomFactor }

        val result = mutableMapOf<String, LensTarget>()
        for (i in sortedSpecs.indices) {
            val candidate = sortedCandidates.getOrNull(i) ?: continue
            result[sortedSpecs[i].lens] = LensTarget(candidate.cameraId, candidate.physicalId)
        }
        return result
    }

    /** First top-level camera id matching a facing — a reasonable stand-in when no specific lens/physical id was resolved. */
    fun defaultCameraId(context: Context, facing: String): String? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            mgr.cameraIdList.firstOrNull { id -> facingOf(mgr.getCameraCharacteristics(id)) == facing }
        } catch (e: Exception) {
            null
        }
    }

    /** AE target-fps ranges the given camera (or physical sub-camera) actually advertises. */
    fun availableFpsRanges(context: Context, cameraId: String, physicalId: String?): List<Range<Int>> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            mgr.getCameraCharacteristics(physicalId ?: cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Picks the advertised range that best matches a desired fps: one that contains it
     * if possible (narrowest such range, for the most precise/stable framerate), else
     * the range whose bound is numerically closest.
     */
    fun closestFpsRange(ranges: List<Range<Int>>, desiredFps: Int): Range<Int>? {
        if (ranges.isEmpty()) return null
        val containing = ranges.filter { desiredFps in it.lower..it.upper }
        val pool = if (containing.isNotEmpty()) containing else ranges
        return pool.minByOrNull { range ->
            val distance = when {
                desiredFps < range.lower -> range.lower - desiredFps
                desiredFps > range.upper -> desiredFps - range.upper
                else -> 0
            }
            distance * 1000 + (range.upper - range.lower)
        }
    }

    private fun facingOf(c: CameraCharacteristics): String = when (c.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    @Deprecated("Use findLensTarget — this only checks top-level ids and misses hidden physical sub-cameras")
    fun findCameraIdForLens(context: Context, facing: String, lens: String): String? =
        findLensTarget(context, facing, lens)?.let { if (it.physicalId == null) it.cameraId else null }
}
