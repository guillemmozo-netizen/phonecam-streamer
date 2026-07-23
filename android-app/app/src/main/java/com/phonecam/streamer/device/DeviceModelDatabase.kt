package com.phonecam.streamer.device

/**
 * Per-camera specs for popular devices, keyed by "manufacturer model-prefix"
 * (both from android.os.Build, lowercased). Prefix matching absorbs regional
 * variants: "samsung sm-s918" matches SM-S918B (global), SM-S918U (US),
 * SM-S918N (Korea), etc.
 *
 * For a known model this database is AUTHORITATIVE over the Camera2 runtime
 * probe for what the Settings device card and zoom chips show: OEM HALs
 * routinely hide physical lenses behind the logical camera, misreport
 * high-frame-rate modes, or omit stream configs entirely, so probing alone
 * shows "back · wide 30fps" on a phone that actually has four lenses and
 * shoots 8K30/1080p240. Actual lens switching still goes through the live
 * Camera2 query in [DeviceCapabilities.findLensTarget], which also finds
 * lenses Samsung hides as physical sub-ids of the logical back camera
 * (unreachable via a plain cameraIdList scan) and routes to them with
 * Camera2Interop.setPhysicalCameraId.
 *
 * Unknown devices fall back to the runtime probe, then [genericFallback].
 */
object DeviceModelDatabase {

    data class CameraSpec(
        val lens: String,                       // "ultra-wide" / "wide" / "telephoto" / "supertelephoto" / "front"
        val zoomFactor: Float,                  // 0.6, 1.0, 3.0, 10.0 — as shown on the zoom chips
        val megapixels: Float,
        val fpsByResolution: Map<String, Int>,  // resolution label -> max fps at that resolution
        val supportsHdr: Boolean = false,       // 10-bit HDR capture on this lens
        // For the rare device where no distinct id (top-level or hidden physical sub-id)
        // exists for a lens at all, marking a spec digital-zoom-only skips the doomed
        // physical-lookup and just crops the currently-bound sensor with setZoomRatio
        // instead of a no-op. (The S23/S24 Ultra's 3x tele used to need this — it turned
        // out to have a real physical sub-id all along, see findLensTargetsForKnownDevice.)
        val isDigitalZoomOnly: Boolean = false,
    )

    data class KnownSpec(
        val ramGb: Int,
        val cameras: List<CameraSpec>,
    )

    // Common fps tables, low→high resolution
    private val UHD60 = mapOf(
        "360p" to 120, "480p" to 120, "720p" to 120,
        "1080p" to 60, "1440p" to 60, "2160p" to 60,
    )
    private val UHD60_HFR = mapOf(          // flagship wide sensor: slow-mo HFR + 4K60
        "360p" to 240, "480p" to 240, "720p" to 240,
        "1080p" to 240, "1440p" to 60, "2160p" to 60,
    )
    private val UHD8K30_HFR = UHD60_HFR + mapOf("4320p" to 30)   // + 8K30
    private val FHD60 = mapOf(
        "360p" to 60, "480p" to 60, "720p" to 60, "1080p" to 60,
    )

    private fun front(mp: Float, fps: Map<String, Int> = UHD60) =
        CameraSpec("front", 1f, mp, fps)

