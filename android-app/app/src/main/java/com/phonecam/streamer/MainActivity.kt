package com.phonecam.streamer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import com.phonecam.streamer.audio.AudioLevelMeter
import com.phonecam.streamer.consent.ConsentManager
import com.phonecam.streamer.databinding.ActivityMainBinding
import com.phonecam.streamer.camera2.Camera2Capabilities
import com.phonecam.streamer.camera2.Camera2CaptureSource
import com.phonecam.streamer.device.DeviceCapabilities
import com.phonecam.streamer.device.DeviceModelDatabase
import com.phonecam.streamer.network.PcControl
import com.phonecam.streamer.network.PcDiscovery
import com.phonecam.streamer.rewards.AdMobAdController
import com.phonecam.streamer.rewards.RewardManager
import com.phonecam.streamer.streaming.CameraStreamer
import com.phonecam.streamer.ui.AppToast
import com.phonecam.streamer.ui.WelcomeDialog
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import com.phonecam.streamer.streaming.RotationPolicy

private const val TAG = "MainActivity"

// A rotation reading has to hold steady for this long before it's actually
// applied — see orientationEventListener's doc for why.
private const val ROTATION_DEBOUNCE_MS = 400L

// Phase 1 of the Camera2 backend targets the main back camera only — the id
// whose vendor table advertises 3840x2160@60 and which the probe measured at
// a sustained 59.8fps.
private const val CAMERA2_CAMERA_ID = "0"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adController: AdMobAdController
    private lateinit var consentManager: ConsentManager
    private lateinit var rewardManager: RewardManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val pcConnectExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var streamer: CameraStreamer? = null
    private var isStreaming = false
    private var adsReady = false
    // Plain in-memory flag: false on every fresh process (app opened/reopened),
    // survives Settings round-trips within the same session — see onWatchAdClicked.
    private var adIntroShownThisSession = false
    private var camera: Camera? = null
    private var recDotAnimator: ObjectAnimator? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentConfig: StreamConfig? = null
    private var torchOn = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var wasScaling = false
    private var audioLevelMeter: AudioLevelMeter? = null

    // The use case CameraStreamer's encoder actually reads frames from —
    // kept as a field (rather than a local in startCamera()'s binding
    // lambda) so orientationEventListener below can push a live
    // targetRotation update to it without a full camera rebind every time
    // the phone is turned.
    private var currentVideoCapture: VideoCapture<com.phonecam.streamer.streaming.StreamingVideoOutput>? = null

    // ── Experimental Camera2 capture backend (Settings > Display > Experimental) ──
    // Null and inert unless the flag is on AND this device advertises the
    // requested size/rate. CameraX stays the default and the fallback; see
    // startCamera2Backend / fallbackToCameraX.
    private var camera2Source: com.phonecam.streamer.camera2.Camera2CaptureSource? = null
    private var camera2Active = false
    // Sticky for the lifetime of the process once the backend has failed once,
    // so a fallback can't bounce straight back into the path that just failed.
    private var camera2FailedThisRun = false
    private var camera2AppliedRotation: Int? = null
    // What the Camera2 session actually negotiated — the numbers diagnostics
    // should show, as opposed to what Settings asked for.
    private var camera2ActualSize: Size? = null
    private var camera2ActualFps: Int? = null

    // Bound to VideoCapture in startCamera() and forwards every SurfaceRequest
    // to whichever CameraStreamer is current — created once here (not per
    // rebind) because startCamera() runs before `streamer` exists on a fresh
    // "start streaming" tap (see startStreaming(): the use-case group is
    // built synchronously, streamer is assigned moments later once the PC
    // connection begins), same nullable-forwarding pattern the old
    // ImageAnalysis.Analyzer lambda used.
    private val mainVideoOutput = com.phonecam.streamer.streaming.StreamingVideoOutput { request ->
        streamer?.videoOutput?.onSurfaceRequested(request) ?: request.willNotProvideSurface()
    }

    // Compensates for physically rotating the phone while recording: MainActivity
    // itself stays orientation-locked to portrait (android:screenOrientation, see
    // manifest — needed so an OS-triggered Activity recreation doesn't tear down
    // an in-progress stream), so Android's own rotation handling never fires here.
    // This tracks the sensor directly instead and feeds it straight to the
    // VideoCapture use case's targetRotation, which CameraX turns into a fresh
    // TransformationInfo (see CameraStreamer.onSurfaceRequested) — the
    // recorded/streamed frame comes out upright regardless of how the phone
    // is physically held, independent of what's drawn on screen.
    // Raw OrientationEventListener readings are noisy right around each
    // 45/135/225/315 bucket boundary — accelerometer jitter (even from a
    // steady hand) flips the raw angle back and forth across a boundary,
    // and without debouncing that used to flip targetRotation just as
    // fast, each flip pushing a fresh TransformationInfo through CameraX
    // and visibly glitching the recorded/streamed orientation. Requiring a
    // candidate rotation to hold for ROTATION_DEBOUNCE_MS before it's
    // actually applied filters that out — a real, deliberate 90° turn
    // easily holds that long, a jitter blip at a boundary doesn't.
    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            private var pendingRotation: Int? = null
            private var pendingSinceMs = 0L

            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = RotationPolicy.bucketFor(orientation)
                if (rotation == currentVideoCapture?.targetRotation) {
                    pendingRotation = null
                    return
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (rotation != pendingRotation) {
                    pendingRotation = rotation
                    pendingSinceMs = now
                    return
                }
                if (now - pendingSinceMs >= ROTATION_DEBOUNCE_MS) {
                    currentVideoCapture?.targetRotation = RotationPolicy.landscapeTargetRotation(rotation)
                    // Camera2 has no targetRotation to push this into — the
                    // equivalent is computed from the sensor's mounting and
                    // handed to the renderer directly.
                    if (camera2Active) applyCamera2Rotation(rotation)
                    pendingRotation = null
                }
            }
        }
    }

    // Lets the camera stay bound (still capturing/streaming) while SettingsActivity
    // is the foreground UI, instead of following MainActivity's own onStop/onPause —
    // CameraX auto-unbinds use cases the instant a bound LifecycleOwner drops below
    // STARTED, and MainActivity does exactly that the moment Settings opens on top
    // of it, however briefly. Bound to this instead of `this` (see startCamera()),
    // and only ever moved to DESTROYED in onDestroy() — never downgraded on pause/
    // stop — so opening Settings mid-recording no longer drops every frame until
    // you back out again.
    private val cameraLifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAudioMeter()
            } else {
                AppToast.warning(this, getString(R.string.toast_mic_permission_needed))
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                AppToast.error(this, getString(R.string.toast_camera_permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gates the rest of setup (camera permission prompt, ad init, etc.)
        // behind the welcome popup so it's genuinely the first thing a new
        // user sees — not a race against the permission dialog. Returning
        // users (WelcomeDialog finds it already marked shown) fall straight
        // through with no visible delay.
        WelcomeDialog.showIfFirstLaunch(this) { continueOnCreate() }
    }

    private fun continueOnCreate() {
        rewardManager = loadRewardManager()
        adController = AdMobAdController(this)
        consentManager = ConsentManager(this)
        updateAdProgressBadge()

        binding.watchAdButton.isEnabled = false
        binding.watchAdButton.setOnClickListener { onWatchAdClicked() }
        binding.toggleStreamButton.setOnClickListener { onToggleStreamClicked() }
        setupRecButtonPressAnimation()
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_up, R.anim.fade_out)
        }
        binding.flipCameraButton.setOnClickListener { flipCamera() }
        binding.torchButton.setOnClickListener { toggleTorch() }
        addPressPop(binding.settingsButton)
        addPressPop(binding.flipCameraButton)
        addPressPop(binding.torchButton)

        cameraLifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        setupCameraControls()
        setupPreviewGestures()
        requestCameraPermission()
        initializeAdsWithConsent()
        startUiTicker()
        animateEntrance()
    }

    private fun animateEntrance() {
        binding.bottomBar.translationY = 100f
        binding.bottomBar.alpha = 0f
        binding.bottomBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.cameraControls.translationX = 60f
        binding.cameraControls.alpha = 0f
        binding.cameraControls.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.topBar.alpha = 0f
        binding.topBar.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(100)
            .start()
    }

    // ── Exposure state ──
    // AE on: the camera meters light and the EV slider biases it (exposure compensation).
    // AE off: the app stops adjusting light entirely — the sensor runs at exactly the
    // ISO and shutter speed set on the sliders (true CONTROL_AE_MODE_OFF manual).
    private var aeEnabled = true
    private var manualSensorSupported = false
    private var isoRange: Range<Int> = Range(100, 3200)
    private var shutterRangeNs: Range<Long> = Range(125_000L, 33_333_333L)  // 1/8000s .. 1/30s
    private var manualFocusDiopters: Float? = null

    private fun isoFromProgress(progress: Int): Int {
        // Log scale feels natural for ISO (100→200 matters more than 6300→6400)
        val lo = kotlin.math.ln(isoRange.lower.toDouble())
        val hi = kotlin.math.ln(isoRange.upper.toDouble())
        return kotlin.math.exp(lo + (progress / 100.0) * (hi - lo)).toInt()
    }

    private fun shutterNsFromProgress(progress: Int): Long {
        val lo = kotlin.math.ln(shutterRangeNs.lower.toDouble())
        val hi = kotlin.math.ln(shutterRangeNs.upper.toDouble())
        return kotlin.math.exp(lo + (progress / 100.0) * (hi - lo)).toLong()
    }

    private fun formatShutter(ns: Long): String {
        val denominator = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
        return "1/$denominator"
    }

    private fun setupCameraControls() {
        binding.isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.isoValue.text = isoFromProgress(progress).toString()
                if (fromUser) rebuildCaptureOptions()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.ssSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.ssValue.text = formatShutter(shutterNsFromProgress(progress))
                if (fromUser) rebuildCaptureOptions()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val cam = camera ?: return
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                val ev = mapProgressToRange(progress, range)
                cam.cameraControl.setExposureCompensationIndex(ev)
                val step = cam.cameraInfo.exposureState.exposureCompensationStep.toFloat()
                binding.exposureValue.text = "%.1f".format(ev * step)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.aeToggle.setOnClickListener {
            aeEnabled = !aeEnabled
            getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
                .putBoolean("auto_exposure", aeEnabled).apply()
            updateExposureUi()
            rebuildCaptureOptions()
            // When AE comes back, clear any leftover manual bias look by resetting EV
            if (aeEnabled) camera?.cameraControl?.setExposureCompensationIndex(0)
        }
    }

    /** Show EV under auto-exposure; show shutter+ISO under manual. */
    private fun updateExposureUi() {
        if (!manualSensorSupported) {
            // Camera can't do manual sensor control — EV-only, hide the AE toggle
            binding.aeToggle.visibility = View.GONE
            binding.ssGroup.visibility = View.GONE
            binding.isoGroup.visibility = View.GONE
            binding.evGroup.visibility = View.VISIBLE
            return
        }
        binding.aeToggle.visibility = View.VISIBLE
        binding.aeToggle.setTextColor(
            if (aeEnabled) getColor(R.color.focus_ring) else 0x77FFFFFF,
        )
        binding.aeToggle.text = if (aeEnabled) getString(R.string.ae_label) else "M"
        binding.ssGroup.visibility = if (aeEnabled) View.GONE else View.VISIBLE
        binding.isoGroup.visibility = if (aeEnabled) View.GONE else View.VISIBLE
        binding.evGroup.visibility = if (aeEnabled) View.VISIBLE else View.GONE
    }

    /**
     * Single source of truth for Camera2 pass-through options: manual focus and
     * manual exposure both live in one builder so applying one never wipes the other.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun rebuildCaptureOptions() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()

        val diopters = manualFocusDiopters
        if (diopters != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        } else {
            // "Cinematic" drives CONTINUOUS_VIDEO — the framework favors smoother, less
            // twitchy transitions for it by design. Every other preset uses
            // CONTINUOUS_PICTURE, which reacquires faster (tap-to-focus speed/region
            // are what actually differentiate Action/Macro from Standard, set in
            // tapToFocus — this just picks the base driving behavior underneath).
            val continuousMode = if (currentConfig?.autofocusSpeed?.continuousVideo == true) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, continuousMode)
        }

        if (!aeEnabled && manualSensorSupported) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoFromProgress(binding.isoSeekBar.progress))
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNsFromProgress(binding.ssSeekBar.progress))
        }

        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = b.build()
    }

    private fun setupPreviewGestures() {
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                    return true
                }
            },
        )

        binding.previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> wasScaling = false
                MotionEvent.ACTION_POINTER_DOWN -> wasScaling = true
                MotionEvent.ACTION_UP -> {
                    if (!wasScaling && !scaleGestureDetector.isInProgress) {
                        tapToFocus(event.x, event.y)
                        view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun tapToFocus(x: Float, y: Float) {
        // Manual mode pins focus to the MF slider — a stray tap must not fight it.
        if (currentConfig?.autofocusMode == StreamConfig.AutofocusMode.MANUAL) return
        val cam = camera ?: return

        val speed = currentConfig?.autofocusSpeed ?: StreamConfig.AutofocusSpeed.STANDARD
        val meteringPointFactory = binding.previewView.meteringPointFactory
        val point = meteringPointFactory.createPoint(x, y, speed.meteringPointSize)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(speed.autoCancelSeconds, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)

        // x/y here are relative to previewView itself (that's what MeteringPointFactory
        // needs). focusIndicator is a sibling positioned in the parent ConstraintLayout,
        // not a child of previewView — those coordinate spaces only lined up back when
        // previewView filled the whole screen. Composition letterboxes/pillarboxes
        // previewView off-center now, so its parent-relative offset has to be added in.
        showFocusIndicator(binding.previewView.x + x, binding.previewView.y + y)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val ind = binding.focusIndicator
        ind.x = x - ind.width / 2f
        ind.y = y - ind.height / 2f
        ind.visibility = View.VISIBLE
        ind.alpha = 0f
        ind.scaleX = 1.4f
        ind.scaleY = 1.4f
        ind.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .withEndAction {
                ind.animate().alpha(0f).setStartDelay(700).setDuration(300)
                    .withEndAction { ind.visibility = View.GONE }.start()
            }
            .start()
    }

    private fun flipCamera() {
        // Icon spin mirrors the physical camera swap
        binding.flipCameraButton.animate()
            .rotationBy(180f)
            .setDuration(450)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // Persist so Settings and next launch agree
        getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
            .putInt("camera_facing", if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0)
            .apply()
        startCamera()
    }

    private fun mapProgressToRange(progress: Int, range: Range<Int>): Int {
        val fraction = progress / 100f
        return (range.lower + fraction * (range.upper - range.lower)).toInt()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)

        val btn = binding.torchButton
        btn.setColorFilter(if (torchOn) getColor(R.color.focus_ring) else 0x99FFFFFF.toInt())
        btn.setBackgroundResource(if (torchOn) R.drawable.btn_circle_glow else R.drawable.btn_circle_dark)

        // "Light burst": quick overshoot pop when turning on, gentle settle when off
        btn.animate().cancel()
        if (torchOn) {
            btn.scaleX = 0.7f; btn.scaleY = 0.7f
            btn.animate().scaleX(1f).scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(3f))
                .start()
        } else {
            btn.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    /** iOS-style press feedback: shrink while held, spring back on release. */
    private fun addPressPop(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f)
                        .setDuration(110)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(350)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    /** Per-camera UI: torch visibility, zoom chips, and the focus mode. Called after each bind. */
    private fun setupCameraDependentControls(cfg: StreamConfig) {
        val cam = camera ?: return

        // Torch — only offered when this camera actually has a flash unit
        val hasFlash = cam.cameraInfo.hasFlashUnit()
        binding.torchButton.visibility = if (hasFlash) View.VISIBLE else View.GONE
        if (!hasFlash) torchOn = false

        setupExposureControls(cam)
        buildZoomChips(cam, cfg)
        applyFocusMode(cam, cfg)

        // A handful of devices genuinely have no distinct id (top-level OR hidden
        // physical) for the requested lens at all — resolveCameraBinding already
        // toasted about it and bound the plain camera. At least approximate the
        // framing with digital zoom so the chip isn't a total no-op there.
        if (currentLensFacing == CameraSelector.LENS_FACING_BACK && cfg.lensType != "wide") {
            val target = findLensTargetFor(cfg.lensType)
            if (target == null) {
                val factor = DeviceModelDatabase.lookup(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                    ?.cameras?.firstOrNull { it.lens == cfg.lensType }?.zoomFactor
                if (factor != null && factor > 1f) {
                    val maxRatio = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(factor.coerceAtMost(maxRatio))
                }
            }
        }
    }

    private val lensPrefIndex = mapOf("wide" to 0, "ultra-wide" to 1, "telephoto" to 2, "supertelephoto" to 3)

    private fun buildZoomChips(cam: Camera, cfg: StreamConfig) {
        val row = binding.zoomChipRow
        row.removeAllViews()

        if (currentLensFacing != CameraSelector.LENS_FACING_BACK) {
            row.visibility = View.GONE
            return
        }

        val dbLenses = DeviceModelDatabase.lookup(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            ?.cameras
            ?.filter { it.lens != "front" }
            ?.sortedBy { it.zoomFactor }

        if (dbLenses != null && dbLenses.size >= 2) {
            // Known model: each chip is meant to be a distinct REAL lens, rebinding the
            // pipeline via the lens preference + DeviceCapabilities.findLensTarget /
            // findLensTargetsForKnownDevice. For the rare device with no distinct id for a
            // lens at all, isDigitalZoomOnly chips skip that doomed rebind and just crop
            // the currently-bound camera with setZoomRatio instead of a no-op.
            row.visibility = View.VISIBLE
            val activeDigitalZoom = dbLenses.firstOrNull { it.isDigitalZoomOnly && isNearZoom(cam, it.zoomFactor) }
            dbLenses.forEach { spec ->
                val active = if (spec.isDigitalZoomOnly) {
                    spec == activeDigitalZoom
                } else {
                    spec.lens == cfg.lensType && activeDigitalZoom == null
                }
                row.addView(makeZoomChip(formatZoomFactor(spec.zoomFactor), active) {
                    if (spec.isDigitalZoomOnly) {
                        cam.cameraControl.setZoomRatio(spec.zoomFactor)
                        buildZoomChips(cam, cfg) // refresh active state immediately
                    } else if (spec.lens != cfg.lensType) {
                        getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
                            .putInt("lens", lensPrefIndex[spec.lens] ?: 0)
                            .apply()
                        startCamera() // rebind onto that physical camera
                    } else if (activeDigitalZoom != null) {
                        cam.cameraControl.setZoomRatio(1f) // back to plain 1x
                        buildZoomChips(cam, cfg)
                    }
                })
            }
            return
        }

        // Unknown model: generic digital-zoom steps clipped to the camera's range
        val zoomState = cam.cameraInfo.zoomState.value
        val minRatio = zoomState?.minZoomRatio ?: 1f
        val maxRatio = zoomState?.maxZoomRatio ?: 1f
        val candidates = listOf(0.5f, 1f, 2f, 5f).filter { it in minRatio..maxRatio }
        if (candidates.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        candidates.forEach { ratio ->
            row.addView(makeZoomChip(formatZoomFactor(ratio), active = false) {
                cam.cameraControl.setZoomRatio(ratio)
            })
        }
        cam.cameraInfo.zoomState.observe(this) { state ->
            val current = state?.zoomRatio ?: 1f
            val nearest = candidates.minByOrNull { kotlin.math.abs(it - current) }
            for (i in 0 until row.childCount) {
                setChipActive(row.getChildAt(i) as TextView, candidates[i] == nearest)
            }
        }
    }

    private fun isNearZoom(cam: Camera, ratio: Float): Boolean {
        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        return kotlin.math.abs(current - ratio) < 0.15f
    }

    private fun formatZoomFactor(ratio: Float): String = when {
        ratio < 1f -> "%.1f".format(ratio)
        ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
        else -> "%.1fx".format(ratio)
    }

    private fun setChipActive(chip: TextView, active: Boolean) {
        chip.setTextColor(if (active) getColor(R.color.focus_ring) else 0xCCFFFFFF.toInt())
        chip.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun makeZoomChip(label: String, active: Boolean, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.btn_circle_dark)
            setChipActive(this, active)
            setOnClickListener {
                onTap()
                // Pop feedback on the tapped chip
                animate().cancel()
                scaleX = 0.75f; scaleY = 0.75f
                animate().scaleX(1f).scaleY(1f)
                    .setDuration(400)
                    .setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
        }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusMode(cam: Camera, cfg: StreamConfig) {
        val manual = cfg.autofocusMode == StreamConfig.AutofocusMode.MANUAL
        val mfVisibility = if (manual) View.VISIBLE else View.GONE
        binding.mfDivider.visibility = mfVisibility
        binding.mfLabel.visibility = mfVisibility
        binding.mfSeekBar.visibility = mfVisibility
        binding.mfValue.visibility = mfVisibility

        if (!manual) {
            manualFocusDiopters = null
            rebuildCaptureOptions()
            return
        }

        // LENS_INFO_MINIMUM_FOCUS_DISTANCE is in diopters (1/meters); 0 = infinity.
        val minFocusDistance = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        if (minFocusDistance <= 0f) {
            // Fixed-focus camera: manual mode is meaningless, quietly fall back
            binding.mfDivider.visibility = View.GONE
            binding.mfLabel.visibility = View.GONE
            binding.mfSeekBar.visibility = View.GONE
            binding.mfValue.visibility = View.GONE
            manualFocusDiopters = null
            rebuildCaptureOptions()
            return
        }

        fun applyFocusDistance(progress: Int) {
            val diopters = (progress / 100f) * minFocusDistance
            manualFocusDiopters = diopters
            rebuildCaptureOptions()
            binding.mfValue.text = if (diopters < 0.05f) "∞" else "%.1fm".format(1f / diopters)
        }

        applyFocusDistance(binding.mfSeekBar.progress)
        binding.mfSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                applyFocusDistance(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    /** Reads sensor limits of the bound camera and syncs the exposure rail to them. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setupExposureControls(cam: Camera) {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        )
        // REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR = 1
        manualSensorSupported = caps?.any { it == 1 } == true

        if (manualSensorSupported) {
            info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )?.let { isoRange = it }
            info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )?.let { hw ->
                // Clamp to video-sensible shutter speeds: 1/8000s .. 1/15s
                shutterRangeNs = Range(
                    maxOf(hw.lower, 125_000L),
                    minOf(hw.upper, 66_666_666L),
                )
            }
        }

        aeEnabled = getSharedPreferences("stream_settings", MODE_PRIVATE)
            .getBoolean("auto_exposure", true)
        binding.isoValue.text = isoFromProgress(binding.isoSeekBar.progress).toString()
        binding.ssValue.text = formatShutter(shutterNsFromProgress(binding.ssSeekBar.progress))
        updateExposureUi()
        rebuildCaptureOptions()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeAdsWithConsent() {
        consentManager.requestConsentAndThen {
            if (consentManager.canRequestAds()) {
                adController.initialize {
                    adController.preload()
                    adsReady = true
                    runOnUiThread { binding.watchAdButton.isEnabled = true }
                }
            } else {
                Log.w(TAG, "ads disabled: consent not granted")
                runOnUiThread {
                    binding.watchAdButton.isEnabled = false
                    binding.watchAdButton.text = getString(R.string.ads_consent_denied)
                }
            }
        }
    }

    private fun onWatchAdClicked() {
        if (!adsReady || !adController.isReady()) {
            AppToast.info(this, getString(R.string.toast_loading))
            adController.preload()
            return
        }
        // Explain the 3-ads-for-1h deal only once per app opening — a plain
        // in-memory flag naturally resets on a fresh process (user left/closed
        // the app and came back) but survives switching to Settings and back,
        // so starting a second batch in the same session jumps straight to the
        // ad instead of re-nagging with the same explanation.
        if (!adIntroShownThisSession) {
            adIntroShownThisSession = true
            showAdBatchIntroDialog { launchRewardedAd() }
        } else {
            launchRewardedAd()
        }
    }

    private fun showAdBatchIntroDialog(onContinue: () -> Unit) {
        val adsPerReward = rewardManager.config.adsPerReward
        val hours = (rewardManager.config.secondsPerReward / 3600.0).roundToInt()
        val ordinal = rewardManager.adsWatchedInBatch + 1
        AppToast.showAdIntro(
            this,
            title = getString(R.string.ad_intro_title, hours),
            description = getString(R.string.ad_intro_description, adsPerReward, hours, ordinal),
            buttonText = getString(R.string.ad_intro_button, ordinal, adsPerReward),
            onWatchAd = onContinue,
        )
    }

    private fun launchRewardedAd() {
        adController.showRewardedAd(
            activity = this,
            onRewardEarned = {
                val batchCompleted = rewardManager.creditAdWatch()
                saveRewardManager()
                refreshStatusUi()
                updateAdProgressBadge()

                val hours = (rewardManager.config.secondsPerReward / 3600.0).roundToInt()
                if (batchCompleted) {
                    AppToast.showCelebration(
                        this,
                        title = getString(R.string.ad_reward_celebration_title, hours),
                        description = getString(R.string.ad_reward_celebration_desc, hours),
                    )
                } else {
                    val watched = rewardManager.adsWatchedInBatch
                    val total = rewardManager.config.adsPerReward
                    val remaining = total - watched
                    AppToast.showProgress(
                        this,
                        title = getString(R.string.ad_progress_title, watched, total),
                        description = resources.getQuantityString(
                            R.plurals.ads_remaining_desc, remaining, remaining, hours,
                        ),
                    )
                }
            },
            onDismissedWithoutReward = {
                AppToast.warning(this, getString(R.string.toast_watch_full_ad))
            },
            onFailedToShow = { reason ->
                AppToast.error(this, "Error: $reason")
            },
        )
    }

    private fun updateAdProgressBadge() {
        val watched = rewardManager.adsWatchedInBatch
        if (watched == 0) {
            binding.adProgressBadge.visibility = View.GONE
        } else {
            binding.adProgressBadge.visibility = View.VISIBLE
            binding.adProgressBadge.text = "$watched/${rewardManager.config.adsPerReward}"
        }
    }

    private fun onToggleStreamClicked() {
        if (isStreaming) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    /**
     * USB: always 127.0.0.1 — the PC's control_server watches for the phone on its
     * own (see watch_usb_devices in control_server.py) and sets up the adb reverse
     * tunnels the instant it's plugged in, so there's nothing to discover here.
     *
     * Wi-Fi/Auto: broadcast discovery first (the PC's discovery_server now starts
     * eagerly at boot, so this succeeds as long as the PC is on and on the same
     * network — no "Discover" tap in Settings needed). A short timeout keeps this
     * off the critical path when nothing answers; falls back to whatever IP was
     * last saved (e.g. from a manual Settings entry or a previous discovery), then
     * to 127.0.0.1 as a last resort.
     */
    private fun resolveStreamHost(): String {
        val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE)
        if (prefs.getInt("connection_mode", 0) == 1) return "127.0.0.1"

        PcDiscovery.findPc(timeoutMs = 1200)?.let { discovered ->
            prefs.edit().putString("pc_ip", discovered).apply()
            return discovered
        }
        val saved = prefs.getString("pc_ip", "")?.trim() ?: ""
        return if (saved.isNotEmpty()) saved else "127.0.0.1"
    }

    private fun startStreaming() {
        isStreaming = true

        // Constructed synchronously, *before* startCamera() binds the
        // VideoCapture use case — CameraX asks mainVideoOutput for a Surface
        // essentially immediately during that bind, exactly once for the
        // whole session (unlike the old ImageAnalysis.analyze(), called many
        // times a second, where a still-null `streamer` for the first
        // instant was harmless). A null streamer at that single moment used
        // to make mainVideoOutput call request.willNotProvideSurface(),
        // silently killing video for the entire session — confirmed
        // on-device (logcat: "SurfaceProcessorNode: Downstream node failed
        // to provide Surface"). The constructor itself does no I/O (just
        // stores fields and starts a HandlerThread), so this is safe to do
        // on the main thread; only start() below (via hostResolver, called
        // on networkExecutor) actually resolves the PC's address or touches
        // the network.
        val cfg = currentConfig ?: StreamConfig.load(this)
        val newStreamer = CameraStreamer(
            hostResolver = { resolveStreamHost() },
            port = 8787,
            rewardManager = rewardManager,
            streamConfig = cfg,
        )
        streamer = newStreamer

        // Pairing + auth. The receiver rejects non-loopback senders without the
        // PC's token, so Wi-Fi needs one; it is fetched over USB (loopback,
        // already trusted) and cached. Doing both here means the first USB
        // session silently arms every later Wi-Fi session.
        pcConnectExecutor.execute {
            val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE)
            PcControl.fetchToken("127.0.0.1")?.let { token ->
                prefs.edit().putString("pc_token", token).apply()
            }
            newStreamer.authToken = prefs.getString("pc_token", "").orEmpty()
        }

        if (shouldUseCamera2(cfg)) {
            newStreamer.metrics.backend = "camera2"
            startCamera2Backend(cfg, newStreamer)
        } else {
            newStreamer.metrics.backend = "camerax"
            startCamera() // rebind WITH the video-capture stream (kept unbound while idle for a snappy viewfinder)
        }
        applyStreamBrightness(active = true)

        morphRecButton(recording = true)
        binding.recDot.setBackgroundResource(R.drawable.dot_recording)
        startRecDotAnimation()

        newStreamer.start()

        // Best-effort nudge in case the PC's services aren't up yet (e.g. it just
        // rebooted and control_server hasn't finished its own auto-start) — a
        // harmless no-op when they're already running. Independent of
        // newStreamer's own host resolution/connect above.
        pcConnectExecutor.execute {
            val host = resolveStreamHost()
            PcControl.startServices(host)
        }
    }

    private fun stopStreaming() {
        stopCamera2Backend()
        streamer?.stop()
        streamer = null
        isStreaming = false
        startCamera() // rebind preview-only
        applyStreamBrightness(active = false)

        morphRecButton(recording = false)
        binding.recDot.setBackgroundResource(R.drawable.dot_idle)
        stopRecDotAnimation()
    }

    // ─────────────── experimental Camera2 capture backend ───────────────

    /**
     * Phase 1 scope: the flag is on, nothing has failed yet this run, we're on
     * the main back camera, and this device genuinely advertises the requested
     * size at the requested rate (vendor table first, AOSP second — see
     * [Camera2Capabilities]). Anything else stays on CameraX.
     *
     * Front/tele/ultra-wide are excluded on purpose: CameraX reaches those
     * through physical-id routing that this backend doesn't implement yet.
     */
    // isSupported/the source itself are API 28+ (SessionConfiguration, and with
    // it session parameters — the entire point of this backend). isSupported()
    // returns false below that, so the runtime gate is real even though lint
    // can't follow it across the call.
    @android.annotation.SuppressLint("NewApi")
    private fun shouldUseCamera2(cfg: StreamConfig): Boolean {
        val enabled = getSharedPreferences("stream_settings", MODE_PRIVATE)
            .getBoolean("experimental_camera2", false)
        val (width, height) = StreamConfig.pixelSizeFor(cfg.qualityLabel)
        val size = Size(width, height)
        val supported = enabled && Camera2CaptureSource.isSupported(this, CAMERA2_CAMERA_ID, size, cfg.fps)
        val eligible = enabled &&
            !camera2FailedThisRun &&
            cfg.lensFacing == CameraSelector.LENS_FACING_BACK &&
            cfg.lensType == "wide" &&
            supported
        // Every one of these has silently returned false at least once during
        // bring-up, and a silent false is indistinguishable from "the backend
        // ran and was slow" in the metrics — so say which one it was.
        Log.i(
            TAG,
            "camera2 gate: eligible=$eligible (flag=$enabled failedEarlier=$camera2FailedThisRun " +
                "facing=${cfg.lensFacing} lens=${cfg.lensType} " +
                "size=${size.width}x${size.height}@${cfg.fps} supported=$supported)",
        )
        return eligible
    }

    /**
     * Hands the camera from CameraX to Camera2 for this streaming session.
     *
     * Order matters and is not negotiable: only one client may hold a camera,
     * so CameraX has to unbind *completely* before openCamera() is attempted.
     * The encoder is created only once a preview surface exists, so the whole
     * thing is a chain of callbacks rather than a straight line — any link
     * failing lands in [fallbackToCameraX].
     */
    @android.annotation.SuppressLint("NewApi") // reached only via shouldUseCamera2 -> isSupported, which gates on API 28
    private fun startCamera2Backend(cfg: StreamConfig, activeStreamer: CameraStreamer) {
        val (width, height) = StreamConfig.pixelSizeFor(cfg.qualityLabel)
        val captureSize = Size(width, height)
        val chars = Camera2Capabilities.characteristicsOrNull(this, CAMERA2_CAMERA_ID)
        val routedId = Camera2Capabilities.physicalIdFor(this, CAMERA2_CAMERA_ID, captureSize)
        val negotiated = Camera2Capabilities.characteristicsOrNull(this, routedId ?: CAMERA2_CAMERA_ID)
            ?.let { Camera2Capabilities.sessionFpsRange(it, captureSize, cfg.fps) }

        // The honest ceiling for this backend: what it can really negotiate,
        // not the AE table that caps CameraX at 30.
        activeStreamer.cameraFpsCeiling = negotiated?.upper ?: cfg.fps
        camera2Active = true
        camera2AppliedRotation = null

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                providerFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "unbindAll before Camera2 open failed", e)
            }
            camera = null
            currentVideoCapture = null
            binding.previewView.visibility = View.GONE

            // A second output stream costs the capture session real frame rate
            // and heat at 4K60 — the viewfinder is not free. Mode picks the
            // trade-off: 0 = 1080p viewfinder, 1 = 720p, 2 = none at all
            // (encoder stream only, which is what the sensor can sustain best).
            // Default 720p. Measured A/B at 4K60 over 60s per mode: capture
            // averaged 59.70 (no preview) / 59.65 (720p) / 59.62 (1080p) — the
            // viewfinder costs essentially nothing in frame rate, so keeping
            // one is worth it, and 720p is plenty on a phone screen while
            // doing strictly less display work than 1080p.
            val previewMode = getSharedPreferences("stream_settings", MODE_PRIVATE)
                .getInt("camera2_preview_mode", 1)
            val previewCap = when (previewMode) {
                2 -> null
                1 -> Size(1280, 720)
                else -> Size(1920, 1080)
            }
            binding.camera2PreviewView.visibility =
                if (previewCap == null) View.GONE else View.VISIBLE

            // 8K only exists on a physical sub-camera, and a viewfinder fed from
            // the logical camera in the same session would mix two sensors, so
            // routing forces the preview off.
            val physicalId = Camera2Capabilities.physicalIdFor(this, CAMERA2_CAMERA_ID, captureSize)
            val previewSize = if (physicalId != null) null else previewCap?.let { cap ->
                chars?.let { Camera2Capabilities.previewSizeFor(it, cap) } ?: cap
            }
            if (physicalId != null) binding.camera2PreviewView.visibility = View.GONE

            withCamera2PreviewSurface(previewSize) { previewSurface ->
                if (!camera2Active || !isStreaming) return@withCamera2PreviewSurface

                val source = Camera2CaptureSource(
                    context = applicationContext,
                    cameraId = CAMERA2_CAMERA_ID,
                    captureSize = captureSize,
                    desiredFps = cfg.fps,
                    metrics = activeStreamer.metrics,
                    listener = camera2Listener(activeStreamer),
                    physicalCameraId = physicalId,
                )
                camera2Source = source
                applyCamera2Rotation(Surface.ROTATION_0)

                activeStreamer.attachCamera2Surface(
                    cameraWidth = captureSize.width,
                    cameraHeight = captureSize.height,
                    onSurfaceReady = { encoderSurface -> source.start(encoderSurface, previewSurface) },
                    onFailed = { runOnUiThread { fallbackToCameraX("encoder unavailable") } },
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun camera2Listener(activeStreamer: CameraStreamer) = object : Camera2CaptureSource.Listener {
        override fun onStarted(size: Size, fpsRange: Range<Int>) {
            runOnUiThread {
                camera2ActualSize = size
                camera2ActualFps = fpsRange.upper
                activeStreamer.cameraFpsCeiling = fpsRange.upper
                Log.i(TAG, "Camera2 backend running: ${size.width}x${size.height} @ $fpsRange")
                refreshStatusUi()
            }
        }

        override fun onFailed(stage: String, reason: String) {
            runOnUiThread { fallbackToCameraX("$stage: $reason") }
        }
    }

    /**
     * A TextureView has no SurfaceTexture until it's been laid out, and it was
     * GONE until a moment ago — so this either fires immediately or waits for
     * the listener. The watchdog matters: without it, a surface that never
     * arrives leaves the session silently half-started, with the record button
     * lit and nothing streaming.
     */
    private fun withCamera2PreviewSurface(previewSize: Size?, onReady: (Surface?) -> Unit) {
        if (previewSize == null) {
            onReady(null)   // viewfinder disabled: encoder stream only
            return
        }
        val view = binding.camera2PreviewView
        var delivered = false
        fun deliver(surface: Surface?) {
            if (delivered) return
            delivered = true
            onReady(surface)
        }

        view.surfaceTexture?.let { texture ->
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            deliver(Surface(texture))
            return
        }

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                deliver(Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
        }

        // Capture without a viewfinder beats not capturing at all.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!delivered) Log.w(TAG, "Camera2 preview surface never arrived — starting without a viewfinder")
            deliver(null)
        }, 3000)
    }

    /**
     * Mirrors CameraX's own relative-rotation math for a back-facing sensor:
     * how far the captured buffer must be rotated clockwise to come out
     * upright, given how the phone is physically held. CameraX derived this
     * from targetRotation; with Camera2 it has to be computed and pushed into
     * the renderer by hand.
     */
    private fun applyCamera2Rotation(surfaceRotation: Int) {
        if (camera2AppliedRotation == surfaceRotation) return
        val source = camera2Source ?: return
        streamer?.setRotationDegrees(
            RotationPolicy.sensorRotationDegrees(source.sensorOrientation, surfaceRotation),
        )
        camera2AppliedRotation = surfaceRotation
    }

    private fun stopCamera2Backend() {
        camera2Source?.stop()
        camera2Source = null
        camera2Active = false
        camera2ActualSize = null
        camera2ActualFps = null
        camera2AppliedRotation = null
        binding.camera2PreviewView.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
    }

    /**
     * Any Camera2 failure ends the session and restarts it on CameraX, rather
     * than swapping the capture source under a live stream: the encoder is
     * created the first time a surface is provided, so re-binding CameraX onto
     * a streamer that already has one would leave two encoders competing for
     * the same session. [camera2FailedThisRun] makes the retreat one-way, so a
     * fallback can't bounce straight back into the path that just failed.
     */
    private fun fallbackToCameraX(reason: String) {
        if (camera2FailedThisRun) return
        Log.w(TAG, "Camera2 backend unavailable ($reason) — falling back to CameraX")
        camera2FailedThisRun = true
        AppToast.warning(this, getString(R.string.toast_camera2_fallback))

        val wasStreaming = isStreaming
        stopStreaming()
        if (wasStreaming) startStreaming()
    }



    private fun morphRecButton(recording: Boolean) {
        val outer = binding.toggleStreamButton
        val inner = binding.recBtnInner

        val startSize = outer.layoutParams.width.takeIf { it > 0 } ?: dp(68)
        val endSize = if (recording) dp(48) else dp(68)
        val innerStartSize = inner.layoutParams.width.takeIf { it > 0 } ?: dp(24)
        val innerEndSize = if (recording) dp(18) else dp(24)

        outer.setBackgroundResource(if (recording) R.drawable.rec_btn_recording else R.drawable.rec_btn_idle)
        inner.setBackgroundResource(if (recording) R.drawable.rec_btn_inner_recording else R.drawable.rec_btn_inner_idle)

        ValueAnimator.ofInt(startSize, endSize).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                outer.layoutParams = outer.layoutParams.apply { width = size; height = size }
                outer.requestLayout()
            }
            start()
        }

        ValueAnimator.ofInt(innerStartSize, innerEndSize).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                inner.layoutParams = inner.layoutParams.apply { width = size; height = size }
                inner.requestLayout()
            }
            start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupRecButtonPressAnimation() {
        binding.toggleStreamButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.92f).scaleY(0.92f)
                        .setDuration(120)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(OvershootInterpolator(2f))
                        .start()
                    if (event.action == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }

    private fun startRecDotAnimation() {
        recDotAnimator = ObjectAnimator.ofFloat(binding.recDot, View.ALPHA, 1f, 0.2f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopRecDotAnimation() {
        recDotAnimator?.cancel()
        recDotAnimator = null
        binding.recDot.alpha = 1f
    }

    /**
     * Constrains the preview (and, via its shared edges, the grid overlay + focus
     * indicator) to the chosen composition ratio, letterboxing the rest — WYSIWYG
     * with the actual cropped frames the ViewPort applies to the encoder feed.
     */
    private fun applyAspectRatioLetterbox(aspectRatio: String) {
        val (num, denom) = StreamConfig.aspectRatioParts(aspectRatio)
        val params = binding.previewView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = "$num:$denom"
        binding.previewView.layoutParams = params
    }

    /** Starts/stops the 2-bar mic level meter to match the Settings toggle, requesting RECORD_AUDIO if needed. */
    private fun applyAudioMeterSetting(enabled: Boolean) {
        if (!enabled) {
            stopAudioMeter()
            binding.audioMeterContainer.visibility = View.GONE
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudioMeter()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioMeter() {
        if (audioLevelMeter != null) return
        val meter = AudioLevelMeter { left, right -> updateAudioMeterBars(left, right) }
        if (meter.start()) {
            audioLevelMeter = meter
            binding.audioMeterContainer.visibility = View.VISIBLE
        } else {
            AppToast.warning(this, getString(R.string.toast_mic_open_failed))
        }
    }

    private fun stopAudioMeter() {
        audioLevelMeter?.stop()
        audioLevelMeter = null
    }

    private fun updateAudioMeterBars(left: Float, right: Float) {
        setMeterBarLevel(binding.audioMeterBarLeft, left)
        setMeterBarLevel(binding.audioMeterBarRight, right)
    }

    private fun setMeterBarLevel(bar: View, level: Float) {
        val track = bar.parent as View
        val maxHeight = track.height.takeIf { it > 0 } ?: dp(70)
        bar.layoutParams = bar.layoutParams.apply { height = (level * maxHeight).toInt() }
        bar.requestLayout()
        bar.setBackgroundColor(
            getColor(
                when {
                    level > 0.9f -> R.color.accent_red
                    level > 0.7f -> R.color.focus_ring
                    else -> R.color.accent_green
                },
            ),
        )
    }

    private fun startCamera() {
        val cfg = StreamConfig.load(this)
        currentConfig = cfg
        currentLensFacing = cfg.lensFacing

        // Apply UI-only settings immediately
        binding.gridOverlay.visibility = if (cfg.grid) View.VISIBLE else View.GONE
        binding.previewView.scaleX = if (cfg.mirror) -1f else 1f
        applyAspectRatioLetterbox(cfg.aspectRatio)
        applyAudioMeterSetting(cfg.audioMeterEnabled)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val (targetWidth, targetHeight) = StreamConfig.pixelSizeFor(cfg.qualityLabel)

            // The preview genuinely captures at the chosen resolution (down to
            // 360p etc, real pixels in, not a post-processing effect) — only
            // capped downward for very high targets, since pushing a raw 4K/8K
            // surface into PreviewView is what caused the ~0.5s viewfinder lag.
            val previewWidth: Int
            val previewHeight: Int
            if (targetWidth.toLong() * targetHeight > 1920L * 1080) {
                previewWidth = 1920; previewHeight = 1080
            } else {
                previewWidth = targetWidth; previewHeight = targetHeight
            }
            val previewSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(previewWidth, previewHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
            val videoSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(targetWidth, targetHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()

            val cameraBinding = resolveCameraBinding(cfg)
            val selector = cameraBinding.selector
            val physicalCameraId = cameraBinding.physicalCameraId

            val fpsRange = cameraBinding.cameraId?.let {
                DeviceCapabilities.closestFpsRange(
                    DeviceCapabilities.availableFpsRanges(this, it, physicalCameraId),
                    cfg.fps,
                )
            }
            // The real ceiling this sensor can deliver, not just what the user
            // picked — see CameraStreamer.cameraFpsCeiling's doc. Without this,
            // picking 60fps on hardware that only negotiates up to 30 made the
            // PC side (Hello.fps, OBS canvas sync, pyvirtualcam) believe it was
            // getting 60 and duplicate frames to compensate, which looked worse
            // than just picking 30.
            streamer?.cameraFpsCeiling = fpsRange?.upper ?: cfg.fps

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewSelector)
                // Deliberately NOT setTargetRotation(ROTATION_90) to match the
                // ViewPort: that tells CameraX "the output is already in the
                // sensor's own landscape orientation, don't rotate", and the
                // viewfinder then shows the scene lying on its side. Leaving
                // the default (ROTATION_0, since this Activity is locked to
                // portrait) makes CameraX apply
                //   relativeRotation = sensorOrientation(90) - target(0) = 90
                // i.e. the 90-degree clockwise rotation the image needs to come
                // out upright. The ViewPort still needs ROTATION_90 for the
                // crop geometry - the two serve different purposes and it is
                // correct for them to differ here.
            applyCamera2Options(previewBuilder, cfg, physicalCameraId, fpsRange)

            // 10-bit HLG viewfinder when the user enabled HDR and this camera can do it.
            // Skipped while streaming: the JPEG network pipeline is 8-bit, and mixing an
            // HLG preview with an SDR analysis stream fails to bind on most devices.
            if (cfg.hdr && !isStreaming && DeviceCapabilities.supports10BitHdr(this)) {
                try {
                    previewBuilder.setDynamicRange(DynamicRange.HLG_10_BIT)
                } catch (e: Exception) {
                    Log.w(TAG, "HLG preview not accepted, staying SDR", e)
                }
            }
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // VideoCapture (our custom Surface-based output, see StreamingVideoOutput)
            // is only bound while actually streaming: an idle 4K/8K capture stream
            // drags the whole capture session (and the viewfinder) down even though
            // every frame would go nowhere.
            //
            // This is a Surface-based capture path (like VideoCapture's own built-in
            // Recorder, the same class real camera apps use for 4K60 recording) —
            // deliberately NOT ImageAnalysis, whose CPU-accessible YUV_420_888 buffers
            // confirmed-on-device capped 4K capture at ~30fps even with a 60fps
            // target, regardless of encoder/network headroom. mainVideoOutput forwards
            // each SurfaceRequest to whichever CameraStreamer is current.
            val videoCapture = if (isStreaming) {
                // Decides the ordered candidate list VideoCapture builds from
                // the VideoOutput's MediaSpec — which outranks the
                // ResolutionSelector below. See StreamingVideoOutput.targetHeight.
                mainVideoOutput.targetHeight = targetHeight
                val videoCaptureBuilder = VideoCapture.Builder(mainVideoOutput)
                    .setResolutionSelector(videoSelector)
                    .setTargetRotation(RotationPolicy.landscapeTargetRotation(
                        binding.previewView.display?.rotation ?: Surface.ROTATION_0))
                    .setTargetFrameRate(android.util.Range(cfg.fps, cfg.fps))
                applyPhysicalCameraId(videoCaptureBuilder, physicalCameraId, fpsRange)
                videoCaptureBuilder.build()
            } else {
                null
            }
            currentVideoCapture = videoCapture

            // ViewPort crops every bound use case (preview AND the capture stream that
            // feeds the network encoder) to the same rectangle, so "Composition" actually
            // changes what's sent to the PC — not just a cosmetic letterbox over an
            // uncropped 16:9 sensor feed.
            val (ratioNum, ratioDenom) = StreamConfig.aspectRatioParts(cfg.aspectRatio)
            // The rotation argument says which orientation the aspect ratio is
            // expressed in — NOT which way the phone is held. This Activity is
            // locked to portrait, so display.rotation was always ROTATION_0 and
            // CameraX read "16:9" as 16:9 *in portrait*, i.e. a tall narrow
            // slice: measured, a 1080p session delivered 1080x608 and a 4K one
            // 2160x1216, cropping away the sides of the scene and looking, on
            // the viewfinder, like the image had been rotated 90 degrees.
            // The output (encoder and preview alike) is landscape, so the ratio
            // has to be expressed in a landscape rotation.
            val viewPort = ViewPort.Builder(
                android.util.Rational(ratioDenom, ratioNum),
                binding.previewView.display?.rotation ?: android.view.Surface.ROTATION_0,
            ).build()

            fun buildUseCaseGroup(previewUseCase: Preview) = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(previewUseCase)
                .apply { videoCapture?.let { addUseCase(it) } }
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(cameraLifecycleOwner, selector, buildUseCaseGroup(preview))
                Log.i(TAG, "camera bound: ${cfg.qualityLabel} · ${cfg.aspectRatio} · lens=$currentLensFacing · streaming=$isStreaming")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed for ${cfg.qualityLabel}", e)
                AppToast.warning(this, "${cfg.qualityLabel} not supported by this camera")
                // Fall back to sensible defaults
                cameraProvider.unbindAll()
                val fallbackPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                camera = cameraProvider.bindToLifecycle(cameraLifecycleOwner, selector, buildUseCaseGroup(fallbackPreview))
            }

            setupCameraDependentControls(cfg)
            if (torchOn) camera?.cameraControl?.enableTorch(true)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Video stabilization and white balance were previously Settings toggles with no
     * effect at all — saved to prefs, never read by the camera pipeline. Camera2Interop
     * lets CameraX carry raw CaptureRequest options through to the capture session.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2Options(
        builder: Preview.Builder,
        cfg: StreamConfig,
        physicalCameraId: String?,
        fpsRange: android.util.Range<Int>?,
    ) {
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (cfg.stabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, cfg.whiteBalanceMode)
        if (physicalCameraId != null) extender.setPhysicalCameraId(physicalCameraId)

        // Constrains the actual capture session to the chosen fps (e.g. picking 24fps
        // visibly throttles the viewfinder) instead of just labeling the stream — before
        // this, "fps" was saved to prefs and used to tag the network Hello packet, but
        // never once reached the camera, so the preview always ran at whatever default
        // rate the sensor negotiated regardless of what was picked in Settings.
        if (fpsRange != null) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
    }

    /**
     * Routes a UseCase onto a hidden physical sub-lens (e.g. the 3x/10x tele modules
     * on Samsung Ultra phones) via Camera2Interop — the only way to reach a lens that
     * CameraSelector can't see as its own top-level camera. No-op when physicalCameraId
     * is null (either facing the front camera, "wide" was picked, or this device has
     * no distinct id for that lens at all).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyPhysicalCameraId(
        builder: VideoCapture.Builder<com.phonecam.streamer.streaming.StreamingVideoOutput>,
        physicalCameraId: String?,
        fpsRange: android.util.Range<Int>?,
    ) {
        val extender = Camera2Interop.Extender(builder)
        if (physicalCameraId != null) extender.setPhysicalCameraId(physicalCameraId)
        if (fpsRange != null) extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
    }

    /**
     * Resolves both what to bind (CameraSelector) and, when the requested lens is
     * hidden as a physical sub-id of a logical multi-camera (the common case for
     * Samsung's tele/periscope lenses — see DeviceCapabilities.findLensTarget), what
     * physical id to route the streams through via Camera2Interop afterward.
     *
     * CameraSelector.addCameraFilter only sees top-level enumerable cameras, so it
     * can bind a lens that has its OWN id, but it can never reach a lens that's only
     * exposed as a physical sub-id — attempting to filter for one there always comes
     * back empty and silently falls through to the default camera. That's the exact
     * bug that made the 3x chip look identical to 1x: this device's 3x tele has no
     * top-level id, only a physical sub-id, so the old filter-based approach could
     * never find it and always bound the plain wide sensor.
     */
    /**
     * Known devices: match lenses by focal-length ORDER against the database (see
     * DeviceCapabilities.findLensTargetsForKnownDevice) — fixed mm thresholds
     * misclassify real lenses on some sensor stacks. Unknown devices fall back to
     * the threshold guess. Shared by resolveCameraBinding and the
     * setupCameraDependentControls digital-zoom fallback so they never disagree
     * about whether a lens was actually found.
     */
    private fun findLensTargetFor(lensType: String): DeviceCapabilities.LensTarget? {
        val knownSpec = DeviceModelDatabase.lookup(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        return if (knownSpec != null) {
            DeviceCapabilities.findLensTargetsForKnownDevice(this, knownSpec)[lensType]
        } else {
            DeviceCapabilities.findLensTarget(this, "back", lensType)
        }
    }

    /** cameraId is the top-level id CameraManager can query characteristics for (e.g. AE fps ranges) even when physicalCameraId is also set. */
    private data class CameraBinding(val selector: CameraSelector, val cameraId: String?, val physicalCameraId: String?)

    private fun resolveCameraBinding(cfg: StreamConfig): CameraBinding {
        val plainSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()
        val facingName = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
        val defaultId = DeviceCapabilities.defaultCameraId(this, facingName)

        if (currentLensFacing != CameraSelector.LENS_FACING_BACK || cfg.lensType == "wide") {
            return CameraBinding(plainSelector, defaultId, null)
        }

        val target = findLensTargetFor(cfg.lensType)
        if (target == null) {
            Log.w(TAG, "no ${cfg.lensType} camera on this device, using default back camera")
            val displayName = when (cfg.lensType) {
                "wide" -> getString(R.string.lens_wide)
                "ultra-wide" -> getString(R.string.lens_ultrawide)
                "telephoto" -> getString(R.string.lens_telephoto)
                "supertelephoto" -> getString(R.string.lens_supertelephoto)
                else -> getString(R.string.label_unknown)
            }
            AppToast.warning(this, getString(R.string.toast_lens_not_available, displayName))
            return CameraBinding(plainSelector, defaultId, null)
        }

        if (target.physicalId == null) {
            // The lens has its own top-level id — filtering directly to it works fine.
            val selector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == target.cameraId }
                }
                .build()
            return CameraBinding(selector, target.cameraId, null)
        }

        // Hidden physical sub-camera: bind the plain logical selector, then route
        // Preview/ImageAnalysis onto target.physicalId via Camera2Interop below.
        return CameraBinding(plainSelector, target.cameraId, target.physicalId)
    }

    override fun onResume() {
        super.onResume()
        // Only ever moves UP here — see cameraLifecycleOwner's doc for why it's
        // never downgraded in onPause()/onStop().
        cameraLifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        orientationEventListener.enable()
        applyDisplayPreferences()
        // Re-apply settings in case the user changed something in Settings
        if (camera != null) startCamera()
    }

    /** DISPLAY settings: keep-awake always, brightness override only while streaming. */
    private fun applyDisplayPreferences() {
        val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE)
        if (prefs.getBoolean("keep_screen_on", true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyStreamBrightness(active = isStreaming)
    }

    private fun applyStreamBrightness(active: Boolean) {
        val mode = getSharedPreferences("stream_settings", MODE_PRIVATE).getInt("stream_brightness", 0)
        val lp = window.attributes
        lp.screenBrightness = when {
            !active || mode == 0 -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            mode == 1 -> 1f      // full brightness while streaming
            else -> 0.15f        // dimmed to save battery/heat while streaming
        }
        window.attributes = lp
    }

    private fun startUiTicker() {
        uiHandler.post(object : Runnable {
            override fun run() {
                refreshStatusUi()
                uiHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun refreshStatusUi() {
        val profile = rewardManager.currentProfile()

        binding.tierText.text = if (profile.premiumActive) {
            getString(R.string.status_premium_tier)
        } else {
            getString(R.string.status_free_tier)
        }
        binding.tierText.setBackgroundResource(
            if (profile.premiumActive) R.drawable.badge_pro else R.drawable.badge_free
        )
        binding.tierText.setTextColor(
            if (profile.premiumActive) 0xFF14181A.toInt() else 0xAAFFFFFF.toInt()
        )

        if (isStreaming) {
            binding.streamStatusText.text = "REC • ${streamingResolutionLabel()}"
        } else {
            binding.streamStatusText.text = getString(R.string.not_streaming)
        }

        binding.hdrBadge.visibility = if (currentConfig?.hdr == true) View.VISIBLE else View.GONE
    }

    /**
     * What's actually being encoded right now, not a tier guess — presets
     * read as "1080p60" (matches the old hardcoded text), a custom
     * resolution as "1920x1080 60fps" (qualityLabel is already the literal
     * "WIDTHxHEIGHT" string for those, see StreamConfig.load). Falls back to
     * the tier ceiling only if a session hasn't actually started yet.
     */
    private fun streamingResolutionLabel(): String {
        val cfg = currentConfig ?: return if (rewardManager.currentProfile().premiumActive) "4K60" else "1080p60"
        // Camera2 reports what it actually negotiated with the HAL, which is
        // the number worth showing — under CameraX the label can only ever
        // echo the request back, true or not.
        camera2ActualSize?.let { size ->
            return "${size.width}x${size.height} ${camera2ActualFps ?: cfg.fps}fps"
        }
        return if (cfg.qualityLabel.contains("x")) {
            "${cfg.qualityLabel} ${cfg.fps}fps"
        } else {
            "${cfg.qualityLabel}${cfg.fps}"
        }
    }

    private fun rewardPrefs() = getSharedPreferences("reward_state", MODE_PRIVATE)

    private fun loadRewardManager(): RewardManager {
        val raw = rewardPrefs().getString("state_json", null) ?: return RewardManager()
        return try {
            RewardManager.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            RewardManager()
        }
    }

    private fun saveRewardManager() {
        rewardPrefs().edit().putString("state_json", rewardManager.toJson().toString()).apply()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
        // rewardManager is only set up once the welcome dialog is dismissed
        // (see continueOnCreate) — confirmed on-device, backgrounding the
        // app (home button, incoming call, screen timeout) while that
        // dialog is still showing fires onPause() first, and saving here
        // unconditionally crashed with UninitializedPropertyAccessException.
        if (::rewardManager.isInitialized) saveRewardManager()
        // Background apps shouldn't hold the mic open — re-acquired in startCamera()
        // via applyAudioMeterSetting() when the app comes back to the foreground.
        stopAudioMeter()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The only place cameraLifecycleOwner ever moves down — see its doc.
        cameraLifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        recDotAnimator?.cancel()
        streamer?.stop()
        stopAudioMeter()
        cameraExecutor.shutdown()
        pcConnectExecutor.shutdown()
    }
}
