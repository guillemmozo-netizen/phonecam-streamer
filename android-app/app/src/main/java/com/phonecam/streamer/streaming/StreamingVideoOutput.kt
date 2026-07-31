package com.phonecam.streamer.streaming

import androidx.camera.core.CameraInfo
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.Observable
import androidx.camera.core.impl.utils.futures.Futures
import androidx.camera.video.MediaSpec
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.camera.video.VideoOutput
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/**
 * Minimal [VideoOutput]: hands every [SurfaceRequest] CameraX makes for the
 * [androidx.camera.video.VideoCapture] use case straight to [onRequest],
 * instead of implementing CameraX's own file-based Recorder pipeline. We
 * want the raw [android.view.Surface] so [CameraStreamer] can render camera
 * frames on the GPU directly into the H.264 encoder's input — the same
 * Surface-based capture class real camera apps use for 4K60 recording.
 *
 * This replaced [androidx.camera.core.ImageAnalysis]: confirmed on-device,
 * ImageAnalysis's CPU-accessible YUV buffers capped 4K capture at ~30fps
 * (a real Camera2 characteristic — the ISP's YUV-readback path is slower
 * than its GPU/PRIVATE-format path) even with encode() nowhere near that
 * budget and the network able to sustain 10x the needed bitrate.
 *
 * [onSurfaceRequested] is the only [VideoOutput] method without a default
 * implementation, but [getMediaSpec] still needs overriding too — confirmed
 * on-device, VideoCapture's onMergeConfig() unconditionally consults it even
 * though we drive resolution ourselves via ResolutionSelector, and the
 * interface's own default produced an Observable that resolved to a null
 * MediaSpec, crashing every single bind with
 * "IllegalArgumentException: Unable to update target resolution by null
 * MediaSpec." A plain default-valued MediaSpec (quality/bitrate/framerate
 * all AUTO) is enough — none of those fields are actually read for our
 * ResolutionSelector-driven path, VideoCapture just needs a non-null one to
 * not crash while merging configs.
 */
class StreamingVideoOutput(private val onRequest: (SurfaceRequest) -> Unit) : VideoOutput {
    /**
     * The landscape sensor size the composition's crop must FIT INSIDE —
     * StreamConfig.neededCaptureFor's output, set before each bind. This
     * replaced a bare "target height": height alone picked FHD for every
     * 1080p composition, and the crossed composition×hold cases (16:9 held
     * vertical, 9:16 held landscape…) then cropped a slice with 44-68% fewer
     * pixels than the encoder and upscaled the difference — the measured
     * vertical pixelation. See [captureQualityOrder].
     */
    @Volatile
    var neededWidth: Int = 1920

    @Volatile
    var neededHeight: Int = 1080

