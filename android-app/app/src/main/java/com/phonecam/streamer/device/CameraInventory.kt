package com.phonecam.streamer.device

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CameraInventory"

/**
 * The single source of truth for what cameras a phone has, what each one is,
 * and what the zoom control should offer — derived entirely from Camera2
 * characteristics.
 *
 * No model table, no manufacturer list, no marketing names: none of those
 * generalise to a device nobody has held, and this has to be right on one.
 * Where the hardware does not say, the answer is `UNKNOWN` with the reasoning
 * attached, never a plausible guess — an inventory that is confidently wrong
 * is worse than one that admits a gap.
 *
 * ## Four problems, all measured rather than assumed
 *
 * **1. Phones hide cameras.** `getCameraIdList()` is not the list of cameras,
 * it is the list the OEM layer chose to show. On a Redmi Note 11S (MIUI)
 * `dumpsys media.camera` reports six HAL devices and `getCameraIdList()`
 * returns `[0, 1]`. On a Galaxy S23 Ultra (One UI) the ultra-wide and both
 * teles exist only as physical sub-ids of a logical camera.
 *
 * **2. Focal length alone means nothing.** Field of view is focal length over
 * sensor size. On that Redmi three of four back cameras report under 3 mm and
 * exactly one is an ultra-wide:
 *
 *     f=1.650  sensor Ø4.67mm  ->  15.3 mm equivalent   ultra-wide
 *     f=1.915  sensor Ø3.51mm  ->  23.6 mm equivalent   macro (frames like the main)
 *     f=2.000  sensor Ø2.01mm  ->  43.0 mm equivalent   depth helper
 *
 * **3. One lens can wear several ids.** Measured on the S23 Ultra: ids `0` and
 * `5` report the same focal length on the same sensor, and id `2` arrives
 * both from the id list and as a physical sub-camera. Left alone that offers
 * the same lens twice and counts it twice in the zoom range.
 *
 * **4. Detected is not usable.** A camera can be enumerated and still refuse
 * to open — physical sub-cameras cannot be opened directly at all, they are
 * reached by binding the logical parent and calling `setPhysicalCameraId`.
 *
 * ## Passive by construction
 *
 * [probe] never opens a camera. Opening is a side effect: it can evict
 * whatever holds the device — including this app's own live stream — and
 * costs hundreds of milliseconds each. [verifyOpenability] is the separate,
 * opt-in operation, and must not run while a session is active.
 */
object CameraInventory {

    /** Diagonal of a 36x24 mm frame — what every "equivalent" is measured against. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.267f

    /** How a camera was found. A camera may be found by more than one route. */
    enum class DiscoverySource { ID_LIST, PHYSICAL_SUB_CAMERA, HIDDEN_ID_SCAN }

    /** Whether the camera can actually be opened. UNKNOWN is a real answer. */
    enum class Openability { OPENABLE, NOT_OPENABLE, UNKNOWN }

    /**
     * How much the classification can be trusted.
     *
     * - [MEASURED]  — the hardware published the deciding number.
     * - [INFERRED]  — deduced from a declared capability or a hard API fact.
     * - [HEURISTIC] — a judgement from several weak signals, which is the best
     *   available when a phone flags nothing.
     */
    enum class Confidence { MEASURED, INFERRED, HEURISTIC }

    enum class LensRole {
        ULTRA_WIDE, WIDE, TELEPHOTO, SUPER_TELEPHOTO, PERISCOPE,
        MACRO, DEPTH,
        SELFIE, SELFIE_ULTRA_WIDE,
        UNKNOWN,
        ;

        /** True for lenses a person would frame a shot with. */
        val isPhotographic: Boolean
            get() = this in setOf(
                ULTRA_WIDE, WIDE, TELEPHOTO, SUPER_TELEPHOTO, PERISCOPE, SELFIE, SELFIE_ULTRA_WIDE,
            )

        /** The mark shown inside a zoom chip; null for roles that get no chip. */
        val chipLabel: String?
            get() = when (this) {
                ULTRA_WIDE -> "UW"
                WIDE -> "W"
                TELEPHOTO -> "T"
                SUPER_TELEPHOTO, PERISCOPE -> "ST"
                else -> null
            }
    }

    data class CameraDescriptor(
        /** The id the app can bind to. Duplicates collapse into this one. */
        val id: String,
        /** Other ids that turned out to be this same physical lens. */
        val duplicateIds: List<String> = emptyList(),
        /** Every route that found it — a camera can arrive by more than one. */
        val sources: Set<DiscoverySource>,
        val physicalParentId: String?,
        val facing: String,
        /**
         * Largest colour output, as plain ints rather than `android.util.Size`.
         * Size is a framework stub on the JVM: it constructs but every getter
         * throws, which would make this whole classifier untestable without
         * mocking the platform.
         */
        val maxWidth: Int,
        val maxHeight: Int,
        val megapixels: Float,
        val sensorWidthMm: Float?,
        val sensorHeightMm: Float?,
        val sensorDiagonalMm: Float?,
        val focalLengthMm: Float?,
        val equivalentFocalMm: Float?,
        val apertureF: Float?,
        val minFocusDistanceDiopters: Float?,
        val maxDigitalZoom: Float?,
        /**
         * `CONTROL_ZOOM_RATIO_RANGE` (API 30+), the modern zoom API.
         *
         * A different quantity from [maxDigitalZoom], not a newer spelling of
         * it: on a logical camera the range spans the whole *optical* sweep the
         * device can switch lenses across, so a lower bound below 1.0 means a
         * wider lens is reachable by zooming rather than by opening another id.
         * That is Google's sanctioned route to an ultra-wide, and on phones
         * that hide the ultra-wide from the id list it is the only safe one.
         * Null below API 30 and on devices that never implemented it.
         */
        val zoomRatioRange: Pair<Float, Float>? = null,
        val capabilities: List<String>,
        val hasDepthOutput: Boolean,
        val producesImages: Boolean,
        var role: LensRole = LensRole.UNKNOWN,
        var confidence: Confidence = Confidence.HEURISTIC,
        /** Exactly why the role was chosen, one fact per entry. */
        var evidence: List<String> = emptyList(),
        var zoomRatio: Float? = null,
        var openability: Openability = Openability.UNKNOWN,
        var openabilityReason: String = "",
    ) {
        /** Closest focusing distance in centimetres; null when not published. */
        val minFocusDistanceCm: Float?
            get() = minFocusDistanceDiopters?.takeIf { it > 0f }?.let { 100f / it }
    }

