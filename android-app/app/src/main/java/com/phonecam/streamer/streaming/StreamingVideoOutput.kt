package com.phonecam.streamer.streaming

import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.Observable
import androidx.camera.core.impl.utils.futures.Futures
import androidx.camera.video.MediaSpec
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
    private val mediaSpec = MediaSpec.builder().build()
    private val mediaSpecObservable = object : Observable<MediaSpec> {
        override fun fetchData(): ListenableFuture<MediaSpec> = Futures.immediateFuture(mediaSpec)
        override fun addObserver(executor: Executor, observer: Observable.Observer<in MediaSpec>) {}
        override fun removeObserver(observer: Observable.Observer<in MediaSpec>) {}
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        onRequest(request)
    }

    override fun getMediaSpec(): Observable<MediaSpec> = mediaSpecObservable
}