    /**
     * A plain MediaSpec.builder().build() defaults its quality selector to
     * VideoSpec.QUALITY_SELECTOR_AUTO, which is `[FHD, HD, SD]` — **UHD is not
     * in that list**. VideoCapture turns the selected qualities into the
     * candidate resolution list, so the default silently capped capture at
     * 1080p no matter what was requested.
     *
     * Rebuilt per bind rather than held as a constant, because that candidate
     * list is *ordered* and CameraX takes the first supported entry from it —
     * OPTION_CUSTOM_ORDERED_RESOLUTIONS outranks our own ResolutionSelector
     * entirely (measured: with UHD listed first, a 1080p request still bound a
     * 3840x2160 stream). So the order here, not the selector, is what actually
     * decides the capture resolution.
     */
    private fun mediaSpecFor(neededWidth: Int, neededHeight: Int): MediaSpec = MediaSpec.builder()
        .configureVideo { videoSpec ->
            // Quality's static init touches the Android framework, so the
            // constants are only referenced here on the device path; the
            // ORDER itself is the pure, JVM-tested index function.
            val byIndex = listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD)
            videoSpec.setQualitySelector(
                QualitySelector.fromOrderedList(
                    captureOrder(neededWidth, neededHeight).map { byIndex[it] },
                ),
            )
        }
        .build()
    private val mediaSpecObservable = object : Observable<MediaSpec> {
        override fun fetchData(): ListenableFuture<MediaSpec> =
            Futures.immediateFuture(mediaSpecFor(neededWidth, neededHeight))
        override fun addObserver(executor: Executor, observer: Observable.Observer<in MediaSpec>) {}
        override fun removeObserver(observer: Observable.Observer<in MediaSpec>) {}
    }

    companion object {
        // CameraX's video Quality bucket sizes, index-aligned with SD/HD/FHD/
        // UHD. Kept as plain ints so the selection logic is JVM-testable —
        // Quality's own statics can't initialize off-device. There are no
        // portrait, 4:3 or 1440p buckets — sensors and video profiles are
        // landscape — which is exactly why the selection has to be "smallest
        // bucket that COVERS the needed crop" rather than "bucket named after
        // the height".
        val BUCKET_SIZES = listOf(
            720 to 480,     // SD
            1280 to 720,    // HD
            1920 to 1080,   // FHD
            3840 to 2160,   // UHD
        )

        /**
         * The shipping selection: the short-edge rule, **escalated only when
         * that rule's own pick would not cover the needed capture**.
         *
         * Scoped this narrowly on purpose, from the measured matrix (S23
         * Ultra, holds confirmed in-log, not assumed):
         *
         *   16:9 landscape  need 1920x1080  short-edge FHD covers   -> unchanged
         *   16:9 portrait   (Camera2 path, never reaches this)      -> unchanged
         *   9:16 portrait   need 1920x1080  FHD covers              -> unchanged
         *   9:16 landscape  need 1080x1920  FHD does NOT cover      -> ESCALATES
         *   1:1  either     need 1080x1080  FHD covers              -> unchanged
         *   3:4  portrait   need 1440x1080  FHD covers              -> unchanged
         *   3:4  landscape  need 1080x1440  FHD does NOT cover      -> ESCALATES
         *
         * Only the cells with a measured (or, for 3:4 landscape, structurally
         * identical) pixel deficit change: 9:16 held landscape captured
         * 608x1080 and upscaled it to a 1080x1920 encoder — 68.3% of the
         * pixels invented, the worst number the campaign found. Everything
         * that measured 0% keeps the exact behaviour it has today, so no
         * thermal or battery cost is paid where there is nothing to gain.
         *
         * Note the 3:4-landscape cell escalates too, by the same geometry;
         * it was not separately measured, and is called out here rather than
         * special-cased away, because carving it out would mean shipping a
         * rule that knowingly leaves an identical defect in place.
         */
        fun captureOrder(neededWidth: Int, neededHeight: Int): List<Int> {
            val byShortEdge = shortEdgeOrderIndices(minOf(neededWidth, neededHeight))
            val firstPick = BUCKET_SIZES[byShortEdge.first()]
            val covers = firstPick.first >= neededWidth && firstPick.second >= neededHeight
            return if (covers) byShortEdge else captureOrderIndices(neededWidth, neededHeight)
        }

        /**
         * Bucket order by the needed capture's short edge alone — the
         * long-standing rule, now used as the base case of [captureOrder].
         *
         * There is no 1440p Quality, so a 1440-tall request prefers UHD and
         * lets the renderer scale 3840x2160 down — a genuine downscale rather
         * than the upscale FHD would force.
         */
        fun shortEdgeOrderIndices(shortEdge: Int): List<Int> = when {
            shortEdge >= 1440 -> listOf(3, 2, 1, 0)   // UHD, FHD, HD, SD
            shortEdge >= 1080 -> listOf(2, 1, 0, 3)
            shortEdge >= 720 -> listOf(1, 0, 2, 3)
            else -> listOf(0, 1, 2, 3)
        }

        /**
         * DIAGNOSTIC ONLY — not wired into capture selection. See M2's
         * verdict below.
         *
         * Bucket indices (into [BUCKET_SIZES]) for a needed landscape capture:
         * every bucket that covers it, smallest first, then the undersized
         * ones largest-first.
         *
         * M2 proposed making this the shipping rule, on the theory that
         * crossed composition×hold combinations cropped a slice with 44-68%
         * fewer pixels than the encoder. **Measured on device (S23 Ultra,
         * before/after campaign over all five compositions): that deficit
         * does not exist.** CameraX delivers buffers already cropped to the
         * exact target (1080x1080 for 1:1, 1080x1440 for 3:4) and Camera2
         * requests the composition's native size directly, so buffer and
         * encoder pixels matched exactly in every case — 0% deficit before
         * and after. Adopting this order would have escalated those cases to
         * a 4K sensor mode for no output gain, paying heat and battery.
         *
         * Kept because it is what makes the `capture need:` diagnostic line
         * meaningful, and because the measurement is one device's: a phone
         * whose CameraX really does under-provision would show it here.
         */
        fun captureOrderIndices(neededWidth: Int, neededHeight: Int): List<Int> {
            val covers = { size: Pair<Int, Int> ->
                size.first >= neededWidth && size.second >= neededHeight
            }
            val covering = BUCKET_SIZES.indices.filter { covers(BUCKET_SIZES[it]) }
            val undersized = BUCKET_SIZES.indices.filterNot { covers(BUCKET_SIZES[it]) }.reversed()
            return covering + undersized
        }
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        onRequest(request)
    }

    override fun getMediaSpec(): Observable<MediaSpec> = mediaSpecObservable

    /**
     * THE fix for capture silently running at 1440x810 while the encoder ran at
     * 3840x2160.
     *
     * VideoOutput's default implementation returns VideoCapabilities.EMPTY. With
     * that, VideoCapture.updateCustomOrderedResolutionsByQuality() finds no
     * supported qualities, logs "Can't find any supported quality on the device."
     * and returns early *without ever inserting* OPTION_CUSTOM_ORDERED_RESOLUTIONS
     * — so our ResolutionSelector is never consulted and CameraX's auto-resolution
     * mechanism picks whatever it likes (measured on an S23 Ultra: a 1920x1440
     * stream, cropped by the 16:9 ViewPort to 1440x810, which EncoderSurfaceRenderer
     * then upscaled 7x to fake 4K).
     *
     * Recorder's implementation reports what the device can really record, which is
     * the same source CameraX uses for its own Recorder-based pipeline — we just
     * never provided it, because this method has a default and the compiler
     * therefore never asked.
     */
    override fun getMediaCapabilities(cameraInfo: CameraInfo): VideoCapabilities =
        Recorder.getVideoCapabilities(cameraInfo)
}