    /**
     * One value in the zoom control. [label] is set only for real lenses —
     * a generated digital step is not a lens and must not claim to be one.
     */
    data class ZoomChip(
        val ratio: Float,
        val label: String?,
        val isPhysicalLens: Boolean,
        val cameraId: String?,
    )

    data class Inventory(
        val brand: String,
        val model: String,
        val cameras: List<CameraDescriptor>,
        val opticalZoomMin: Float,
        val opticalZoomMax: Float,
        val digitalZoomMax: Float,
        val hybridZoomMax: Float,
        val zoomChips: List<ZoomChip>,
        val notes: List<String>,
    ) {
        /** The lenses worth offering. Duplicates are already collapsed away. */
        val lenses: List<CameraDescriptor>
            get() = cameras.filter { it.role.isPhotographic }
    }

    // ──────────────────────────────────────────────────────────────────
    // Thresholds
    // ──────────────────────────────────────────────────────────────────

    private const val ULTRA_WIDE_MAX_MM = 18f
    private const val WIDE_MAX_MM = 35f
    private const val SUPER_TELEPHOTO_MIN_MM = 120f

    /**
     * A lens this long cannot fit across a phone's thickness, so its light
     * path is folded — which is what "periscope" names. Both tests are
     * required: the equivalent alone would also catch a long lens sitting on
     * a very small sensor next to an equally long main.
     */
    private const val PERISCOPE_MIN_MM = 100f
    private const val PERISCOPE_MIN_ZOOM = 4f

    private const val AUXILIARY_MAX_MEGAPIXELS = 5f
    private const val AUXILIARY_MAX_SENSOR_DIAGONAL_MM = 4.0f
    private const val MACRO_FOV_MATCH_TOLERANCE = 0.25f

    /** Diopters are 1/metres, so larger is closer. 20 D is 5 cm. */
    private const val MACRO_MIN_FOCUS_DIOPTERS = 20f

    /**
     * How much wider a second front camera must be before it counts as a
     * selfie ultra-wide rather than a cropped mode of the same lens.
     *
     * Real selfie ultra-wides sit around 0.6x of the main selfie. The S23
     * Ultra's two front ids are 0.85x apart and are one camera.
     */
    private const val SELFIE_ULTRA_WIDE_MAX_RATIO = 0.8f

    private const val DUPLICATE_FOCAL_TOLERANCE_MM = 0.05f
    private const val DUPLICATE_SENSOR_TOLERANCE_MM = 0.05f

    // ──────────────────────────────────────────────────────────────────
    // Entry point
    // ──────────────────────────────────────────────────────────────────

    /** Enumerates, classifies, deduplicates and builds the chips. Opens nothing. */
    fun probe(context: Context): Inventory {
        val notes = mutableListOf<String>()
        val startedAt = System.nanoTime()
        characteristicsReads = 0
        val discovered = try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            enumerate(manager, notes)
        } catch (e: Throwable) {
            Log.e(TAG, "camera enumeration failed", e)
            notes += "enumeration failed: $e"
            emptyList()
        }

        val cameras = collapseDuplicates(discovered)
        classify(cameras)
        inferOpenability(cameras)
        val zoom = computeZoom(cameras)
        val chips = generateZoomChips(cameras, zoom)

