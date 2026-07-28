package com.phonecam.streamer.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.phonecam.streamer.streaming.StreamMetrics

private const val TAG = "Camera2CaptureSource"

/**
 * Experimental Camera2 capture backend — the half of the streaming pipeline
 * that CameraX cannot do on this hardware.
 *
 * CameraX validates every requested frame rate against
 * CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES and every requested size against
 * StreamConfigurationMap. On the reference device both tables under-report
 * (see [Camera2Capabilities]), so CameraX will not even ask the HAL for 4K60
 * — the ceiling is client-side, not hardware. This class asks directly.
 *
 * The one thing that makes it work, confirmed by measurement: the fps range
 * goes in as a **session parameter**, via
 * [SessionConfiguration.setSessionParameters], so the HAL sees it while it is
 * still choosing its pipeline. Setting the same key on the repeating request
 * afterwards (what Camera2Interop does under CameraX) is too late — the
 * session has already been configured for 30fps by then.
 *
 * Everything downstream is untouched: the [encoderSurface] handed to [start]
 * is the very same [android.view.Surface] the CameraX path uses
 * (EncoderSurfaceRenderer.cameraInputSurface), so the GL renderer, the
 * MediaCodec encoder, the frame throttle and the network protocol don't know
 * or care which backend produced the frames.
 *
 * Lifecycle: one instance per streaming session. [start] is asynchronous and
 * reports through [Listener]; [stop] is idempotent. All camera callbacks run
 * on this class's own thread, never the caller's.
 */
