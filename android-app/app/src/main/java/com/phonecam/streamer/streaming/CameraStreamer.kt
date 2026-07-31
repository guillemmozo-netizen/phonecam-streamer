package com.phonecam.streamer.streaming

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.phonecam.streamer.StreamConfig
import com.phonecam.streamer.audio.AudioCapture
import com.phonecam.streamer.rewards.RewardManager
import com.phonecam.streamer.rewards.StreamProfile
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
) {
    // Single-threaded, and that is load-bearing now that audio shares this
    // socket: it is the one thread that ever writes to the connection, so
    // video, audio and the hello can never interleave a length prefix with
    // somebody else's payload. It is also what serialises reconnects.
    //
    // Typed as ExecutorService, not Executor, so it can actually be shut
    // down. MainActivity builds a *new* CameraStreamer for every single
    // start/stop of streaming, and this thread is non-daemon — so every
    // session used to leave one behind alive forever. Fifty start/stop
    // cycles, fifty parked threads and their stacks, for the lifetime of the
    // process.
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraStreamerNet").apply { isDaemon = true }
    }
    @Volatile private var connection: StreamConnection? = null
    @Volatile private var supervisor: ConnectionSupervisor<StreamConnection>? = null
    @Volatile private var stopped = false
    private var helloSent = false

    /**
     * The microphone half of the session, or null when the user turned audio
     * off in Settings. [AudioStreamer.start] may still decide there is no
     * audio (permission refused, no usable microphone), in which case
     * [audioFormat] stays null and this session streams video exactly as it
     * did before audio existed.
     */
    private val audioStreamer: AudioStreamer? =
        if (!streamConfig.audioEnabled) null else AudioStreamer(
            captureConfig = AudioCapture.Config(
                sampleRate = streamConfig.audioSampleRate,
                channels = 2,
                noiseSuppression = streamConfig.noiseReduction,
            ),
            requestedCodec = streamConfig.audioCodec,
            bitrateBps = streamConfig.audioBitrateBps,
            windFilter = streamConfig.windFilter,
            send = { ptsUs, packet -> sendAudioPacket(ptsUs, packet) },
        )

    /**
     * The audio format actually negotiated, or null for a video-only session.
     *
     * Also the switch for the wire framing: non-null means every message
     * carries a MediaChunk header. Set once, on the network thread, before
     * the first hello can go out, and never changed afterwards — audio
     * failing mid-session stops the audio but must not change the framing
     * under a receiver that has already been told what to expect.
     */
    @Volatile private var audioFormat: AudioStreamer.Format? = null

    /**
     * Set when a reconnect gives us a socket that has never seen the video
     * encoder's SPS/PPS. Read and cleared on the network thread, where the
     * next send re-issues the config ahead of the frame.
     */
    @Volatile private var needsCodecConfig = false

    /**
     * nanoTime at which the write currently in flight began, or 0 when the
     * network thread is idle. Written by the network thread, read by the GL
     * thread's stall check — see [checkForSendStall].
     */
    @Volatile private var sendInFlightSinceNanos = 0L

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

    // What the encoder was actually built with, captured once at creation.
    //
    // Hello used to recompute these from effectiveTarget() at send time,
    // which is a different thing: the encoder's size is fixed for the
    // session, but the reward tier feeding effectiveTarget can change while
    // it runs. On a reconnect that recomputation could announce a resolution
    // the encoder is not producing. Reading back what was built removes the
    // possibility — and gives the audio path the same numbers without
    // duplicating the calculation.
    @Volatile private var encoderWidth = 0
    @Volatile private var encoderHeight = 0
    @Volatile private var sessionProfile: StreamProfile? = null

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
        networkExecutor.execute {
            // Before connecting, not after: Hello has to announce the real
            // audio format (which may not be the one requested — see
            // AudioStreamer.start), and Hello goes out on the first send
            // after this connects. Opening the microphone here also keeps it
            // off the UI thread without needing a second one.
            audioFormat = audioStreamer?.start()
            connectWithRetry()
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
                // A new socket means a receiver that has been told nothing:
                // no Hello, and — the part that used to be missed — no
                // SPS/PPS either. Its decoder is brand new, so without the
                // config re-sent it decodes precisely nothing, which looked
                // exactly like a dead camera rather than a dropped link.
                if (state == ConnectionState.RECONNECTING) {
                    helloSent = false
                    needsCodecConfig = true
                }
            },
        ).also { sup ->
            val fresh = sup.ensureConnected { stopped }
            connection = fresh
        }
    }

    /**
     * Breaks a write that has been blocked for too long.
     *
     * Runs on the GL thread, which is already being called once per camera
     * frame — so this costs a volatile read and no extra thread. It has to
     * run somewhere *other* than the network thread by definition: the whole
     * problem is that the network thread is stuck inside a write that will
     * never return on its own.
     *
     * Closing the socket from here makes that write throw, which puts the
     * session on the ordinary reconnect path instead of leaving it silently
     * dead. Before this, a PC that suspended mid-stream took video, audio
     * and reconnection with it, with no error anywhere and no recovery short
     * of the user stopping and restarting the stream.
     */
    private fun checkForSendStall() {
        val startedAt = sendInFlightSinceNanos
        if (startedAt == 0L) return
        val stalledMs = (System.nanoTime() - startedAt) / 1_000_000
        if (stalledMs < SEND_STALL_TIMEOUT_MS) return

        sendInFlightSinceNanos = 0L
        Log.w(TAG, "a send has been blocked for ${stalledMs}ms — dropping the connection to force a reconnect")
        metrics.onDrop(StreamMetrics.Drop.SEND_FAILED)
        // markLost() closes the socket, which is what unblocks the write.
        // The reconnect itself still happens on the network thread, once
        // that write has thrown and unwound.
        supervisor?.markLost()
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
        if (fresh != null) {
            // The new decoder on the far side needs an IDR to start from, and
            // the next one is up to I_FRAME_INTERVAL_SECONDS away — that is
            // two seconds of black after every reconnect, on top of whatever
            // the reconnect itself cost. Asking for one costs a single larger
            // frame.
            synchronized(encoderLock) {
                if (!stopped) encoder?.requestKeyFrame()
            }
        }
    }

    fun stop() {
        stopped = true
        // Stops the capture thread, so nothing more can be posted here. It
        // blocks briefly (see AudioStreamer.stop), which is why it is not on
        // the caller's thread.
        submitIfAccepting(networkExecutor) { audioStreamer?.stop() }
        submitIfAccepting(networkExecutor) {
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

        // Shutdown is itself queued, so it runs *after* the audio stop above
        // has completed and joined the capture thread. Calling it inline here
        // was a crash: shutdown() only stops new tasks being accepted, it does
        // not wait for the queued ones, so the still-running capture thread's
        // next packet hit a shutting-down executor and took the app down with
        // a RejectedExecutionException (see ExecutorSubmit). Queueing it means
        // by the time it runs there is no thread left to post anything.
        //
        // The shutdown itself is what stops this session's network thread
        // outliving the session — without it, a user toggling the record
        // button all evening accumulated one live thread per press.
        submitIfAccepting(networkExecutor) { networkExecutor.shutdown() }
    }

    /** Blocks until this session's network thread has finished. Test/diagnostic hook. */
    fun awaitShutdown(timeoutMs: Long = 2_000): Boolean =
        networkExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

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
    // Rotation the current capture source's texture matrix already carries —
    // 0 for CameraX (its processing node consumes the HAL transform hint),
    // the sensor mounting for direct Camera2 (the hint reaches our
    // SurfaceTexture untouched). Pushed into the renderer per session.
    @Volatile private var sourceTextureRotation = 0

    private fun onSurfaceRequested(request: SurfaceRequest) {
        sourceTextureRotation = 0
        startEncoderFor(
            cameraWidth = request.resolution.width,
            cameraHeight = request.resolution.height,
            onSurfaceReady = { surface ->
                request.setTransformationInfoListener(DIRECT_EXECUTOR) { info ->
                    rotationDegrees = info.rotationDegrees
                    // The other half of TransformationInfo, which used to be
                    // discarded: which rect of the buffer is the composition.
                    // CameraX only pre-crops when its internal processing node
                    // is in the pipeline; otherwise the buffer is the full
                    // sensor frame and this rect is the only thing standing
                    // between the stream and an uncropped composition. Pushed
                    // to the live renderer so mid-session updates (a fresh
                    // TransformationInfo follows every targetRotation change)
                    // land on the next frame.
                    val crop = info.cropRect
                    synchronized(encoderLock) {
                        encoder?.renderer?.setContentCrop(crop.left, crop.top, crop.right, crop.bottom)
                    }
                    // Forensic geometry line: the one datum no log carried and
                    // three validation rounds needed — what rotation and crop
                    // this session ACTUALLY applies, and the cover scale that
                    // follows from them. Fires only when CameraX pushes a new
                    // TransformationInfo (a handful of times per session).
                    val scale = com.phonecam.streamer.streaming.gl.ContentGeometry.coverScale(
                        crop.width(), crop.height(), info.rotationDegrees, encoderWidth, encoderHeight,
                    )
                    Log.i(
                        TAG,
                        "geom[camerax]: θ=${info.rotationDegrees} " +
                            "crop=${crop.left},${crop.top}→${crop.right},${crop.bottom} " +
                            "buffer=${request.resolution.width}x${request.resolution.height} " +
                            "encoder=${encoderWidth}x$encoderHeight scale=${scale[0]}x${scale[1]}",
                    )
                }
                request.provideSurface(surface, DIRECT_EXECUTOR, Consumer { })
            },
            onFailed = { request.willNotProvideSurface() },
        )
    }

    /**
     * Camera2 backend entry point (see com.phonecam.streamer.camera2.Camera2CaptureSource).
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
        textureRotationDegrees: Int,
        onSurfaceReady: (android.view.Surface) -> Unit,
        onFailed: () -> Unit,
    ) {
        sourceTextureRotation = textureRotationDegrees
        startEncoderFor(cameraWidth, cameraHeight, onSurfaceReady, onFailed)
    }

    /** Camera2 has no TransformationInfo; the caller computes the equivalent and pushes it here. */
    fun setRotationDegrees(degrees: Int) {
        rotationDegrees = degrees
        // Forensic geometry line, camera2 flavor: this backend has no ViewPort
        // so the crop is always the full capture; θ and the texture-carried
        // rotation are the whole story.
        Log.i(
            TAG,
            "geom[camera2]: θ=$degrees texRot=$sourceTextureRotation " +
                "vertex=${com.phonecam.streamer.streaming.gl.ContentGeometry.vertexRotation(degrees, sourceTextureRotation)} " +
                "crop=full encoder=${encoderWidth}x$encoderHeight",
        )
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
        encoderWidth = encWidth
        encoderHeight = encHeight
        sessionProfile = profile
        metrics.encoderSize = "${encWidth}x$encHeight"
        metrics.targetFps = targetFps

        glHandler.post {
            if (stopped) {
                onFailed()
                return@post
            }
            // A rebind mid-session (flip camera, return from Settings)
            // delivers a fresh SurfaceRequest and lands here a second time.
            // The previous encoder used to be overwritten without release(),
            // leaking one hardware MediaCodec + one EGL context per rebind —
            // and hardware codec instances are a small global pool, so a few
            // Settings round-trips could exhaust it and every later session
            // died at the create call below until the process was killed
            // ("works again after restarting the app"). Release BEFORE
            // creating, not after: at the pool limit the new create only
            // succeeds because this freed a slot first. Same thread and lock
            // discipline as stop(): this block runs on glThread, where every
            // EGL teardown in release() must happen.
            synchronized(encoderLock) {
                encoder?.let {
                    Log.i(TAG, "releasing previous encoder before rebind replacement")
                    it.release()
                    encoder = null
                }
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
            renderer.setTextureRotationDegrees(sourceTextureRotation)
            renderer.setOnFrameAvailableListener({ onFrameAvailable(profile) }, glHandler)

            Log.i(TAG, "session: ${metrics.describeSession()} camera=${cameraWidth}x$cameraHeight")
            onSurfaceReady(renderer.cameraInputSurface)
        }
    }

    // Watchdog signal: when the camera last delivered a frame. 0 until the
    // first one arrives, so sessions whose capture never starts (encoder
    // failure, permission) are NOT reported as frozen — they have their own
    // failure paths. Measured need: Samsung's CameraService can cut a live
    // client from outside ("block for PID <app>", observed on-device freezing
    // a healthy 30fps session solid for 80 seconds) and nothing in-process
    // gets an error callback on the capture path — the frames just stop.
    @Volatile private var lastFrameAtMs = 0L

    /** Milliseconds since the camera last delivered a frame, or -1 before the first one. */
    fun millisSinceLastFrame(): Long =
        if (lastFrameAtMs == 0L) -1L
        else android.os.SystemClock.elapsedRealtime() - lastFrameAtMs

    /** Runs on glHandler for every camera frame the SurfaceTexture receives. */
    private fun onFrameAvailable(profile: StreamProfile) {
        lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
        // Unconditional, before anything that might `return` early below —
        // see EncoderSurfaceRenderer.updateCameraTexture's doc for why:
        // skipping this for a throttled/not-yet-connected frame starves the
        // camera's buffer queue and collapses capture fps over time.
        synchronized(encoderLock) {
            if (!stopped) encoder?.renderer?.updateCameraTexture()
        }

        metrics.onCameraFrame()
        metrics.logIfWindowElapsed()
        checkForSendStall()

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

        // Same guard as the audio path: the GL thread that calls this also
        // outlives the network thread at teardown (quitSafely lets queued
        // frames finish), so a frame can land on a shutting-down executor.
        submitIfAccepting(networkExecutor) {
            // A reconnect may have happened while this task sat in the
            // queue. Sending into the dead socket would throw and trigger a
            // second, pointless reconnect on a connection that is already
            // healthy.
            if (stopped || connection !== conn) return@submitIfAccepting
            try {
                sendHelloIfNeeded(conn)
                // Timed because a blocking write is the honest measure of
                // network backpressure: when the far side stops keeping up,
                // TCP's send buffer fills and this call starts costing
                // milliseconds it never cost before.
                val sendStart = System.nanoTime()
                sendInFlightSinceNanos = sendStart
                var bytes = 0
                for (chunk in prependCodecConfigIfNeeded(chunks)) {
                    sendVideoChunk(conn, chunk)
                    bytes += chunk.data.size
                }
                sendInFlightSinceNanos = 0L
                metrics.onSent(System.nanoTime() - sendStart, bytes)
            } catch (e: Exception) {
                sendInFlightSinceNanos = 0L
                metrics.onDrop(StreamMetrics.Drop.SEND_FAILED)
                Log.e(TAG, "send failed, reconnecting", e)
                // Already on networkExecutor, so this serialises with other
                // sends: no second reconnect can start while this one runs.
                if (!stopped) reconnect()
            }
        }
    }

    /**
     * Re-issues the encoder's SPS/PPS ahead of [chunks] when the current
     * socket has never seen it — see [H264Encoder.codecConfig]. A no-op on
     * the overwhelmingly common path, where the flag is clear.
     *
     * Runs on the network thread, the only one that reads or clears the flag.
     */
    private fun prependCodecConfigIfNeeded(chunks: List<EncodedChunk>): List<EncodedChunk> {
        if (!needsCodecConfig) return chunks
        needsCodecConfig = false
        // Already leading with config (the encoder happened to re-emit it, as
        // some do on every keyframe) means there is nothing to add.
        if (chunks.firstOrNull()?.isConfig == true) return chunks
        val config = synchronized(encoderLock) { encoder?.codecConfig } ?: return chunks
        Log.i(TAG, "re-sending codec config (${config.data.size} bytes) to the reconnected receiver")
        return listOf(config) + chunks
    }

    /** Bare payload or MediaChunk-tagged, per what Hello announced. */
    private fun sendVideoChunk(conn: StreamConnection, chunk: EncodedChunk) {
        if (audioFormat == null) {
            conn.sendFrame(chunk.data)
        } else {
            conn.sendVideoChunk(chunk.presentationTimeUs, chunk.data)
        }
    }

    /**
     * Sends the session's Hello if this socket has not had one yet.
     *
     * Only ever called from the network thread, which is why [helloSent]
     * needs no synchronisation.
     *
     * Only the *video* path may call this, and that is load-bearing rather
     * than stylistic: Hello describes the video geometry, and audio starts
     * capturing in start() — well before CameraX asks for a surface and the
     * encoder is built. An audio packet arriving first would send a Hello
     * with width=0/height=0, which the receiver rejects outright as out of
     * range (protocol.py bounds them at 16..8192) and drops the connection
     * for. See [sendAudioPacket], which waits instead.
     */
    private fun sendHelloIfNeeded(conn: StreamConnection) {
        if (helloSent) return
        val audio = audioFormat
        val profile = sessionProfile ?: rewardManager.currentProfile()
        conn.sendHello(
            Hello(
                width = encoderWidth,
                height = encoderHeight,
                fps = targetFps,
                quality = profile.quality,
                watermark = profile.watermark,
                deviceName = android.os.Build.MODEL,
                codec = negotiatedCodec,
                videoBitrateBps = streamConfig.videoBitrateBps,
                syncObs = streamConfig.syncObs,
                authToken = authToken,
                audioCodec = audio?.codec.orEmpty(),
                audioSampleRate = audio?.sampleRate ?: 0,
                audioChannels = audio?.channels ?: 0,
                audioBitrateBps = audio?.bitrateBps ?: 0,
            ),
        )
        helloSent = true
    }

    /**
     * Queues one encoded audio packet for the shared socket.
     *
     * Called from AudioStreamer's capture thread, so the actual write is
     * posted to the network thread — see [networkExecutor]'s doc for why
     * that single owner matters.
     *
     * Audio that belongs to a connection we no longer have is dropped rather
     * than queued. Video can afford to arrive late because the receiver skips
     * to the freshest frame; audio cannot, and a burst of packets from before
     * a reconnect would play out seconds behind the picture and desync
     * everything after it. Silence across the gap is the correct outcome.
     */
    private fun sendAudioPacket(ptsUs: Long, packet: ByteArray) {
        val conn = connection ?: return
        // submitIfAccepting, not execute: the capture thread outlives the
        // network thread by a few milliseconds at teardown, and a rejected
        // packet there is a normal race, not an error. Letting it throw killed
        // the capture thread — and with it the app — on every stop.
        submitIfAccepting(networkExecutor) {
            if (stopped || connection !== conn) return@submitIfAccepting
            // Audio never sends the Hello — it waits for video to. Capture
            // starts in start(), several hundred milliseconds before CameraX
            // hands over a surface and the encoder (and with it the
            // resolution Hello has to state) exists at all. See
            // sendHelloIfNeeded. The few packets dropped here are the first
            // tens of milliseconds of a session, before there is any picture
            // to be in sync with.
            if (!helloSent) return@submitIfAccepting
            try {
                sendInFlightSinceNanos = System.nanoTime()
                conn.sendAudioChunk(ptsUs, packet)
                sendInFlightSinceNanos = 0L
            } catch (e: Exception) {
                sendInFlightSinceNanos = 0L
                Log.e(TAG, "audio send failed, reconnecting", e)
                if (!stopped) reconnect()
            }
        }
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { it.run() }

        /**
         * min(user's Settings choice, reward-tier ceiling) — the tier can
         * restrict, never expand.
         *
         * The size comes from [StreamConfig.outputSizeFor], not from the
         * resolution preset alone: the composition ratio is what the ViewPort
         * crops the camera to, so it is what the encoder has to be shaped like
         * for the frame to arrive unstretched. See that function's doc.
         *
         * The ceiling is a PIXEL BUDGET (the tier table's WxH defines how many
         * pixels, not a box shape) applied with [StreamConfig.fitPixelBudget]:
         * aspect-neutral, so a vertical or square composition buys the same
         * quality as the landscape one. The previous box clamp priced 9:16 at
         * 608x1080 on the free tier — the audited root cause of "vertical is
         * far more pixelated". For 16:9 requests nothing changes.
         */
        fun effectiveTarget(streamConfig: StreamConfig, profile: StreamProfile): Triple<Int, Int, Int> {
            val (userWidth, userHeight) =
                StreamConfig.outputSizeFor(streamConfig.qualityLabel, streamConfig.aspectRatio)
            val (ceilWidth, ceilHeight, ceilFps) = FrameEncoder.tierCeiling(profile.quality)
            val (width, height) =
                StreamConfig.fitPixelBudget(userWidth, userHeight, ceilWidth.toLong() * ceilHeight)
            return Triple(width, height, minOf(streamConfig.fps, ceilFps))
        }
    }
}