    private val specs: Map<String, KnownSpec> = mapOf(
        // ── Samsung ──
        // Galaxy S23 Ultra: 0.6x UW · 1x 200MP (8K30, 1080p240) · 3x tele · 10x periscope.
        // 3x and 10x are both hidden as physical sub-ids of the logical back camera on
        // this device — findLensTargetsForKnownDevice resolves them via getPhysicalCameraIds()
        // (previously mis-resolved to the wide sensor because the logical id's own focal
        // length was double-counted as a candidate — fixed in DeviceCapabilities.kt).
        "samsung sm-s918" to KnownSpec(
            ramGb = 12,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 12f, UHD60),
                CameraSpec("wide", 1f, 200f, UHD8K30_HFR, supportsHdr = true),
                CameraSpec("telephoto", 3f, 10f, UHD60),
                CameraSpec("supertelephoto", 10f, 10f, UHD60),
                front(12f),
            ),
        ),
        // Galaxy S24 Ultra: same 4-lens logical-camera layout as the S23 Ultra above
        "samsung sm-s928" to KnownSpec(
            ramGb = 12,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 12f, UHD60),
                CameraSpec("wide", 1f, 200f, UHD8K30_HFR, supportsHdr = true),
                CameraSpec("telephoto", 3f, 10f, UHD60),
                CameraSpec("supertelephoto", 5f, 50f, UHD60),
                front(12f),
            ),
        ),
        // Galaxy S23 / S24 base
        "samsung sm-s911" to KnownSpec(
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 12f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD8K30_HFR, supportsHdr = true),
                CameraSpec("telephoto", 3f, 10f, UHD60),
                front(12f),
            ),
        ),
        "samsung sm-s921" to KnownSpec(
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 12f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD8K30_HFR, supportsHdr = true),
                CameraSpec("telephoto", 3f, 10f, UHD60),
                front(12f),
            ),
        ),
        // Galaxy A55: no telephoto
        "samsung sm-a556" to KnownSpec(
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 12f, FHD60),
                CameraSpec("wide", 1f, 50f, UHD60),
                front(32f, FHD60),
            ),
        ),

        // ── Google ──
        "google pixel 8 pro" to KnownSpec(
            ramGb = 12,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.5f, 48f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD60_HFR, supportsHdr = true),
                CameraSpec("telephoto", 5f, 48f, UHD60),
                front(10.5f),
            ),
        ),
        "google pixel 8" to KnownSpec(
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.5f, 12f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD60_HFR, supportsHdr = true),
                front(10.5f),
            ),
        ),
        "google pixel 7" to KnownSpec(
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.5f, 12f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD60),
                front(10.8f),
            ),
        ),
        "google pixel 6a" to KnownSpec(
            ramGb = 6,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.5f, 12f, FHD60),
                CameraSpec("wide", 1f, 12.2f, UHD60),
                front(8f, FHD60),
            ),
        ),

        // ── Xiaomi ──
        "xiaomi 2201116" to KnownSpec(   // Xiaomi 12 Pro
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 50f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD8K30_HFR, supportsHdr = true),
                CameraSpec("telephoto", 2f, 50f, UHD60),
                front(32f),
            ),
        ),
        "xiaomi m2101k6" to KnownSpec(   // Redmi Note 10
            ramGb = 6,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 8f, FHD60),
                CameraSpec("wide", 1f, 48f, mapOf("360p" to 120, "480p" to 120, "720p" to 60, "1080p" to 60, "2160p" to 30)),
                front(13f, FHD60),
            ),
        ),

        // ── OnePlus ──
        "oneplus cph2449" to KnownSpec(  // OnePlus 11 (8K24)
            ramGb = 8,
            cameras = listOf(
                CameraSpec("ultra-wide", 0.6f, 48f, UHD60),
                CameraSpec("wide", 1f, 50f, UHD60_HFR + mapOf("4320p" to 24)),
                CameraSpec("telephoto", 2f, 32f, UHD60),
                front(16f),
            ),
        ),
    )

    fun lookup(manufacturer: String, model: String): KnownSpec? {
        val key = "${manufacturer.trim().lowercase()} ${model.trim().lowercase()}"
        return specs.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    /** Conservative guess for an unlisted device whose probe also failed, by RAM tier. */
    fun genericFallback(ramGb: Int): KnownSpec = when {
        ramGb >= 8 -> KnownSpec(
            ramGb,
            listOf(
                CameraSpec("ultra-wide", 0.6f, 0f, FHD60),
                CameraSpec("wide", 1f, 0f, UHD60 - "1440p" + mapOf("1440p" to 30, "2160p" to 30)),
                CameraSpec("telephoto", 2f, 0f, FHD60),
                front(0f, FHD60),
            ),
        )
        ramGb >= 6 -> KnownSpec(
            ramGb,
            listOf(
                CameraSpec("ultra-wide", 0.6f, 0f, FHD60),
                CameraSpec("wide", 1f, 0f, FHD60),
                front(0f, FHD60),
            ),
        )
        else -> KnownSpec(
            ramGb,
            listOf(
                CameraSpec("wide", 1f, 0f, mapOf("360p" to 30, "480p" to 30, "720p" to 30, "1080p" to 30)),
                front(0f, mapOf("360p" to 30, "480p" to 30, "720p" to 30)),
            ),
        )
    }
}
