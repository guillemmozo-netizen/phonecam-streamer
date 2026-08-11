package com.phonecam.streamer.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Camera2Probe"
private const val MEASURE_SECONDS = 10L

/**
 * Debug-only, throwaway diagnostic. Answers one question with measured numbers:
 * can a plain Camera2 client on this device get 4K60 and 8K30, when the AOSP
 * metadata (CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, StreamConfigurationMap)
 * says it cannot but Samsung's vendor tables say it can?
 *
 * Lives in src/debug so it never reaches a release build, and deliberately
 * shares NOTHING with the production capture path (CameraX / CameraStreamer /
 * MainActivity) — it opens its own CameraDevice so a result here can't be
 * explained away by anything the app already does.
 *
 * Launch:
 *   adb shell am start -n com.framecast.app/.probe.Camera2ProbeActivity
 * Results: logcat tag Camera2Probe, on screen, and
 *   /sdcard/Android/data/com.framecast.app/files/camera2_probe.txt
 */
// Lint: debug-only measurement harness, launched by hand over adb on a device
// that already granted CAMERA (the app asked at first run). It targets the
// API 28+/29+ Camera2 session APIs on purpose — that is what it measures —
// and never ships in a release build (src/debug).
@android.annotation.SuppressLint("NewApi", "MissingPermission")
class Camera2ProbeActivity : Activity() {

    private lateinit var textView: TextView
    private val report = StringBuilder()

    // Two threads on purpose. probeThread drives the sequence and blocks on
    // latches/sleeps; callbackThread services every camera, session and
    // ImageReader callback. They must not be the same thread — with one thread
    // the open/configure callbacks are queued behind the very await() that is
    // waiting for them, and every open times out.
    private lateinit var probeThread: HandlerThread
    private lateinit var probeHandler: Handler
    private lateinit var callbackThread: HandlerThread
    private lateinit var callbackHandler: Handler
    private val executor = Executor { callbackHandler.post(it) }