@RequiresApi(Build.VERSION_CODES.P)
class Camera2CaptureSource(
    private val context: Context,
    private val cameraId: String,
    private val captureSize: Size,
    private val desiredFps: Int,
    private val metrics: StreamMetrics,
    private val listener: Listener,
    // Non-null when the requested size only exists on a physical sub-camera
    // (8K lives on id 5 on an S23 Ultra). The output is then routed there via
    // OutputConfiguration.setPhysicalCameraId - the only working route, since
    // that sensor's own top-level id is not enumerable.
    private val physicalCameraId: String? = null,
) {

    interface Listener {
        /** The repeating request is running. [fpsRange] is what was negotiated, not what was asked for. */
        fun onStarted(size: Size, fpsRange: Range<Int>)

        /** Terminal failure — nothing is capturing. The caller should fall back to CameraX. */
        fun onFailed(stage: String, reason: String)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("Camera2Source").apply { start() }
    private val handler = Handler(thread.looper)

    // Every camera callback is delivered here. Deliberately NOT the thread
    // that calls start()/stop(): the probe that validated this flow deadlocked
    // exactly once by sharing them, with the open callback queued behind the
    // caller waiting for it.
    private val callbackExecutor = java.util.concurrent.Executor { handler.post(it) }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var stopped = false
    private var captureFailures = 0

    /** Clockwise degrees the sensor's output is rotated relative to the device's natural orientation. */
    val sensorOrientation: Int =
        Camera2Capabilities.characteristicsOrNull(context, cameraId)
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    @SuppressLint("MissingPermission")
    fun start(encoderSurface: Surface, previewSurface: Surface?) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onFailed("permission", "CAMERA permission not granted")
            return
        }
        val chars = Camera2Capabilities.characteristicsOrNull(context, physicalCameraId ?: cameraId)
        if (chars == null) {
            listener.onFailed("characteristics", "camera $cameraId not available")
            return
        }
        val fpsRange = Camera2Capabilities.sessionFpsRange(chars, captureSize, desiredFps)
        if (fpsRange == null) {
            listener.onFailed("capability", "no fps range for ${captureSize.width}x${captureSize.height}")
            return
        }
        Log.i(TAG, "opening camera $cameraId" +
            (physicalCameraId?.let { " (physical $it)" } ?: "") +
            " for ${captureSize.width}x${captureSize.height} @ $fpsRange " +
            "(${Camera2Capabilities.describe(chars, captureSize)})")

        try {
            cameraManager.openCamera(cameraId, callbackExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    if (stopped) { camera.close(); return }
                    configure(camera, encoderSurface, previewSurface, fpsRange)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    if (!stopped) listener.onFailed("open", "camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    if (!stopped) listener.onFailed("open", "camera error $error")
                }
            })
        } catch (e: Exception) {
            listener.onFailed("open", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun configure(
        camera: CameraDevice,
        encoderSurface: Surface,
        previewSurface: Surface?,
        fpsRange: Range<Int>,
    ) {
        val outputs = buildList {
            add(OutputConfiguration(encoderSurface).apply {
                if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
            })
            // Only the encoder stream is routed to the physical sensor; a
            // viewfinder from the logical camera in the same session mixes two
            // different sources, so the caller disables it when routing.
            previewSurface?.let { add(OutputConfiguration(it)) }
        }

        // THE mechanism. Present at session-configuration time, the HAL picks
        // its 60fps video pipeline; absent, it configures the 30fps default
        // pipeline and no later request can raise it.
        val params = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }.build()

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            callbackExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    if (stopped) { safeClose(configured); return }
                    startRepeating(camera, configured, encoderSurface, previewSurface, fpsRange)
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    if (!stopped) listener.onFailed("configure", "session configuration failed")
                }
            },
        // Named `params` rather than `sessionParameters`: inside apply, an
        // identically-named local would resolve to the property itself and
        // silently self-assign, leaving the session parameters null — which
        // fails softly, as a 30fps session.
        ).apply { sessionParameters = params }

        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            listener.onFailed("configure", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startRepeating(
        camera: CameraDevice,
        session: CameraCaptureSession,
        encoderSurface: Surface,
        previewSurface: Surface?,
        fpsRange: Range<Int>,
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoderSurface)
                previewSurface?.let { addTarget(it) }
                // Repeated here as well as in the session parameters: the
                // session parameter selects the pipeline, this keeps AE from
                // wandering off the rate once it's running.
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()

            session.setRepeatingRequest(request, captureCallback, handler)
            metrics.captureSize = "${captureSize.width}x${captureSize.height}"
            metrics.negotiatedFps = "$fpsRange"
            listener.onStarted(captureSize, fpsRange)
            Log.i(TAG, "capture running: ${captureSize.width}x${captureSize.height} @ $fpsRange " +
                "(preview stream: ${previewSurface != null})")
        } catch (e: Exception) {
            listener.onFailed("repeating", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            metrics.onCaptureCompleted()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            captureFailures++
            // Rate-limited: a genuinely broken session fails every frame, and
            // 60 log lines a second buries everything else.
            if (captureFailures == 1 || captureFailures % 60 == 0) {
                Log.w(TAG, "capture failed ($captureFailures so far, reason=${failure.reason})")
            }
        }
    }

    fun stop() {
        stopped = true
        handler.post {
            session?.let { safeClose(it) }
            session = null
            try { device?.close() } catch (e: Exception) { Log.w(TAG, "device close failed", e) }
            device = null
            thread.quitSafely()
        }
    }

    private fun safeClose(s: CameraCaptureSession) {
        // stopRepeating throws if the device already went away (another client
        // preempted us, the user unplugged, etc.) — that's a normal teardown
        // path, not an error worth surfacing.
        try { s.stopRepeating() } catch (e: Exception) { Log.d(TAG, "stopRepeating during teardown: ${e.message}") }
        try { s.close() } catch (e: Exception) { Log.d(TAG, "session close during teardown: ${e.message}") }
    }

    companion object {
        /**
         * Can this device stream [size]@[fps] through the Camera2 backend?
         *
         * SessionConfiguration (and therefore session parameters, and therefore
         * the whole point of this backend) is API 28+. Below that, or on a
         * device whose tables don't advertise the combination, the caller
         * stays on CameraX.
         */
        fun isSupported(context: Context, cameraId: String, size: Size, fps: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val physicalId = Camera2Capabilities.physicalIdFor(context, cameraId, size)
            val chars = Camera2Capabilities.characteristicsOrNull(context, physicalId ?: cameraId)
                ?: return false
            if (!Camera2Capabilities.isSizeSupported(chars, size)) return false
            val range = Camera2Capabilities.sessionFpsRange(chars, size, fps) ?: return false
            return range.upper >= fps
        }
    }
}
