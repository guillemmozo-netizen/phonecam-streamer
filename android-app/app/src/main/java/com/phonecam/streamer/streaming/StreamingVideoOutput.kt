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
     * The capture height the caller is aiming for. Set before each bind — see
     * [qualitiesFor] for why this can't just be a fixed list.
     */
    @Volatile
    var targetHeight: Int = 1080

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
    private fun mediaSpecFor(targetHeight: Int): MediaSpec = MediaSpec.builder()
        .configureVideo { videoSpec ->
            videoSpec.setQualitySelector(QualitySelector.fromOrderedList(qualitiesFor(targetHeight)))
        }
        .build()

    /**
     * CameraX only has SD/HD/FHD/UHD buckets — there is no 1440p quality, so
     * 2560x1440 can never appear in the candidate list at all. A 1440p request
     * therefore prefers UHD and lets the renderer scale 3840x2160 down, which
     * is a genuine downscale rather than the upscale FHD would have forced.
     */
    private fun qualitiesFor(targetHeight: Int): List<Quality> = when {
        targetHeight >= 1440 -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        targetHeight >= 1080 -> listOf(Quality.FHD, Quality.HD, Quality.SD)
        targetHeight >= 720 -> listOf(Quality.HD, Quality.SD)
        else -> listOf(Quality.SD, Quality.HD)
    }
    private val mediaSpecObservable = object : Observable<MediaSpec> {
        override fun fetchData(): ListenableFuture<MediaSpec> =
            Futures.immediateFuture(mediaSpecFor(targetHeight))
        override fun addObserver(executor: Executor, observer: Observable.Observer<in MediaSpec>) {}
        override fun removeObserver(observer: Observable.Observer<in MediaSpec>) {}
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
