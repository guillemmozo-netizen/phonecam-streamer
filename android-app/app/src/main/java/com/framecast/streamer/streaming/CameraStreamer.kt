package com.framecast.streamer.streaming

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.framecast.streamer.StreamConfig
import com.framecast.streamer.rewards.RewardManager
import com.framecast.streamer.rewards.StreamProfile
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "CameraStreamer"


/**
 * Ties together: camera frame delivery -> reward-tier gating -> H.264 encode
 * (MediaCodec, see H264Encoder) -> TCP send. One instance per active
 * streaming session.
 *
 * Camera frames arrive via [videoOutput] — a [StreamingVideoOutput] bound to
 * CameraX's `VideoCapture` use case (see MainActivity.startCamera) — instead
 * of implementing [androidx.camera.core.ImageAnalysis.Analyzer] like the
 * previous version. That switch is why 4K went from ~30fps to real 4K60 on a
 * real device: ImageAnalysis hands frames to the CPU as YUV_420_888, and
 * confirmed on-device, THAT specific buffer format was what capped capture
 * at ~30fps, not the encoder (which measured ~27ms/frame — well inside a
 * 60fps budget) or the network (measured 80-300+ Mbps on both Wi-Fi and USB,
 * vastly more than the ~50Mbps a 4K stream needs). VideoCapture's
 * Surface-based path is the same capture class real camera apps use for
 * 4K60 recording — frames go straight from the camera to a GPU texture,
 * never touching the CPU.
 *
 * Output resolution/fps is the user's Settings choice ([streamConfig]) capped by
 * the reward tier's ceiling (1080p60 free / 4k60 premium) — never higher than what
 * the tier allows, but free to be lower (e.g. 720p30 to save bandwidth on weak Wi-Fi).
 * [streamConfig.videoBitrateBps] now has a real effect too: it's the encoder's
 * configured target bitrate, not just a saved preference.
 */
