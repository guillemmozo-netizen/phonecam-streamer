package com.phonecam.streamer.streaming

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.phonecam.streamer.StreamConfig
import com.phonecam.streamer.rewards.RewardManager
import com.phonecam.streamer.rewards.StreamProfile
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "CameraStreamer"

// The PC side is often not quite ready the instant streaming starts — a USB
// cable was just plugged in and control_server's watcher (2s poll) hasn't
// pushed the adb reverse tunnels yet, or Wi-Fi services are still spinning
// up — so a single connect attempt used to fail permanently with no retry.
// A handful of retries spread over a few seconds self-heals that race
// without the user having to stop/start streaming again.
private const val MAX_CONNECT_ATTEMPTS = 5
private const val RETRY_DELAY_MS = 2000L
private const val RETRY_POLL_MS = 200L

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
    private val networkExecutor: Executor = Executors.newSingleThreadExecutor()
    @Volatile private var connection: StreamConnection? = null
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

    // Set by MainActivity right after it computes the real negotiated
    // CONTROL_AE_TARGET_FPS_RANGE for the bound camera (DeviceCapabilities.
    // closestFpsRange) — confirmed via `adb shell dumpsys media.camera` that
    // every camera id on a real S23 Ultra tops out at 30fps in that range
    // regardless of what's requested, so a user picking "60 fps" in Settings
    // still only gets ~30 distinct frames/sec from the sensor. Before this
    // field existed, targetFps (and therefore Hello.fps, the encoder's
    // configured rate, and the PC's declared pyvirtualcam/OBS canvas fps)
    // stayed at the raw 60 the user picked — so the PC side believed it was
    // getting 60fps and duplicated every other frame to fill that cadence,
    // which is what made "60fps" look visibly worse in OBS than just picking
    // 30 (where declared and actual fps already matched, so nothing needed
    // duplicating). Clamping targetFps to what the sensor can truly deliver
    // keeps every downstream consumer honest about the real frame rate.
    @Volatile var cameraFpsCeiling: Int = Int.MAX_VALUE

    // Updated live via the TransformationInfoListener registered in
    // onSurfaceRequested() — CameraX pushes a fresh TransformationInfo
    // whenever MainActivity's orientationEventListener changes the bound
    // VideoCapture's targetRotation, so physically rotating the phone while
    // recording corrects the encoded frame without us polling anything.
    @Volatile private var rotationDegrees = 0

    // Diagnostics only: periodic logging to see exactly where frame budget
    // goes on-device — camera delivery rate vs. how long encode() (GL
    // render + MediaCodec drain) actually takes per call.
    private var frameCallCount = 0
    private var encodeCallCount = 0
    private var encodeTimeTotalNanos = 0L
    private var diagWindowStartNanos = 0L

    /** Bind this to CameraX's `VideoCapture.withOutput(...)` — see MainActivity.startCamera. */
    val videoOutput = StreamingVideoOutput { request -> onSurfaceRequested(request) }

    fun start() {
        stopped = false
        networkExecutor.execute { connectWithRetry() }
    }

    private fun connectWithRetry() {
        // Resolved once per session, off the main thread (this already runs
        // on networkExecutor) — not per attempt, so a flaky discovery
        // broadcast on one retry can't make later retries target a
        // different host mid-session.
        val host = hostResolver()
        var attempt = 0
        while (!stopped && attempt < MAX_CONNECT_ATTEMPTS) {
            attempt++
            try {
                connection = StreamConnection.connect(host, port)
                Log.i(TAG, "connected to receiver at $host:$port (attempt $attempt/$MAX_CONNECT_ATTEMPTS)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt $attempt/$MAX_CONNECT_ATTEMPTS to $host:$port failed: ${e.message}")
            }
            if (stopped || attempt >= MAX_CONNECT_ATTEMPTS) return
            var waited = 0L
            while (waited < RETRY_DELAY_MS && !stopped) {
                Thread.sleep(RETRY_POLL_MS)
                waited += RETRY_POLL_MS
            }
        }
    }

    fun stop() {
        stopped = true
        networkExecutor.execute {
            connection?.close()
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
        val profile = rewardManager.currentProfile()
        val (encWidth, encHeight, fps) = effectiveTarget(streamConfig, profile)
        targetFps = minOf(fps, cameraFpsCeiling)
        val cameraWidth = request.resolution.width
        val cameraHeight = request.resolution.height

        glHandler.post {
            if (stopped) {
                request.willNotProvideSurface()
                return@post
            }
            val activeEncoder = try {
                H264Encoder(
                    width = encWidth,
                    height = encHeight,
                    fps = targetFps,
                    bitrateBps = streamConfig.videoBitrateBps,
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to create H.264 encoder at ${encWidth}x$encHeight " +
                    "${streamConfig.videoBitrateBps}bps — streaming cannot continue this session", e)
                encoderFailed = true
                request.willNotProvideSurface()
                return@post
            }
            synchronized(encoderLock) { encoder = activeEncoder }

            val renderer = activeEncoder.renderer
            renderer.setCameraFrameSize(cameraWidth, cameraHeight)
            renderer.setOnFrameAvailableListener({ onFrameAvailable(profile) }, glHandler)

            request.setTransformationInfoListener(DIRECT_EXECUTOR) { info -> rotationDegrees = info.rotationDegrees }
            request.provideSurface(renderer.cameraInputSurface, DIRECT_EXECUTOR, Consumer { })
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

        frameCallCount++
        if (diagWindowStartNanos == 0L) diagWindowStartNanos = System.nanoTime()
        val diagElapsedNanos = System.nanoTime() - diagWindowStartNanos
        if (diagElapsedNanos >= 2_000_000_000L) {
            val deliveryFps = frameCallCount / (diagElapsedNanos / 1_000_000_000.0)
            val avgEncodeMs = if (encodeCallCount > 0) (encodeTimeTotalNanos / encodeCallCount) / 1_000_000.0 else 0.0
            Log.i(
                TAG,
                "diag: camera delivers ${"%.1f".format(deliveryFps)} fps " +
                    "($frameCallCount calls/${diagElapsedNanos / 1_000_000}ms), " +
                    "encode() sent=$encodeCallCount avg=${"%.1f".format(avgEncodeMs)}ms/call",
            )
            frameCallCount = 0
            encodeCallCount = 0
            encodeTimeTotalNanos = 0L
            diagWindowStartNanos = System.nanoTime()
        }

        val conn = connection ?: return
        if (encoderFailed || stopped) return

        val now = System.nanoTime()
        val minIntervalNanos = 1_000_000_000L / targetFps.coerceAtLeast(1)
        if (now < nextSendDeadlineNanos) return
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
            encodeCallCount++
            encodeTimeTotalNanos += System.nanoTime() - encodeStart
            result
        } catch (e: Exception) {
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
                            codec = "h264",
                            videoBitrateBps = streamConfig.videoBitrateBps,
                            syncObs = streamConfig.syncObs,
                        ),
                    )
                    helloSent = true
                }
                for (chunk in chunks) conn.sendFrame(chunk)
            } catch (e: Exception) {
                Log.e(TAG, "send failed, dropping frame", e)
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