        return Inventory(
            brand = BrandBadge.resolve(Build.MANUFACTURER, Build.BRAND).name,
            model = Build.MODEL,
            cameras = cameras,
            opticalZoomMin = zoom.opticalMin,
            opticalZoomMax = zoom.opticalMax,
            digitalZoomMax = zoom.digitalMax,
            hybridZoomMax = zoom.hybridMax,
            zoomChips = chips,
            notes = notes,
        ).also {
            val millis = (System.nanoTime() - startedAt) / 1_000_000
            logSummary(it)
            // Enumeration is a chain of synchronous binder calls into the
            // camera service, which Google documents as sometimes taking
            // hundreds of milliseconds each. This number is the difference
            // between "run off the main thread as a precaution" and "would
            // have been an ANR".
            Log.i(TAG, "probe took ${millis}ms, $characteristicsReads characteristics reads")
        }
    }

    /** Counts characteristics reads per probe, for the timing line. */
    @Volatile
    private var characteristicsReads = 0

    // ──────────────────────────────────────────────────────────────────
    // 1. Discovery
    // ──────────────────────────────────────────────────────────────────

    private fun enumerate(manager: CameraManager, notes: MutableList<String>): List<CameraDescriptor> {
        val routes = LinkedHashMap<String, MutableSet<DiscoverySource>>()
        val parents = mutableMapOf<String, String>()

        val topLevel = try {
            manager.cameraIdList
        } catch (e: Throwable) {
            Log.e(TAG, "cameraIdList unavailable", e)
            notes += "cameraIdList unavailable: $e"
            emptyArray()
        }
        topLevel.forEach { routes.getOrPut(it) { mutableSetOf() }.add(DiscoverySource.ID_LIST) }

        // Route 2: physical members of logical cameras. One UI puts the
        // ultra-wide and both teles here and nowhere else. A camera already
        // in the id list keeps that route too, rather than being overwritten —
        // both facts are true and both are worth reporting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            topLevel.forEach { parent ->
                characteristicsOf(manager, parent)
                    ?.let { chars -> runCatching { chars.physicalCameraIds }.getOrNull() }
                    ?.forEach { sub ->
                        routes.getOrPut(sub) { mutableSetOf() }.add(DiscoverySource.PHYSICAL_SUB_CAMERA)
                        parents.putIfAbsent(sub, parent)
                    }
            }
        }

        // Route 3: ids the OEM did not list but will still answer for.
        // Reading characteristics cannot open or disturb a camera, so the cost
        // is a few failed lookups on phones that hide nothing. This recovers
        // four cameras on MIUI.
        val recovered = mutableListOf<String>()
        HIDDEN_ID_SCAN.forEach { candidate ->
            if (routes.containsKey(candidate)) return@forEach
            val chars = characteristicsOf(manager, candidate) ?: return@forEach
            val usable = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
            if (usable) {
                routes.getOrPut(candidate) { mutableSetOf() }.add(DiscoverySource.HIDDEN_ID_SCAN)
                recovered += candidate
            }
        }
        if (recovered.isNotEmpty()) {
            notes += "recovered ${recovered.size} camera(s) the OS did not list: $recovered"
        }

        return routes.mapNotNull { (id, sources) ->
            val chars = characteristicsOf(manager, id) ?: return@mapNotNull null
            runCatching { describe(id, chars, sources, parents[id]) }.getOrNull()
        }
    }

    /**
     * Ids worth asking about beyond the list. Camera ids are conventionally
     * small integers; scanning far past what any phone has would slow every
     * cold start to find nothing.
     */
    private val HIDDEN_ID_SCAN = (2..9).map { it.toString() }

    /**
     * One guarded, counted characteristics read.
     *
     * Every read goes through here so the cost of enumeration is measurable,
     * and so a HAL that throws for an id the OS just listed — documented on
     * several vendors, and thrown as an undeclared RuntimeException as often
     * as a CameraAccessException — costs that one camera rather than the
     * whole report.
     */
    private fun characteristicsOf(manager: CameraManager, id: String): CameraCharacteristics? {
        characteristicsReads++
        return runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
    }

    private fun describe(
        id: String,
        c: CameraCharacteristics,
        sources: Set<DiscoverySource>,
        parent: String?,
    ): CameraDescriptor {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull().sane(Float.MIN_VALUE, MAX_FOCAL_MM)
        val sensorW = physical?.width.sane(Float.MIN_VALUE, MAX_SENSOR_MM)
        val sensorH = physical?.height.sane(Float.MIN_VALUE, MAX_SENSOR_MM)

        val colourSizes = listOf(ImageFormat.YUV_420_888, ImageFormat.JPEG, ImageFormat.PRIVATE)
            .flatMap { format -> runCatching { map?.getOutputSizes(format)?.toList() }.getOrNull().orEmpty() }
        val largest = colourSizes.maxByOrNull { it.width.toLong() * it.height }

        return CameraDescriptor(
            id = id,
            sources = sources,
            physicalParentId = parent,
            facing = facing,
            maxWidth = largest?.width ?: pixelArray?.width ?: 0,
            maxHeight = largest?.height ?: pixelArray?.height ?: 0,
            megapixels = pixelArray?.let { (it.width.toLong() * it.height) / 1_000_000f }
                .sane(0f, MAX_MEGAPIXELS) ?: 0f,
            sensorWidthMm = sensorW,
            sensorHeightMm = sensorH,
            sensorDiagonalMm = if (sensorW != null && sensorH != null) hypot(sensorW, sensorH) else null,
            focalLengthMm = focal,
            equivalentFocalMm = equivalentFocalMm(focal, sensorW, sensorH),
            apertureF = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull().sane(Float.MIN_VALUE, MAX_APERTURE_F),
            minFocusDistanceDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                .sane(0f, MAX_DIOPTERS),
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                .sane(1f, MAX_DIGITAL_ZOOM),
            zoomRatioRange = readZoomRatioRange(c),
            capabilities = caps.map(::capabilityName),
            hasDepthOutput = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT),
            producesImages = colourSizes.isNotEmpty(),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Duplicates — collapsed, not merely flagged
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns one entry per physical lens, carrying the ids it answered to.
     *
     * The survivor is whichever came from the id list, because that is the id
     * the app can bind to; its [CameraDescriptor.duplicateIds] records the
     * rest so nothing is lost. Measured on an S23 Ultra: ids `0` and `5` are
     * one sensor, and `2` arrives by two routes at once.
     */
    fun collapseDuplicates(cameras: List<CameraDescriptor>): List<CameraDescriptor> {
        val groups = mutableListOf<MutableList<CameraDescriptor>>()
        cameras.forEach { camera ->
            val match = groups.firstOrNull { isSameHardware(it.first(), camera) }
            if (match != null) match.add(camera) else groups.add(mutableListOf(camera))
        }
        return groups.map { group ->
            if (group.size == 1) return@map group.single()
            val primary = group.minByOrNull { candidate ->
                if (DiscoverySource.ID_LIST in candidate.sources) 0 else 1
            } ?: group.first()
            primary.copy(
                duplicateIds = group.filter { it !== primary }.map { it.id },
                // Every route that reached this lens under any of its ids.
                sources = group.flatMap { it.sources }.toSet(),
                physicalParentId = primary.physicalParentId
                    ?: group.firstNotNullOfOrNull { it.physicalParentId },
            )
        }
    }

    /** Same sensor size, same focal length, same way up. */
    fun isSameHardware(a: CameraDescriptor, b: CameraDescriptor): Boolean {
        if (a.facing != b.facing) return false
        if (a.id == b.id) return true
        val focalA = a.focalLengthMm ?: return false
        val focalB = b.focalLengthMm ?: return false
        if (abs(focalA - focalB) > DUPLICATE_FOCAL_TOLERANCE_MM) return false
        val widthA = a.sensorWidthMm ?: return false
        val widthB = b.sensorWidthMm ?: return false
        val heightA = a.sensorHeightMm ?: return false
        val heightB = b.sensorHeightMm ?: return false
        if (abs(widthA - widthB) > DUPLICATE_SENSOR_TOLERANCE_MM) return false
        if (abs(heightA - heightB) > DUPLICATE_SENSOR_TOLERANCE_MM) return false
        // Resolution as the final tiebreak: two lenses can share a focal
        // length and a sensor size and still be different modules.
        return a.megapixels == 0f || b.megapixels == 0f || abs(a.megapixels - b.megapixels) < 0.5f
    }

    /**
     * `CONTROL_ZOOM_RATIO_RANGE`, validated before it is believed.
     *
     * Wrapped individually rather than relying on the caller's guard: there is
     * an unverified report of this specific read crashing on some low-cost
     * hardware, and a try costs nothing against losing the camera entirely.
     * The range itself is checked for sanity — an upper below the lower, or a
     * non-finite bound, is not a range.
     */
    private fun readZoomRatioRange(c: CameraCharacteristics): Pair<Float, Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val range = runCatching { c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
            ?: return null
        val lower = range.lower.sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) ?: return null
        val upper = range.upper.sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) ?: return null
        if (upper < lower) return null
        return lower to upper
    }

    // --------------------------------------------------------------
    // Sanity gate
    // --------------------------------------------------------------

    /**
     * A HAL value is a measurement only if it is finite and inside physical
     * reason. Everything else - NaN, infinity, zero, negative, a placeholder
     * like 1e30 - becomes null, which the rest of the system already knows how
     * to report as "not published".
     *
     * Rejected at the boundary rather than guarded downstream, because there
     * is no downstream guard that catches all of it: a NaN compares false
     * against everything, so it slips silently through every range check and
     * only surfaces at `JSONObject.put`, which throws "JSON does not allow
     * non-finite numbers" and takes the whole export with it. That was a real
     * crash, found by HostileHalTest rather than reasoned about.
     */
    private fun Float?.sane(min: Float, max: Float): Float? {
        val value = this ?: return null
        if (!value.isFinite()) return null
        if (value < min || value > max) return null
        return value
    }

    // Physical bounds. Generous on purpose: the job is to reject nonsense,
    // not to second-guess unusual but real hardware.
    private const val MAX_FOCAL_MM = 1000f
    private const val MAX_SENSOR_MM = 100f
    private const val MAX_APERTURE_F = 100f
    private const val MAX_DIGITAL_ZOOM = 1000f
    private const val MAX_DIOPTERS = 1000f
    private const val MAX_MEGAPIXELS = 1000f

    /** Zoom ratios outside this came from numbers that were not measurements. */
    private const val MIN_SANE_ZOOM = 0.05f
    private const val MAX_SANE_ZOOM = 200f

    // ──────────────────────────────────────────────────────────────────
    // 3. Geometry
    // ──────────────────────────────────────────────────────────────────

    /**
     * The 35 mm-equivalent focal length — the only focal number comparable
     * across two different phones. Null when no sensor size is published:
     * guessing one misclassifies silently, which is worse than saying nothing.
     */
    fun equivalentFocalMm(focalMm: Float?, sensorWidthMm: Float?, sensorHeightMm: Float?): Float? {
        val focal = focalMm.sane(Float.MIN_VALUE, MAX_FOCAL_MM) ?: return null
        val width = sensorWidthMm.sane(Float.MIN_VALUE, MAX_SENSOR_MM) ?: return null
        val height = sensorHeightMm.sane(Float.MIN_VALUE, MAX_SENSOR_MM) ?: return null
        val diagonal = hypot(width, height)
        if (!diagonal.isFinite() || diagonal <= 0f) return null
        // The result is gated too: sane inputs still divide into nonsense
        // when a vendor reports a micrometre-wide sensor.
        return (focal * FULL_FRAME_DIAGONAL_MM / diagonal).sane(Float.MIN_VALUE, MAX_FOCAL_MM)
    }

    /**
     * The band a photographic lens falls in.
     *
     * Telephoto runs 35-120, leaving no gap: a 48 mm equivalent is one of the
     * commonest "2x" designs shipping and the Redmi's depth helper sits at
     * 43 mm. An unassigned band drops real hardware into UNKNOWN.
     */
    fun roleForEquivalent(equivalentMm: Float): LensRole = when {
        equivalentMm <= ULTRA_WIDE_MAX_MM -> LensRole.ULTRA_WIDE
        equivalentMm <= WIDE_MAX_MM -> LensRole.WIDE
        equivalentMm < SUPER_TELEPHOTO_MIN_MM -> LensRole.TELEPHOTO
        else -> LensRole.SUPER_TELEPHOTO
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Classification
    // ──────────────────────────────────────────────────────────────────

    fun classify(cameras: List<CameraDescriptor>) {
        val backs = cameras.filter { it.facing == "back" }
        val fronts = cameras.filter { it.facing == "front" }

        // The main camera is the largest sensor among back cameras that are
        // not helpers — universally true, because the main is where the money
        // goes. Every zoom ratio is measured from it.
        val main = backs
            .filterNot { isAuxiliarySensor(it) }
            .maxByOrNull { it.sensorDiagonalMm ?: (it.megapixels / 100f) }
        val mainEquivalent = main?.equivalentFocalMm

        backs.forEach { camera ->
            camera.zoomRatio = zoomRatioOf(camera, mainEquivalent)
            classifyBack(camera, mainEquivalent)
        }

        // Front: absolute bands do not work — plenty of ordinary selfie
        // cameras sit at 24 mm, inside any ultra-wide band you would pick for
        // the back. With only one there is nothing to be wider than.
        //
        // Being the widest is not enough either. Measured on an S23 Ultra,
        // which has one selfie camera: the OS exposes it twice, at 25.5 mm
        // and 30.1 mm, because one id is a cropped mode of the other. Calling
        // the 25.5 mm one an ultra-wide was a false positive — 25.5 mm is an
        // ordinary selfie framing. A real selfie ultra-wide is a different
        // lens and sits far wider than its companion, so a genuine gap is
        // required and 15% is not one.
        val widestFront = fronts.mapNotNull { it.equivalentFocalMm }.minOrNull()
        val narrowestFront = fronts.mapNotNull { it.equivalentFocalMm }.maxOrNull()
        val frontsDifferEnough = widestFront != null && narrowestFront != null &&
            narrowestFront > 0f && widestFront / narrowestFront <= SELFIE_ULTRA_WIDE_MAX_RATIO
        fronts.forEach { camera ->
            val equivalent = camera.equivalentFocalMm
            val isWidest = fronts.size > 1 && frontsDifferEnough &&
                equivalent != null && widestFront != null &&
                equivalent <= widestFront * 1.05f
            camera.role = if (isWidest) LensRole.SELFIE_ULTRA_WIDE else LensRole.SELFIE
            camera.confidence = if (fronts.size > 1) Confidence.INFERRED else Confidence.MEASURED
            camera.evidence = buildList {
                add("facing=front")
                add("front_camera_count=${fronts.size}")
                equivalent?.let { add("equivalent35mm=${fmt(it, 1)}") }
                if (fronts.size > 1) add(if (isWidest) "widest_front_camera" else "narrower_front_camera")
            }
            camera.zoomRatio = 1f
        }
    }

    private fun classifyBack(camera: CameraDescriptor, mainEquivalent: Float?) {
        val equivalent = camera.equivalentFocalMm
        val base = buildList {
            equivalent?.let { add("equivalent35mm=${fmt(it, 1)}") }
            add("sensor=${fmt(camera.megapixels, 1)}MP")
            camera.sensorDiagonalMm?.let { add("sensor_diagonal=${fmt(it, 2)}mm") }
            if (equivalent != null && mainEquivalent != null && mainEquivalent > 0f) {
                add("fov_ratio=" + fmt(equivalent / mainEquivalent, 2))
            }
            camera.zoomRatioRange?.let { add("zoom_ratio_range=${fmt(it.first, 2)}-${fmt(it.second, 2)}") }
        }

        if (camera.hasDepthOutput) {
            camera.role = LensRole.DEPTH
            camera.confidence = Confidence.MEASURED
            camera.evidence = base + "capability=DEPTH_OUTPUT"
            return
        }
        if (!camera.producesImages) {
            camera.role = LensRole.DEPTH
            camera.confidence = Confidence.INFERRED
            camera.evidence = base + "no_colour_output_stream"
            return
        }

        if (isAuxiliarySensor(camera)) {
            val diopters = camera.minFocusDistanceDiopters
            if (diopters != null && diopters >= MACRO_MIN_FOCUS_DIOPTERS) {
                camera.role = LensRole.MACRO
                camera.confidence = Confidence.INFERRED
                camera.evidence = base +
                    "min_focus=${fmt(100f / diopters, 1)}cm" +
                    "min_focus_diopters=${fmt(diopters, 0)}"
                return
            }
            if (equivalent != null && mainEquivalent != null && mainEquivalent > 0f) {
                val ratio = equivalent / mainEquivalent
                // A macro is built to frame like the normal lens, up close; a
                // depth helper looks at a different angle entirely.
                if (abs(ratio - 1f) <= MACRO_FOV_MATCH_TOLERANCE) {
                    camera.role = LensRole.MACRO
                    camera.confidence = Confidence.HEURISTIC
                    camera.evidence = base + "auxiliary_sensor" + "frames_like_main_lens"
                    return
                }
                camera.role = LensRole.DEPTH
                camera.confidence = Confidence.HEURISTIC
                camera.evidence = base + "auxiliary_sensor" + "no_photographic_use"
                return
            }
            camera.role = LensRole.UNKNOWN
            camera.confidence = Confidence.HEURISTIC
            camera.evidence = base + "auxiliary_sensor" + "insufficient_data_to_classify"
            return
        }

        if (equivalent == null) {
            // No sensor size published: the band cannot be computed. Say so
            // rather than invent a number.
            camera.role = LensRole.UNKNOWN
            camera.confidence = Confidence.HEURISTIC
            camera.evidence = base + "no_sensor_size_published"
            return
        }

        val ratio = camera.zoomRatio
        if (equivalent >= PERISCOPE_MIN_MM && ratio != null && ratio >= PERISCOPE_MIN_ZOOM) {
            camera.role = LensRole.PERISCOPE
            camera.confidence = Confidence.INFERRED
            camera.evidence = base + "zoom_ratio=${fmt(ratio, 1)}" + "folded_optics_required_at_this_length"
            return
        }
        camera.role = roleForEquivalent(equivalent)
        camera.confidence = Confidence.MEASURED
        camera.evidence = base
    }

    /**
     * A sensor too small *and* too coarse to be a photographic lens.
     *
     * Both tests, never either: a genuine ultra-wide can be 8 MP and a genuine
     * periscope sits on a small sensor. Only the combination is a helper.
     */
    fun isAuxiliarySensor(camera: CameraDescriptor): Boolean {
        val lowResolution = camera.megapixels in 0.01f..AUXILIARY_MAX_MEGAPIXELS
        val smallSensor = (camera.sensorDiagonalMm ?: Float.MAX_VALUE) < AUXILIARY_MAX_SENSOR_DIAGONAL_MM
        return lowResolution && smallSensor
    }

    private fun zoomRatioOf(camera: CameraDescriptor, mainEquivalent: Float?): Float? {
        val equivalent = camera.equivalentFocalMm ?: return null
        val reference = mainEquivalent.sane(Float.MIN_VALUE, MAX_FOCAL_MM) ?: return null
        val ratio = (equivalent / reference).sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) ?: return null
        return roundZoom(ratio)
    }

    /**
     * Zoom factors as a camera app prints them.
     *
     * 0.7 is deliberately absent: phone ultra-wides are sold as 0.5x or 0.6x,
     * and leaving it in made a real 0.651x lens (measured on the Redmi) snap
     * to a number its own maker never shows. The 12% window is the spread real
     * lenses sit at around their marketed figure — tight enough that a genuine
     * 1.4x keeps its own value rather than claiming to be the main camera.
     */
    fun roundZoom(zoom: Float): Float {
        if (!zoom.isFinite() || zoom <= 0f) return 1f
        val conventional = floatArrayOf(0.5f, 0.6f, 1f, 2f, 2.5f, 3f, 3.5f, 5f, 8f, 10f)
        val nearest = conventional.minByOrNull { abs(it - zoom) } ?: return zoom
        return if (abs(nearest - zoom) <= nearest * 0.12f) nearest else (Math.round(zoom * 10) / 10f)
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Openability
    // ──────────────────────────────────────────────────────────────────

    /**
     * What can be said about opening each camera without opening one.
     *
     * A physical sub-camera is [Openability.NOT_OPENABLE] as a statement of
     * the API contract, not a guess: `openCamera` takes a logical id, and a
     * sub-camera is reached by binding its parent and selecting it with
     * `setPhysicalCameraId`. Ids that arrived only from the hidden scan stay
     * [Openability.UNKNOWN] — the OEM did not list them, and whether it will
     * also refuse to open them can only be found out by trying.
     */
    fun inferOpenability(cameras: List<CameraDescriptor>) {
        cameras.forEach { camera ->
            when {
                DiscoverySource.ID_LIST in camera.sources -> {
                    camera.openability = Openability.OPENABLE
                    camera.openabilityReason = "listed by the OS as an openable camera"
                }
                camera.sources == setOf(DiscoverySource.PHYSICAL_SUB_CAMERA) -> {
                    camera.openability = Openability.NOT_OPENABLE
                    camera.openabilityReason =
                        "physical sub-camera; reached through logical camera " +
                        "${camera.physicalParentId} with setPhysicalCameraId"
                }
                else -> {
                    camera.openability = Openability.UNKNOWN
                    camera.openabilityReason = "not listed by the OS; requires a real open to confirm"
                }
            }
        }
    }

    /**
     * Opens each still-unknown camera to settle the question, then closes it.
     *
     * **Never call this while a camera session is running.** Opening evicts
     * whoever holds the device, including this app's own stream. It is also
     * slow — hundreds of milliseconds per camera — which is why [probe] does
     * not do it and this is opt-in.
     *
     * Blocking, with a per-camera timeout so a HAL that never calls back
     * cannot hang the caller. Without the CAMERA permission every result
     * stays UNKNOWN rather than being reported as a hardware failure.
     */
    fun verifyOpenability(
        context: Context,
        cameras: List<CameraDescriptor>,
        timeoutMs: Long = 1500L,
    ) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "openability not verified: CAMERA permission not granted")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val thread = HandlerThread("camera-openability").apply { start() }
        val handler = Handler(thread.looper)
        try {
            cameras.filter { it.openability == Openability.UNKNOWN }.forEach { camera ->
                val (result, reason) = tryOpen(manager, handler, camera.id, timeoutMs)
                camera.openability = result
                camera.openabilityReason = reason
                Log.i(TAG, "openability ${camera.id}: $result — $reason")
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun tryOpen(
        manager: CameraManager,
        handler: Handler,
        id: String,
        timeoutMs: Long,
    ): Pair<Openability, String> {
        val latch = CountDownLatch(1)
        var outcome: Pair<Openability, String> = Openability.UNKNOWN to "timed out after ${timeoutMs}ms"
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        outcome = Openability.OPENABLE to "opened successfully"
                        runCatching { device.close() }   // a probe, not a session
                        latch.countDown()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        outcome = Openability.NOT_OPENABLE to "disconnected while opening"
                        runCatching { device.close() }
                        latch.countDown()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        outcome = Openability.NOT_OPENABLE to "open failed with error $error"
                        runCatching { device.close() }
                        latch.countDown()
                    }
                },
                handler,
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: CameraAccessException) {
            outcome = Openability.NOT_OPENABLE to "CameraAccessException reason ${e.reason}"
        } catch (e: IllegalArgumentException) {
            outcome = Openability.NOT_OPENABLE to "rejected: ${e.message}"
        } catch (e: SecurityException) {
            outcome = Openability.NOT_OPENABLE to "denied: ${e.message}"
        } catch (e: Throwable) {
            outcome = Openability.NOT_OPENABLE to "failed: $e"
        }
        return outcome
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Zoom
    // ──────────────────────────────────────────────────────────────────

    data class ZoomRange(
        val opticalMin: Float,
        val opticalMax: Float,
        val digitalMax: Float,
        val hybridMax: Float,
    )

    /**
     * Optical range from the lenses, digital from the sensor crop, hybrid from
     * both together.
     *
     * Hybrid is the largest **product**, not the longest lens times the
     * biggest crop: on a phone whose periscope allows less digital zoom than
     * the main, the main can be the one that reaches furthest.
     */
    fun computeZoom(cameras: List<CameraDescriptor>): ZoomRange {
        val lenses = cameras.filter { it.facing == "back" && it.role.isPhotographic }
        // Every input re-gated: a descriptor can be built by a caller
        // rather than by describe(), and 1x is the only honest fallback -
        // it is what a camera does when nothing about its zoom is known.
        val ratios = lenses.mapNotNull { it.zoomRatio.sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) }
        val derivedMin = ratios.minOrNull() ?: 1f
        val derivedMax = ratios.maxOrNull() ?: 1f

        // Where a logical camera publishes CONTROL_ZOOM_RATIO_RANGE it is
        // more authoritative than anything derived from focal lengths: it is
        // the device stating the sweep it will actually perform, lens
        // switching included. Widened rather than replaced, because a phone
        // that hides a lens from the id list can still expose it here, and a
        // phone that never implemented the API reports a flat 1.0-1.0 that
        // must not shrink a range the lenses prove is wider.
        val published = cameras.filter { it.facing == "back" }.mapNotNull { it.zoomRatioRange }
        val opticalMin = minOf(derivedMin, published.minOfOrNull { it.first } ?: derivedMin)
        val opticalMax = maxOf(derivedMax, published.maxOfOrNull { it.second } ?: derivedMax)
        val digitalMax = lenses
            .mapNotNull { it.maxDigitalZoom.sane(1f, MAX_DIGITAL_ZOOM) }
            .maxOrNull() ?: 1f
        val hybridMax = lenses.mapNotNull { camera ->
            val ratio = camera.zoomRatio.sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) ?: return@mapNotNull null
            val digital = camera.maxDigitalZoom.sane(1f, MAX_DIGITAL_ZOOM) ?: return@mapNotNull null
            (ratio * digital).sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM * MAX_DIGITAL_ZOOM)
        }.maxOrNull() ?: opticalMax
        return ZoomRange(opticalMin, opticalMax, digitalMax, maxOf(hybridMax, opticalMax))
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Zoom chips
    // ──────────────────────────────────────────────────────────────────

    /**
     * The 1-2-3-5 ladder every logarithmic scale uses, which is why the values
     * it produces read as natural rather than arithmetic.
     */
    private val CHIP_LADDER = floatArrayOf(
        0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f, 200f, 300f, 500f, 1000f,
    )

    /** Roughly how far apart consecutive generated steps should sit. */
    private const val CHIP_STEP_RATIO = 2.5f

    /**
     * The values the zoom control shows, built from the hardware.
     *
     * Three rules, in order:
     *
     * 1. **Every physical lens gets a chip**, labelled UW/W/T/ST. A real lens
     *    is never hidden.
     * 2. **Gaps between lenses are filled from the ladder, but only above 1x.**
     *    Below 1x there is no digital zoom — nothing but a wider lens can get
     *    there — so a generated 0.6 beside a 0.5 lens would be a lens that
     *    does not exist.
     * 3. **Above the longest lens**, steps climb by about [CHIP_STEP_RATIO]
     *    up the ladder to the hybrid maximum, which is always included.
     *
     * Generated steps carry no label, because they are crops rather than
     * lenses and labelling them UW/W/T/ST would claim hardware that is not
     * there.
     */
    fun generateZoomChips(cameras: List<CameraDescriptor>, zoom: ZoomRange): List<ZoomChip> {
        val lenses = cameras
            .filter {
                it.facing == "back" && it.role.isPhotographic &&
                    it.zoomRatio.sane(MIN_SANE_ZOOM, MAX_SANE_ZOOM) != null
            }
            .sortedBy { it.zoomRatio }
        if (lenses.isEmpty()) return emptyList()

        val chips = linkedMapOf<Float, ZoomChip>()
        lenses.forEach { lens ->
            val ratio = lens.zoomRatio ?: return@forEach
            // One chip per ratio: two lenses that round to the same figure
            // must not produce two identical chips.
            chips.putIfAbsent(
                ratio,
                ZoomChip(ratio, lens.role.chipLabel, isPhysicalLens = true, cameraId = lens.id),
            )
        }

        val opticalRatios = chips.keys.sorted()
        opticalRatios.zipWithNext().forEach { (low, high) ->
            CHIP_LADDER.filter { it > low && it < high && it >= 1f }
                .forEach { chips.putIfAbsent(it, ZoomChip(it, null, false, null)) }
        }

        if (zoom.hybridMax > zoom.opticalMax * 1.05f) {
            var value = zoom.opticalMax
            while (true) {
                val next = CHIP_LADDER.firstOrNull { it >= value * CHIP_STEP_RATIO } ?: break
                if (next >= zoom.hybridMax) break
                chips.putIfAbsent(next, ZoomChip(next, null, false, null))
                value = next
            }
            chips.putIfAbsent(zoom.hybridMax, ZoomChip(zoom.hybridMax, null, false, null))
        }

        return chips.values.sortedBy { it.ratio }
    }

    // ──────────────────────────────────────────────────────────────────
    // 8. Output
    // ──────────────────────────────────────────────────────────────────

    /**
     * The full report.
     *
     * `model` is `Build.MODEL` — "SM-S918B", not "Galaxy S23 Ultra". Android
     * exposes no marketing name, and the only way to produce one is the
     * lookup table this system exists to avoid.
     *
     * Every number is written with [Locale.ROOT]. JSON is not localised, and a
     * phone set to Spanish would otherwise emit `0,6` where every parser
     * downstream expects `0.6`.
     */
    fun toJson(inventory: Inventory): JSONObject {
        val cameras = JSONArray()
        inventory.cameras.forEach { camera ->
            cameras.put(
                JSONObject()
                    .put("id", camera.id)
                    .put("duplicateIds", JSONArray(camera.duplicateIds))
                    .put("role", camera.role.name)
                    .put("confidence", camera.confidence.name)
                    .put("evidence", JSONArray(camera.evidence))
                    .put("openability", camera.openability.name)
                    .put("openabilityReason", camera.openabilityReason)
                    .put("sources", JSONArray(camera.sources.map { it.name }.sorted()))
                    .put("facing", camera.facing)
                    .put("physicalParentId", camera.physicalParentId ?: JSONObject.NULL)
                    .put("equivalent35mm", camera.equivalentFocalMm?.let { round(it, 1) } ?: JSONObject.NULL)
                    .put("megapixels", round(camera.megapixels, 1))
                    .put(
                        "maxResolution",
                        if (camera.maxWidth > 0) "${camera.maxWidth}x${camera.maxHeight}" else JSONObject.NULL,
                    )
                    .put("sensorSizeMm", camera.sensorWidthMm?.let {
                        "${fmt(it, 2)}x${fmt(camera.sensorHeightMm ?: 0f, 2)}"
                    } ?: JSONObject.NULL)
                    .put("focalLengthMm", camera.focalLengthMm?.let { round(it, 2) } ?: JSONObject.NULL)
                    .put("aperture", camera.apertureF?.let { round(it, 1) } ?: JSONObject.NULL)
                    .put(
                        "minimumFocusDistanceCm",
                        camera.minFocusDistanceCm?.let { round(it, 1) } ?: JSONObject.NULL,
                    )
                    .put("maxDigitalZoom", camera.maxDigitalZoom?.let { round(it, 1) } ?: JSONObject.NULL)
                    .put("zoomRatio", camera.zoomRatio?.let { round(it, 2) } ?: JSONObject.NULL)
                    .put("capabilities", JSONArray(camera.capabilities)),
            )
        }

        val chips = JSONArray()
        inventory.zoomChips.forEach { chip ->
            chips.put(
                JSONObject()
                    .put("ratio", round(chip.ratio, 2))
                    .put("label", chip.label ?: JSONObject.NULL)
                    .put("physicalLens", chip.isPhysicalLens)
                    .put("cameraId", chip.cameraId ?: JSONObject.NULL),
            )
        }

        return JSONObject()
            .put("brand", inventory.brand)
            .put("model", inventory.model)
            .put("opticalZoomMin", round(inventory.opticalZoomMin, 2))
            .put("opticalZoomMax", round(inventory.opticalZoomMax, 2))
            .put("digitalZoomMax", round(inventory.digitalZoomMax, 2))
            .put("hybridZoomMax", round(inventory.hybridZoomMax, 2))
            .put("zoomChips", chips)
            .put("notes", JSONArray(inventory.notes))
            .put("cameras", cameras)
    }

    /**
     * Rounds for output without letting the device locale near the value,
     * and without ever handing JSON a number it refuses.
     *
     * Returns [JSONObject.NULL] for anything non-finite. The sanity gate
     * should have caught it long before, but this is the last line before
     * the export throws, and an export that throws loses the whole report.
     */
    private fun round(value: Float, decimals: Int): Any =
        if (!value.isFinite()) {
            JSONObject.NULL
        } else {
            String.format(Locale.ROOT, "%.${decimals}f", value).toDouble()
        }

    private fun fmt(value: Float, decimals: Int): String =
        if (!value.isFinite()) "?" else String.format(Locale.ROOT, "%.${decimals}f", value)

    /** "10x", "0.6x", "1.4x" — no trailing zero on whole numbers. */
    fun formatZoom(value: Float): String {
        if (!value.isFinite()) return "?"
        val whole = abs(value - Math.round(value)) < 0.05f
        return if (whole) "${Math.round(value)}x" else "${fmt(value, 1)}x"
    }

    private fun logSummary(inventory: Inventory) {
        Log.i(
            TAG,
            "${inventory.brand} ${inventory.model}: " +
                inventory.cameras.joinToString(" | ") { camera ->
                    "${camera.id}${camera.duplicateIds.takeIf { it.isNotEmpty() }?.let { "+$it" } ?: ""}" +
                        ":${camera.facing}/${camera.role}" +
                        (camera.zoomRatio?.let { "@${formatZoom(it)}" } ?: "") +
                        " ${camera.confidence}/${camera.openability}" +
                        (camera.zoomRatioRange?.let {
                            " zr=${formatZoom(it.first)}-${formatZoom(it.second)}"
                        } ?: "")
                } +
                " | optical ${formatZoom(inventory.opticalZoomMin)}-${formatZoom(inventory.opticalZoomMax)}" +
                " digital ${formatZoom(inventory.digitalZoomMax)}" +
                " hybrid ${formatZoom(inventory.hybridZoomMax)}" +
                " | chips " + inventory.zoomChips.joinToString(",") {
                    formatZoom(it.ratio) + (it.label?.let { l -> "($l)" } ?: "")
                },
        )
        inventory.notes.forEach { Log.i(TAG, "note: $it") }
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        else -> "CAPABILITY_$value"
    }
}