class CameraStreamer(
    // A resolver, not a plain String: MainActivity now constructs this
    // instance *synchronously*, before startCamera() binds the VideoCapture
    // use case, so [videoOutput] is never null when CameraX asks for a
    // Surface (see its own doc — VideoCapture only asks once per bind
    // session, unlike ImageAnalysis's per-frame analyze() calls, so a null
    // streamer at that one moment used to kill video for the whole
    // session). Host resolution itself still happens off the main thread,
    // inside connectWithRetry() on networkExecutor, exactly as before.
    private val hostResolver: () -> String,
    private val port: Int,
    private val rewardManager: RewardManager,
    private val streamConfig: StreamConfig,
    private val networkExecutor: Executor = Executors.newSingleThreadExecutor(),
    // Null means this session has no audio, which puts the whole stream back on
    // the original video-only framing (see the Hello/send path below). Built by
    // MainActivity, which is where the microphone preference and the
    // RECORD_AUDIO permission live.
    private val audioSession: com.framecast.streamer.audio.AudioStreamSession? = null,
    /** Called when the microphone was refused, so the UI can say why instead of
     *  streaming video with silently wrong audio. */
    private val onAudioFailure: (com.framecast.streamer.audio.CaptureFailure) -> Unit = {},
) {
    @Volatile private var connection: StreamConnection? = null
    @Volatile private var supervisor: ConnectionSupervisor<StreamConnection>? = null
    @Volatile private var stopped = false
    private var helloSent = false

    // All GL/encoder work (SurfaceTexture.OnFrameAvailableListener needs a
    // Handler/Looper, and every EGL/GL call must happen on whichever thread
    // last called eglMakeCurrent) lives on its own dedicated thread — separate
    // from both networkExecutor and whatever thread calls stop().
    private val glThread = HandlerThread("CameraStreamerGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    // encoder is touched from two different threads: glHandler creates/uses
    // it, while stop() — called from the UI thread by the record button, or
    // from onDestroy() — releases it. Without encoderLock, a release()
    // landing mid-encode() tore down the codec's buffers while a frame was
    // still being drawn into them, throwing "IllegalStateException: buffer
    // is inaccessible" (confirmed on-device) instead of the clean, expected
    // "codec already released" — and since that could happen within the
    // first second of a session, before the encoder ever emitted its first
    // chunk, no frame from that session reached the PC at all.
    private val encoderLock = Any()
    private var encoder: H264Encoder? = null
    // Set once if H264Encoder's constructor itself throws (e.g. this device's
    // encoder rejects the configured resolution/bitrate combo), or CameraX
    // can't grant us a capture surface at all. Sticky rather than retried
    // per-frame: MediaCodec.createEncoderByType + configure isn't cheap to
    // fail repeatedly, and the outcome won't change frame to frame.
    private var encoderFailed = false
    private var encodeFailureStreak = 0

    // Requesting a low fps from the camera only ever *asks* the sensor to
    // slow down — plenty of devices have a hardware AE floor well above 1fps
    // and just clamp to their own minimum, so a low target fps has to be
    // enforced here instead of trusting the sensor.
    //
    // Deadline-based ("send when now >= deadline, then advance the deadline
    // by exactly one interval"), NOT "drop if arrived sooner than 1/fps
    // after the last send": that older scheme re-anchored the clock to each
    // frame's actual arrival time, so when the camera's cadence (~33.4ms at
    // its real ~29.9fps) sat within jitter of the throttle interval
    // (33.33ms for a 30fps target), frames arriving a fraction of a
    // millisecond "early" were dropped over and over — confirmed on-device
    // at only 35-40 of every 60 delivered frames actually sent (~18fps out
    // of a perfectly healthy 30fps capture), which is what a "1080p60"
    // session visibly stuttering at low fps actually was. Advancing the
    // deadline by the interval itself keeps the schedule locked to the
    // target rate regardless of per-frame arrival jitter; the max() floor
    // caps catch-up credit at one interval so a real stall (app pause,
    // camera hiccup) doesn't get "repaid" as a burst of back-to-back sends.
    private var nextSendDeadlineNanos = 0L
    private var targetFps = 30

    // Set by MainActivity right after it computes the real negotiated fps
    // range for the bound camera.
    //
    // Under CameraX that range comes from CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
    // (DeviceCapabilities.closestFpsRange), which on the S23 Ultra tops out at
    // 30 — so a user picking "60 fps" really does only get ~30 distinct
    // frames/sec through that backend. That is a CameraX/AOSP-metadata limit,
    // NOT a hardware one: the same sensor delivers a measured 59.8fps at 4K
    // through the Camera2 backend, which reads Samsung's vendor table instead
    // and passes the rate as a session parameter (see Camera2Capabilities).
    // The Camera2 path therefore sets this ceiling from what it actually
    // negotiated, not from the AE table. Before this
    // field existed, targetFps (and therefore Hello.fps, the encoder's
    // configured rate, and the PC's declared pyvirtualcam/OBS canvas fps)
    // stayed at the raw 60 the user picked — so the PC side believed it was
    // getting 60fps and duplicated every other frame to fill that cadence,
    // which is what made "60fps" look visibly worse in OBS than just picking
    // 30 (where declared and actual fps already matched, so nothing needed
    // duplicating). Clamping targetFps to what the sensor can truly deliver
    // keeps every downstream consumer honest about the real frame rate.
    @Volatile var cameraFpsCeiling: Int = Int.MAX_VALUE

    /** PC control token, required by the receiver for Wi-Fi senders. */
    @Volatile var authToken: String = ""

    // Updated live via the TransformationInfoListener registered in
    // onSurfaceRequested() — CameraX pushes a fresh TransformationInfo
    // whenever MainActivity's orientationEventListener changes the bound
    // VideoCapture's targetRotation, so physically rotating the phone while
    // recording corrects the encoded frame without us polling anything.
    @Volatile private var rotationDegrees = 0

    // Set when the encoder is created; sent in Hello so the receiver knows
    // which decoder to instantiate.
    @Volatile private var negotiatedCodec = "h264"

    /**
     * Per-stage instrumentation (capture/encode/send rates, latencies, drops)
     * — see [StreamMetrics]. Public so the capture backend can record camera-
     * side events (Camera2's capture callbacks) into the same window as the
     * encode/send events recorded here, which is the whole point: the numbers
     * are only diagnostic when they line up on one timeline.
     */
    val metrics = StreamMetrics(TAG)

    /** Bind this to CameraX's `VideoCapture.withOutput(...)` — see MainActivity.startCamera. */
    val videoOutput = StreamingVideoOutput { request -> onSurfaceRequested(request) }

    fun start() {
        stopped = false
        networkExecutor.execute { connectWithRetry() }
        startAudio()
    }

    /**
     * Starts capturing and sending the microphone, if this session has one.
     *
     * Audio produced before Hello has gone out is dropped rather than queued:
     * the PC cannot interpret a typed message until it knows audio was
     * negotiated, and a queue would only delay live audio to deliver samples
     * nobody wants by then.
     */
    private fun startAudio() {
        val session = audioSession ?: return
        val failure = session.start(
            onConfig = { config ->
                networkExecutor.execute {
                    val conn = connection ?: return@execute
                    if (!helloSent) return@execute
                    runCatching { conn.sendAudioConfig(config) }
                        .onFailure { Log.w(TAG, "audio config send failed", it) }
                }
            },
            onFrame = { frame ->
                networkExecutor.execute {
                    val conn = connection ?: return@execute
                    if (!helloSent) return@execute
                    runCatching { conn.sendAudioFrame(frame) }
                        .onFailure {
                            // Video's send failure already drives the reconnect;
                            // duplicating it here would race two reconnects
                            // through the same supervisor.
                            Log.w(TAG, "audio frame send failed", it)
                        }
                }
            },
        )
        if (failure != null) {
            Log.w(TAG, "microphone refused ($failure); streaming video only")
            onAudioFailure(failure)
        }
    }

    private fun connectWithRetry() {
        // Host resolved once per session, off the main thread — not per
        // attempt, so a flaky discovery broadcast on one retry can't make
        // later retries target a different host mid-session.
        val host = hostResolver()
        supervisor = ConnectionSupervisor(
            connect = { StreamConnection.connect(host, port) },
            closer = { it.close() },
            // Unlimited: this now also covers mid-stream reconnects (cable
            // pulled, adb restarted, PC asleep), where giving up strands the
            // session encoding frames into a void. The user stopping the
            // stream is what ends it.
            maxAttempts = 0,
            onStateChange = { state ->
                Log.i(TAG, "connection state: $state (host=$host:$port)")
                // A new socket means the receiver has no Hello yet.
                if (state == ConnectionState.RECONNECTING) helloSent = false
            },
        ).also { sup ->
            val fresh = sup.ensureConnected { stopped }
            connection = fresh
        }
    }

    /**
     * Re-establishes the socket after a send failure, on the network thread.
     *
     * Without this, a dropped connection left the session alive but mute:
     * frames kept being captured and encoded, every send threw, and nothing
     * ever tried to reconnect.
     */
    private fun reconnect() {
        val sup = supervisor ?: return
        sup.markLost()
        connection = null
        val fresh = sup.ensureConnected { stopped }
        connection = fresh
    }

    fun stop() {
        stopped = true
        runCatching { audioSession?.stop() }
            .onFailure { Log.w(TAG, "audio session did not stop cleanly", it) }
        networkExecutor.execute {
            supervisor?.shutdown() ?: connection?.close()
            connection = null
            helloSent = false
        }
        // Posted to glHandler, not called directly here: this runs on
        // whatever thread called stop() (the UI thread, via the record
        // button or onDestroy), but every GL/EGL call in encoder.release()
        // must happen on glThread — the same thread renderCameraFrame()
        // uses. EGL contexts are current on exactly one thread at a time;
        // even under encoderLock (which only keeps release() and encode()
        // from running *concurrently*, not from running on different
        // threads), calling eglMakeCurrent for the same context from the UI
        // thread right after glThread last held it current was throwing
        // "eglMakeCurrent failed" on-device. quitSafely() still lets this
        // already-queued task run before the thread actually exits.
        glHandler.post {
            synchronized(encoderLock) {
                encoder?.release()
                encoder = null
            }
        }
        glThread.quitSafely()
    }

    /**
     * CameraX calls this once per VideoCapture bind session (not per frame)
     * to ask for a Surface. [SurfaceRequest.getResolution] is what the
     * *camera* will actually produce (matching MainActivity's
     * ResolutionSelector, built from the user's raw Settings choice — see
     * [effectiveTarget]'s doc). The *encoder* is sized separately, to the
     * tier-capped target: a free-tier user picking "4K" in Settings still
     * only ever gets encoded at the free ceiling, same as before — the GL
     * renderer scales from the camera's (possibly larger) resolution down
     * to the encoder's via the draw viewport, same trick the old
     * CPU-buffer path used.
     */
    private fun onSurfaceRequested(request: SurfaceRequest) {
        startEncoderFor(
            cameraWidth = request.resolution.width,
            cameraHeight = request.resolution.height,
            onSurfaceReady = { surface ->
                request.setTransformationInfoListener(DIRECT_EXECUTOR) { info -> rotationDegrees = info.rotationDegrees }
                request.provideSurface(surface, DIRECT_EXECUTOR, Consumer { })
            },
            onFailed = { request.willNotProvideSurface() },
        )
    }

    /**
     * Camera2 backend entry point (see com.framecast.streamer.camera2.Camera2CaptureSource).
     *
     * Same encoder, same renderer, same Surface — the only difference from the
     * CameraX path above is who hands that Surface to the camera. CameraX does
     * it through SurfaceRequest.provideSurface; Camera2 does it through
     * OutputConfiguration. Nothing downstream of the Surface changes, which is
     * exactly why the two backends can share this code.
     *
     * Rotation is the one thing CameraX supplied for free (via
     * TransformationInfo) that Camera2 has to compute — see
     * [setRotationDegrees], which the caller drives from SENSOR_ORIENTATION
     * plus the device's physical orientation.
     */
    fun attachCamera2Surface(
        cameraWidth: Int,
        cameraHeight: Int,
        onSurfaceReady: (android.view.Surface) -> Unit,
        onFailed: () -> Unit,
    ) = startEncoderFor(cameraWidth, cameraHeight, onSurfaceReady, onFailed)

    /** Camera2 has no TransformationInfo; the caller computes the equivalent and pushes it here. */
    fun setRotationDegrees(degrees: Int) {
        rotationDegrees = degrees
    }

    /**
     * Creates the session's encoder sized to the tier-capped target, points the
     * GL renderer at the camera's actual output geometry, and hands the
     * resulting camera-input Surface back through [onSurfaceReady].
     *
     * Extracted verbatim from onSurfaceRequested so both capture backends run
     * the identical path — [cameraWidth]/[cameraHeight] are what the *camera*
     * will really produce, which is not necessarily the encoder's size (the
     * renderer scales between them via the draw viewport, same as before).
     */
    private fun startEncoderFor(
        cameraWidth: Int,
        cameraHeight: Int,
        onSurfaceReady: (android.view.Surface) -> Unit,
        onFailed: () -> Unit,
    ) {
        val profile = rewardManager.currentProfile()
        val (encWidth, encHeight, fps) = effectiveTarget(streamConfig, profile)
        targetFps = minOf(fps, cameraFpsCeiling)
        metrics.encoderSize = "${encWidth}x$encHeight"
        metrics.targetFps = targetFps

        glHandler.post {
            if (stopped) {
                onFailed()
                return@post
            }
            val activeEncoder = try {
                H264Encoder(
                    width = encWidth,
                    height = encHeight,
                    fps = targetFps,
                    bitrateBps = streamConfig.videoBitrateBps,
                    mimeType = mimeTypeFor(encWidth, encHeight),
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to create H.264 encoder at ${encWidth}x$encHeight " +
                    "${streamConfig.videoBitrateBps}bps — streaming cannot continue this session", e)
                encoderFailed = true
                onFailed()
                return@post
            }
            synchronized(encoderLock) { encoder = activeEncoder }
            // The PC picks its decoder from this, so it has to be whatever the
            // encoder really negotiated (h265 above 4K), never a constant.
            negotiatedCodec = activeEncoder.codecName

            val renderer = activeEncoder.renderer
            renderer.setCameraFrameSize(cameraWidth, cameraHeight)
            renderer.setOnFrameAvailableListener({ onFrameAvailable(profile) }, glHandler)

            Log.i(TAG, "session: ${metrics.describeSession()} camera=${cameraWidth}x$cameraHeight")
            onSurfaceReady(renderer.cameraInputSurface)
        }
    }

    /** Runs on glHandler for every camera frame the SurfaceTexture receives. */
    private fun onFrameAvailable(profile: StreamProfile) {
        // Unconditional, before anything that might `return` early below —
        // see EncoderSurfaceRenderer.updateCameraTexture's doc for why:
        // skipping this for a throttled/not-yet-connected frame starves the
        // camera's buffer queue and collapses capture fps over time.
        synchronized(encoderLock) {
            if (!stopped) encoder?.renderer?.updateCameraTexture()
        }

        metrics.onCameraFrame()
        metrics.logIfWindowElapsed()

        val conn = connection
        if (conn == null) {
            metrics.onDrop(StreamMetrics.Drop.NO_CONNECTION)
            return
        }
        if (encoderFailed || stopped) {
            if (encoderFailed) metrics.onDrop(StreamMetrics.Drop.ENCODER_FAILED)
            return
        }

        val now = System.nanoTime()
        val minIntervalNanos = 1_000_000_000L / targetFps.coerceAtLeast(1)
        if (now < nextSendDeadlineNanos) {
            metrics.onDrop(StreamMetrics.Drop.THROTTLED)
            return
        }
        nextSendDeadlineNanos = maxOf(nextSendDeadlineNanos + minIntervalNanos, now - minIntervalNanos)

        val presentationTimeUs = now / 1000

        // GL rendering + MediaCodec drain with no documented exception
        // contract; an uncaught throw here used to silently kill this
        // callback's future invocations (SurfaceTexture just stops getting
        // serviced), with nothing visible to the user — no crash dialog,
        // camera just never reached the PC.
        val chunks = try {
            val encodeStart = System.nanoTime()
            val result = synchronized(encoderLock) {
                if (stopped) emptyList() else encoder?.encode(rotationDegrees, profile.watermark, presentationTimeUs) ?: emptyList()
            }
            metrics.onEncoded(System.nanoTime() - encodeStart)
            result
        } catch (e: Exception) {
            metrics.onDrop(StreamMetrics.Drop.ENCODE_EXCEPTION)
            encodeFailureStreak++
            if (encodeFailureStreak == 1 || encodeFailureStreak % 60 == 0) {
                Log.e(TAG, "encode failed ($encodeFailureStreak in a row, likely stream stopping) — dropping frame", e)
            }
            emptyList()
        }
        if (chunks.isNotEmpty()) encodeFailureStreak = 0
        if (chunks.isEmpty()) return

        val (targetWidth, targetHeight, _) = effectiveTarget(streamConfig, profile)
        networkExecutor.execute {
            try {
                if (!helloSent) {
                    conn.sendHello(
                        Hello(
                            width = targetWidth,
                            height = targetHeight,
                            fps = targetFps,
                            quality = profile.quality,
                            watermark = profile.watermark,
                            deviceName = android.os.Build.MODEL,
                            codec = negotiatedCodec,
                            videoBitrateBps = streamConfig.videoBitrateBps,
                            syncObs = streamConfig.syncObs,
                            authToken = authToken,
                            audio = audioSession != null,
                            audioSampleRate = audioSession?.sampleRate ?: 0,
                            audioChannels = audioSession?.channelCount ?: 0,
                            audioBitrateBps = audioSession?.bitrateBps ?: 0,
                        ),
                    )
                    helloSent = true
                    // Re-sent on every (re)connect, not just the first: a
                    // reconnect gives the PC a brand new decoder, and one that
                    // never saw the AudioSpecificConfig decodes nothing at all.
                    audioSession?.codecConfig?.let { conn.sendAudioConfig(it) }
                }
                // Timed because a blocking write is the honest measure of
                // network backpressure: when the far side stops keeping up,
                // TCP's send buffer fills and this call starts costing
                // milliseconds it never cost before.
                val sendStart = System.nanoTime()
                var bytes = 0
                for (chunk in chunks) {
                    // Tagged only when audio was negotiated — otherwise the PC
                    // is reading bare frames and a type byte would land inside
                    // the picture.
                    if (audioSession != null) conn.sendVideoFrameTyped(chunk) else conn.sendFrame(chunk)
                    bytes += chunk.size
                }
                metrics.onSent(System.nanoTime() - sendStart, bytes)
            } catch (e: Exception) {
                metrics.onDrop(StreamMetrics.Drop.SEND_FAILED)
                Log.e(TAG, "send failed, reconnecting", e)
                // Already on networkExecutor, so this serialises with other
                // sends: no second reconnect can start while this one runs.
                if (!stopped) reconnect()
            }
        }
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { it.run() }

        /** min(user's Settings choice, reward-tier ceiling) — the tier can restrict, never expand. */
        fun effectiveTarget(streamConfig: StreamConfig, profile: StreamProfile): Triple<Int, Int, Int> {
            val (userWidth, userHeight) = StreamConfig.pixelSizeFor(streamConfig.qualityLabel)
            val (ceilWidth, ceilHeight, ceilFps) = FrameEncoder.tierCeiling(profile.quality)
            return Triple(
                minOf(userWidth, ceilWidth),
                minOf(userHeight, ceilHeight),
                minOf(streamConfig.fps, ceilFps),
            )
        }
    }
}
