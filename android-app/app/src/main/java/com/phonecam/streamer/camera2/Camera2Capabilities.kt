package com.phonecam.streamer.camera2

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size

private const val TAG = "Camera2Capabilities"

/**
 * What a camera can *actually* record at, as opposed to what the AOSP metadata
 * admits to.
 *
 * On this project's reference device (Galaxy S23 Ultra) the two disagree, and
 * the AOSP tables are the ones that are wrong for video:
 *
 *   CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES     max 30 on every camera id
 *   StreamConfigurationMap 3840x2160           present, minFrameDuration 33.3ms
 *   samsung.android.scaler.availableVideoConfigurations
 *                                              3840x2160 @ 60   <-- the truth
 *
 * Measured on-device (see the Camera2 probe in src/debug): requesting
 * [60,60] as a *session parameter* on camera 0 at 3840x2160 delivers a
 * sustained 59.8fps with zero dropped frames, while the identical code path
 * with [30,30] delivers exactly 30. The AE table describes the default
 * preview pipeline; the HAL selects its video pipeline from the vendor table
 * at session-configuration time, which is why AE_TARGET_FPS_RANGE appears in
 * android.request.availableSessionKeys on this device.
 *
 * Vendor tags are addressed by name through the public
 * CameraCharacteristics.Key(String, Class) constructor — no reflection, no
 * hidden API. Devices without the tag (i.e. everything non-Samsung) fall
 * back to the AOSP tables, so this never *invents* a capability: it only
 * stops under-reporting one the HAL explicitly advertises.
 */
object Camera2Capabilities {

    private const val VENDOR_VIDEO_CONFIGS = "samsung.android.scaler.availableVideoConfigurations"

    /** One row of the vendor video table: a size plus the fps range valid at that size. */
    data class VideoConfig(val width: Int, val height: Int, val minFps: Int, val maxFps: Int) {
        val size: Size get() = Size(width, height)
    }

    fun characteristicsOrNull(context: Context, cameraId: String): CameraCharacteristics? = try {
        context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
    } catch (e: Exception) {
        Log.w(TAG, "characteristics unavailable for camera $cameraId", e)
        null
    }

    /**
     * The vendor table, or null when this device doesn't publish one.
     * Rows are 6 ints: width, height, fpsMin, fpsMax, and two fields whose
     * meaning Samsung doesn't document (they vary per row and are ignored).
     */
    fun vendorVideoConfigs(chars: CameraCharacteristics): List<VideoConfig>? {
        val raw = try {
            chars.get(CameraCharacteristics.Key(VENDOR_VIDEO_CONFIGS, IntArray::class.java))
        } catch (e: Throwable) {
            // A device without the vendor tag descriptor throws rather than
            // returning null — not exceptional, just "not a Samsung".
            Log.i(TAG, "no vendor video config table on this device (${e.javaClass.simpleName})")
            null
        } ?: return null

        return raw.toList().chunked(6)
            .filter { it.size == 6 && it[0] > 0 && it[1] > 0 }
            .map { VideoConfig(it[0], it[1], it[2], it[3]) }
    }

    /**
     * The fps range to hand to SessionConfiguration.setSessionParameters for
     * [size] at [desiredFps], or null when this camera can't do that rate at
     * that size at all.
     *
     * Prefers a fixed range ([60,60] over [15,60]): a fixed range is what
     * pins the sensor to the rate instead of letting AE drop it in low light,
     * and it's what the probe measured 59.8fps with.
     */
    fun sessionFpsRange(chars: CameraCharacteristics, size: Size, desiredFps: Int): Range<Int>? {
        val vendor = vendorVideoConfigs(chars)
        if (vendor != null) {
            val atSize = vendor.filter { it.width == size.width && it.height == size.height }
            if (atSize.isNotEmpty()) {
                atSize.firstOrNull { it.minFps == desiredFps && it.maxFps == desiredFps }
                    ?.let { return Range(it.minFps, it.maxFps) }
                atSize.firstOrNull { it.maxFps == desiredFps }
                    ?.let { return Range(it.minFps, it.maxFps) }
                // The size exists but not at this rate — fall through to the
                // AOSP list rather than requesting something unsupported.
                Log.i(TAG, "vendor table has ${size.width}x${size.height} but not @${desiredFps}fps " +
                    "(max ${atSize.maxOf { it.maxFps }})")
            }
        }
        return aospFpsRange(chars, desiredFps)
    }

    /** Highest fps this camera advertises at [size], across vendor table then AOSP. */
    fun maxFpsFor(chars: CameraCharacteristics, size: Size): Int {
        vendorVideoConfigs(chars)
            ?.filter { it.width == size.width && it.height == size.height }
            ?.maxOfOrNull { it.maxFps }
            ?.let { return it }
        return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxOfOrNull { it.upper } ?: 30
    }

    /**
     * Is [size] configurable as a capture output? The vendor table counts:
     * the probe confirmed a size absent from StreamConfigurationMap still
     * configures successfully when the consumer surface is fixed-size (a
     * MediaCodec input surface or our SurfaceTexture), because the framework
     * only rounds sizes for flexible consumers.
     */
    fun isSizeSupported(chars: CameraCharacteristics, size: Size): Boolean {
        vendorVideoConfigs(chars)?.let { configs ->
            if (configs.any { it.width == size.width && it.height == size.height }) return true
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(ImageFormat.PRIVATE)?.any { it == size } == true
    }

    /** Largest AOSP-advertised preview size no bigger than [max] — used for the viewfinder stream. */
    fun previewSizeFor(chars: CameraCharacteristics, max: Size): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val candidates = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?: return Size(1280, 720)
        return candidates
            .filter { it.width <= max.width && it.height <= max.height }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: candidates.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }

    /** Same "closest advertised range" rule DeviceCapabilities uses, for non-Samsung devices. */
    private fun aospFpsRange(chars: CameraCharacteristics, desiredFps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
            ?: return null
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

    /**
     * The physical sub-camera that can actually produce [size], or null when the
     * logical camera can do it itself.
     *
     * 8K only exists on one sensor: measured on an S23 Ultra, `7680x4320` is
     * absent from logical camera 0's own table but present on physical id 5 (the
     * 200MP main sensor). Top-level id 56 also lists it but is not enumerable —
     * `openCamera("56")` throws `IllegalArgumentException: Unknown camera ID` —
     * so routing an output to the physical id is the only route that works.
     */
    fun physicalIdFor(context: Context, cameraId: String, size: Size): String? {
        val logical = characteristicsOrNull(context, cameraId) ?: return null
        if (vendorVideoConfigs(logical)?.any { it.width == size.width && it.height == size.height } == true) {
            return null   // the logical camera handles it; no routing needed
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val physicalIds = try { logical.physicalCameraIds } catch (e: Exception) { emptySet<String>() }
        for (physicalId in physicalIds) {
            val chars = characteristicsOrNull(context, physicalId) ?: continue
            if (vendorVideoConfigs(chars)?.any { it.width == size.width && it.height == size.height } == true) {
                Log.i(TAG, "${size.width}x${size.height} lives on physical camera $physicalId")
                return physicalId
            }
        }
        return null
    }

    /** Human-readable capability summary for diagnostics/logs. */
    fun describe(chars: CameraCharacteristics, size: Size): String {
        val vendor = vendorVideoConfigs(chars)
        val source = if (vendor == null) "AOSP only" else "vendor table (${vendor.size} rows)"
        return "${size.width}x${size.height} max ${maxFpsFor(chars, size)}fps [$source]"
    }
}