    private lateinit var cameraManager: CameraManager
    private lateinit var powerManager: PowerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Camera2 refuses to open for a process with no visible activity
        // (Android 9+ background-camera restriction), so the probe has to be a
        // real foreground activity. show-when-locked lets it run without
        // unlocking the phone first.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        textView = TextView(this).apply {
            textSize = 10f
            setPadding(24, 48, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(textView) })

        cameraManager = getSystemService(CameraManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            emit("CAMERA permission not granted — grant it and relaunch.")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }

        probeThread = HandlerThread("Camera2Probe").apply { start() }
        probeHandler = Handler(probeThread.looper)
        callbackThread = HandlerThread("Camera2ProbeCb").apply { start() }
        callbackHandler = Handler(callbackThread.looper)
        probeHandler.post { runAllProbes() }
    }

    private fun runAllProbes() {
        emit("=== Camera2 capability probe ===")
        emit("device=${Build.MANUFACTURER} ${Build.MODEL}  android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        emit("thermal at start: ${thermalName()}")
        emit("")

        inventory()
        emit("")
        // Baseline first: without it, "test 1 measured 30fps" is ambiguous
        // between "the [60,60] session parameter was ignored" and "the camera
        // is simply slow right now". Same code path, only the range differs.
        probeImageReader("TEST 1a (baseline)", Range(30, 30))
        emit("")
        probeImageReader("TEST 1", Range(60, 60))
        emit("")
        probeHevc8k()
        emit("")
        emit("thermal at end: ${thermalName()}")
        emit("=== done ===")
        writeReportFile()
    }

    // ─────────────────────────── inventory ───────────────────────────

    /** What the app can see through the public API vs. what the vendor tables say. */
    private fun inventory() {
        emit("--- INVENTORY ---")
        val ids = try { cameraManager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        emit("cameraIdList (what CameraX/CameraManager enumerate): $ids")
        emit("\"56\" enumerated? ${"56" in ids}   (8K lives on this id per dumpsys)")

        for (id in listOf("0", "56")) {
            val chars = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                emit("camera $id: characteristics unreadable (${e.javaClass.simpleName}: ${e.message})")
                continue
            }
            emit("camera $id:")
            if (Build.VERSION.SDK_INT >= 28) {
                emit("   physicalIds=${chars.physicalCameraIds}")
            }
            val aeRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            emit("   AOSP AE fps ranges = ${aeRanges?.joinToString()}")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val privSizes = map?.getOutputSizes(ImageFormat.PRIVATE)?.sortedByDescending { it.width * it.height }
            emit("   AOSP PRIVATE sizes (top 4) = ${privSizes?.take(4)?.joinToString()}")
            emit("   AOSP map has 3840x2160? ${privSizes?.contains(Size(3840, 2160))}   7680x4320? ${privSizes?.contains(Size(7680, 4320))}")

            // The whole premise: can a normal app read Samsung's vendor table
            // at runtime? If yes, a future implementation can be capability-
            // driven instead of hardcoding what dumpsys showed us.
            val vendor = readVendorVideoConfigs(chars)
            if (vendor == null) {
                emit("   vendor availableVideoConfigurations: NOT READABLE from app process")
            } else {
                val entries = vendor.toList().chunked(6)
                    .filter { it.size == 6 }
                    .map { Triple(it[0], it[1], it[3]) }
                val top = entries.filter { it.first * it.second >= 1920 * 1080 }
                    .distinct()
                    .sortedByDescending { it.first.toLong() * it.second * 100 + it.third }
                    .take(6)
                emit("   vendor availableVideoConfigurations: READABLE, ${entries.size} entries")
                top.forEach { emit("      ${it.first}x${it.second} @ ${it.third}fps") }
            }
        }
    }

    /**
     * samsung.android.scaler.availableVideoConfigurations, addressed by name.
     * Vendor tags are reachable through the public CameraCharacteristics.Key
     * (String, Class) constructor — no reflection — provided the vendor tag
     * descriptor is loaded in this process, which it is for any camera client.
     */
    private fun readVendorVideoConfigs(chars: CameraCharacteristics): IntArray? = try {
        chars.get(
            CameraCharacteristics.Key(
                "samsung.android.scaler.availableVideoConfigurations",
                IntArray::class.java,
            ),
        )
    } catch (e: Throwable) {
        Log.w(TAG, "vendor tag read failed", e)
        null
    }

    // ─────────────────────────── test 1 ───────────────────────────

    /**
     * Camera 0 -> ImageReader at 3840x2160, with the fps range supplied as a
     * SESSION PARAMETER (not just a repeating-request option). AE_TARGET_FPS_RANGE
     * is listed in this device's android.request.availableSessionKeys, which is
     * what makes the distinction meaningful: the HAL picks its pipeline at
     * session-configuration time.
     *
     * The reader is PRIVATE/USAGE_VIDEO_ENCODE, not YUV_420_888 — a CPU-readable
     * reader is itself a ~30fps ceiling at 4K on this ISP, which would poison
     * the measurement with the exact confound we're trying to rule out.
     */
    private fun probeImageReader(label: String, fpsRange: Range<Int>) {
        emit("--- $label: camera 0, 3840x2160, ImageReader, AE_TARGET_FPS_RANGE=$fpsRange as session parameter ---")

        val reader = if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(3840, 2160, ImageFormat.PRIVATE, 6, HardwareBuffer.USAGE_VIDEO_ENCODE)
        } else {
            ImageReader.newInstance(3840, 2160, ImageFormat.PRIVATE, 6)
        }
        val delivered = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { delivered.incrementAndGet() }
        }, callbackHandler)

        val counters = Counters()
        val device = openCamera("0") ?: run { reader.close(); return }
        try {
            val session = configureSession(device, listOf(OutputConfiguration(reader.surface)), fpsRange, null)
            if (session == null) { emit("   RESULT: session creation FAILED"); return }
            emit("   session creation: OK")

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }.build()
            session.setRepeatingRequest(request, counters.callback, callbackHandler)

            measure(counters, delivered)
            report(counters, delivered.get(), extraLabel = "ImageReader frames")
            try {
                session.stopRepeating()
                session.close()
            } catch (e: Exception) {
                emit("   (teardown after measurement: ${e.javaClass.simpleName})")
            }
        } catch (e: Exception) {
            emit("   EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            device.close()
            reader.close()
        }
    }

    // ─────────────────────────── test 2 ───────────────────────────

    /**
     * 7680x4320 into an HEVC MediaCodec input surface. Two routes are tried, in
     * the order most likely to work: top-level camera id "56", then logical
     * camera "0" with the output routed to physical id "5" (the 200MP main
     * sensor — the only id whose vendor table lists 8K).
     */
    private fun probeHevc8k() {
        emit("--- TEST 2: 7680x4320, HEVC MediaCodec surface, 30fps ---")

        val codecName: String
        val codec: MediaCodec
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codecName = codec.name
        } catch (e: Exception) {
            emit("   RESULT: no HEVC encoder (${e.message})"); return
        }
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
            emit("   encoder=$codecName  sizeSupported(7680x4320)=${caps.isSizeSupported(7680, 4320)}" +
                "  sizeAndRate@30=${caps.areSizeAndRateSupported(7680, 4320, 30.0)}")
        } catch (e: Exception) {
            emit("   encoder capability query failed: ${e.message}")
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 7680, 4320).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 100_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val inputSurface: Surface
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            emit("   MediaCodec configure/start at 7680x4320: OK")
        } catch (e: Exception) {
            emit("   RESULT: MediaCodec configure FAILED at 7680x4320 — ${e.javaClass.simpleName}: ${e.message}")
            codec.release(); return
        }

        val encoded = AtomicInteger()
        val draining = java.util.concurrent.atomic.AtomicBoolean(true)
        val drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (draining.get()) {
                try {
                    val idx = codec.dequeueOutputBuffer(info, 50_000)
                    if (idx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) encoded.incrementAndGet()
                        codec.releaseOutputBuffer(idx, false)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { start() }

        // Route A: top-level id 56. Route B: logical 0 -> physical 5.
        var ok = false
        for ((routeLabel, cameraId, physicalId) in listOf(
            Triple("A: top-level camera 56", "56", null),
            Triple("B: camera 0 -> physical id 5", "0", "5"),
        )) {
            emit("   route $routeLabel")
            val device = openCamera(cameraId) ?: continue
            val counters = Counters()
            try {
                val output = OutputConfiguration(inputSurface).apply {
                    if (physicalId != null && Build.VERSION.SDK_INT >= 28) setPhysicalCameraId(physicalId)
                }
                val session = configureSession(device, listOf(output), Range(30, 30), physicalId)
                if (session == null) { emit("      session creation: FAILED"); device.close(); continue }
                emit("      session creation: OK")

                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(inputSurface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                }.build()
                session.setRepeatingRequest(request, counters.callback, callbackHandler)

                measure(counters, encoded)
                report(counters, encoded.get(), extraLabel = "HEVC frames encoded")
                // Set before teardown, not after: if another camera client
                // (face unlock, etc.) grabs the device mid-run, stopRepeating()
                // throws "session has been closed" and the route gets reported
                // as a failure even though the measurement above succeeded.
                ok = true
                try {
                    session.stopRepeating()
                    session.close()
                } catch (e: Exception) {
                    emit("      (teardown after measurement: ${e.javaClass.simpleName})")
                }
            } catch (e: Exception) {
                emit("      EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                device.close()
            }
            if (ok) break
        }
        if (!ok) emit("   RESULT: no route to 8K succeeded")

        draining.set(false)
        drainThread.join(2000)
        try { codec.stop() } catch (e: Exception) { }
        codec.release()
    }

    // ─────────────────────────── plumbing ───────────────────────────

    private class Counters {
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        val buffersLost = AtomicInteger()
        val firstTimestampNs = AtomicLong(0)
        val lastTimestampNs = AtomicLong(0)
        val firstFrameNumber = AtomicLong(-1)
        val lastFrameNumber = AtomicLong(-1)
        var wallStartNs = 0L

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                completed.incrementAndGet()
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                    firstTimestampNs.compareAndSet(0, ts)
                    lastTimestampNs.set(ts)
                }
                firstFrameNumber.compareAndSet(-1, result.frameNumber)
                lastFrameNumber.set(result.frameNumber)
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                failed.incrementAndGet()
            }

            override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, t: Surface, n: Long) {
                buffersLost.incrementAndGet()
            }
        }
    }

    /**
     * [consumerCounter] is reset alongside the capture counters, not just the
     * capture ones — otherwise the consumer's total still includes the warm-up
     * window while its rate is divided by the (shorter) measured window, which
     * reads as an impossible "67.9 fps" on a 60fps stream.
     */
    private fun measure(counters: Counters, consumerCounter: AtomicInteger) {
        // Give AE/AF a moment to settle so the ramp-up isn't averaged into the
        // steady-state number, then reset and measure a clean window.
        Thread.sleep(1500)
        consumerCounter.set(0)
        counters.completed.set(0)
        counters.failed.set(0)
        counters.buffersLost.set(0)
        counters.firstTimestampNs.set(0)
        counters.firstFrameNumber.set(-1)
        counters.wallStartNs = System.nanoTime()
        Thread.sleep(TimeUnit.SECONDS.toMillis(MEASURE_SECONDS))
    }

    private fun report(counters: Counters, consumerFrames: Int, extraLabel: String) {
        val wallSeconds = (System.nanoTime() - counters.wallStartNs) / 1e9
        val completed = counters.completed.get()
        val sensorSpanNs = counters.lastTimestampNs.get() - counters.firstTimestampNs.get()
        val sensorFps = if (sensorSpanNs > 0 && completed > 1) (completed - 1) * 1e9 / sensorSpanNs else 0.0
        val frameSpan = counters.lastFrameNumber.get() - counters.firstFrameNumber.get()
        // Camera2 frame numbers increment per request the HAL accepted; a gap
        // wider than the number of results we got back means requests the HAL
        // took but never completed.
        val gaps = if (frameSpan > 0) (frameSpan + 1 - completed).coerceAtLeast(0) else 0

        emit("   capture-callback fps : ${"%.2f".format(completed / wallSeconds)}  ($completed results in ${"%.1f".format(wallSeconds)}s)")
        emit("   sensor-timestamp fps : ${"%.2f".format(sensorFps)}")
        emit("   $extraLabel  : $consumerFrames  (${"%.2f".format(consumerFrames / wallSeconds)} fps)")
        emit("   dropped: onCaptureFailed=${counters.failed.get()}  buffersLost=${counters.buffersLost.get()}  frame-number gaps=$gaps")
        emit("   thermal now: ${thermalName()}")
    }

    private fun openCamera(id: String): CameraDevice? {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var error: String? = null
        try {
            cameraManager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened = camera; latch.countDown() }
                override fun onDisconnected(camera: CameraDevice) { error = "disconnected"; camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, err: Int) { error = "error code $err"; camera.close(); latch.countDown() }
            })
        } catch (e: Exception) {
            emit("      openCamera($id) threw ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!latch.await(5, TimeUnit.SECONDS)) { emit("      openCamera($id) timed out"); return null }
        if (opened == null) emit("      openCamera($id) failed: $error")
        return opened
    }

    /**
     * The point of the whole probe: fpsRange goes in via
     * SessionConfiguration.setSessionParameters, so the HAL sees it while it is
     * still choosing its pipeline — not as a per-request option applied after
     * the session already exists.
     */
    private fun configureSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        fpsRange: Range<Int>,
        physicalId: String?,
    ): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        val sessionParams = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            if (physicalId != null) {
                Log.i(TAG, "session parameters target physical camera $physicalId")
            }
        }.build()

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; latch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { latch.countDown() }
            },
        ).apply { sessionParameters = sessionParams }

        try {
            device.createCaptureSession(config)
        } catch (e: Exception) {
            emit("      createCaptureSession threw ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!latch.await(8, TimeUnit.SECONDS)) { emit("      session configuration timed out"); return null }
        return session
    }

    private fun thermalName(): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown (API<29)"
        return when (val s = powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "code $s"
        }
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        report.append(line).append('\n')
        runOnUiThread { textView.text = report.toString() }
    }

    private fun writeReportFile() {
        try {
            val f = File(getExternalFilesDir(null), "camera2_probe.txt")
            f.writeText(report.toString())
            Log.i(TAG, "report written to ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "could not write report", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::probeThread.isInitialized) probeThread.quitSafely()
    }
}
