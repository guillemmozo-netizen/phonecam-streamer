package com.phonecam.streamer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.HapticFeedbackConstants
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.widget.PopupMenu
import com.phonecam.streamer.screen.ScreenCaptureService
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
import com.phonecam.streamer.streaming.RotationDebouncer
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
    // The config the ACTIVE streaming session was built from (encoder shape,
    // Hello announcement). Distinct from currentConfig, which follows every
    // Settings save; the gap between the two mid-session is exactly the
    // camera-cropping-new/encoder-shaped-old mismatch — see onResume.
    private var streamingConfig: StreamConfig? = null
    private var torchOn = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var wasScaling = false
    private var audioLevelMeter: AudioLevelMeter? = null

    // ─── screen-capture session state (source = "Pantalla") ───
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenSessionActive = false
    // What the encoder was actually built at, for the status line — the
    // camera-mode label logic reads Settings, which screen mode ignores.
    private var screenSessionLabel: String? = null
    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    // The system can revoke a projection at any moment (the user taps "stop
    // sharing" in the status bar, or another app takes the projection). That
    // MUST end the session cleanly rather than leave the encoder feeding a
    // dead display.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            uiHandler.post { if (screenSessionActive) stopStreaming() }
        }
    }

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
    // Deciding *when* a reading is a real turn lives in RotationDebouncer, which
    // is unit-tested; this only applies the result. Keeping the two apart is
    // what surfaced the bug where the debounce compared a device bucket against
    // targetRotation, which carries a quarter-turn offset — see its doc.
    private val rotationDebouncer = RotationDebouncer(ROTATION_DEBOUNCE_MS)

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = rotationDebouncer.onOrientationChanged(
                    orientation, android.os.SystemClock.elapsedRealtime(),
                ) ?: return
                // The RAW bucket is the rotation reference — no quarter-turn
                // shift. Validated on hardware (Round 3 geometry lines): with
                // the shifted reference, every hold streamed 90° clockwise of
                // upright on BOTH backends (vertical applied θ=0 where the
                // content needed 90; horizontal applied 270 where it needed 0).
                currentVideoCapture?.targetRotation = rotation
                // Camera2 has no targetRotation to push this into — the
                // equivalent is computed from the sensor's mounting and
                // handed to the renderer directly.
                if (camera2Active) applyCamera2Rotation(rotation)
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
                // Two paths reach this launcher now — the level-meter toggle
                // and starting a stream — so honour the setting rather than
                // assuming the meter was what asked, which would pop the
                // meter up on a user who never enabled it.
                if ((currentConfig ?: StreamConfig.load(this)).audioMeterEnabled) startAudioMeter()
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

    /**
     * Asked for right before a screen session, never at launch.
     *
     * Without it on Android 13+, the capture service's notification is simply
     * not shown — the service still runs, so casting works, but the only
     * control that exists while the user is in another app is invisible.
     * Reported from use as "there is no way to stop it", and the shade
     * confirmed it: no FrameCast notification at all, just the system's cast
     * icon. The session continues either way; a refusal only costs the
     * outside-the-app Stop button, which the toast explains.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                AppToast.warning(this, getString(R.string.screen_notification_denied))
            }
            if (pendingScreenStart) {
                pendingScreenStart = false
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }

    /** A screen session waiting for the notification prompt to be answered. */
    private var pendingScreenStart = false

    // The system's screen-capture consent dialog. Launched instead of
    // startStreaming() when the source is "screen" and no projection exists
    // yet — so a refusal changes nothing: no session was started.
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startScreenServiceThenStream(result.resultCode, data)
            } else {
                AppToast.warning(this, getString(R.string.screen_capture_denied))
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
        binding.sourceButton.setOnClickListener { showSourceMenu() }
        addPressPop(binding.settingsButton)
        addPressPop(binding.flipCameraButton)
        addPressPop(binding.torchButton)
        addPressPop(binding.sourceButton)
        updateSourceIcon()

        // A capture service left over from an activity that was destroyed
        // mid-cast: nothing owns it now, so without this it would sit in the
        // notification shade with the system still marking the screen as
        // shared, and the fresh UI would look idle next to it.
        if (ScreenCaptureService.isRunning && mediaProjection == null) {
            Log.w(TAG, "an orphaned screen-capture service was still running — stopping it")
            ScreenCaptureService.stop(this)
        }

        cameraLifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        setupCameraControls()
        setupPreviewGestures()
        requestCameraPermission()
        initializeAdsWithConsent()
        startUiTicker()
        animateEntrance()
        warmUpDeviceProbe()
    }

    /** The probed device, once it lands. Null until then — never blocked on. */
    private var deviceInfo: DeviceCapabilities.DeviceInfo? = null

    /**
     * Runs the camera probe off the main thread so the zoom chips can be built
     * from this phone's real lenses.
     *
     * Rebuilds the chips when it finishes, because on a cold start the camera
     * binds before the probe completes and the first pass has nothing to work
     * from. Cached in DeviceCapabilities, so Settings opening later is free.
     */
    private fun warmUpDeviceProbe() {
        deviceInfo = DeviceCapabilities.cachedOrNull()
        if (deviceInfo != null) return
        cameraExecutor.execute {
            val probed = try {
                DeviceCapabilities.probe(this)
            } catch (e: Exception) {
                Log.w(TAG, "device probe failed — zoom chips fall back to digital", e)
                null
            } ?: return@execute
            runOnUiThread {
                deviceInfo = probed
                val cam = camera ?: return@runOnUiThread
                currentConfig?.let { buildZoomChips(cam, it) }
            }
        }
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
    // The stops actually offered, built from this camera's own reported limits
    // in setupExposureControls. The fallbacks are only ever seen before the
    // first bind; every real session replaces them with hardware values.
    private var isoStops: List<Int> = ExposureScale.isoStops(100, 3200)
    private var shutterStopsNs: List<Long> = ExposureScale.shutterStopsNs(125_000L, 33_333_333L)
    private var isoIndex = 0
    private var shutterIndex = 0
    private var evIndex = 0
    private var manualFocusDiopters: Float? = null
    private var focusProgress = 0

    /** Which control owns the shared slider right now, or null while it is closed. */
    private enum class ExposureControl { SHUTTER, ISO, EV, FOCUS }
    private var openControl: ExposureControl? = null

    private fun currentIso(): Int = isoStops.getOrElse(isoIndex) { isoStops.first() }
    private fun currentShutterNs(): Long = shutterStopsNs.getOrElse(shutterIndex) { shutterStopsNs.first() }

    private fun setupCameraControls() {
        binding.panelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                onPanelProgress(progress, fromUser)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Persist on release rather than on every pixel of the drag:
                // a manual look is worth keeping across restarts, an
                // apply()-per-frame is not.
                saveExposurePrefs()
            }
        })

        binding.shutterChip.setOnClickListener { toggleControl(ExposureControl.SHUTTER) }
        binding.isoChip.setOnClickListener { toggleControl(ExposureControl.ISO) }
        binding.evChip.setOnClickListener { toggleControl(ExposureControl.EV) }
        binding.mfChip.setOnClickListener { toggleControl(ExposureControl.FOCUS) }

        binding.aeToggle.setOnClickListener {
            aeEnabled = !aeEnabled
            saveExposurePrefs()
            // The open slider may be one that no longer exists in the new mode
            // (EV is meaningless under manual, shutter under AE), so close it
            // rather than leave a slider driving a control nothing reads.
            if (openControl == ExposureControl.EV || openControl == ExposureControl.SHUTTER ||
                openControl == ExposureControl.ISO
            ) {
                closePanel()
            }
            updateExposureUi()
            rebuildCaptureOptions()
            // When AE comes back, clear any leftover manual bias look by resetting EV
            if (aeEnabled) {
                evIndex = 0
                camera?.cameraControl?.setExposureCompensationIndex(0)
                updateExposureReadouts()
            }
        }
    }

    /**
     * The one place a slider movement becomes a camera setting. Which control
     * it belongs to is [openControl], so all four share a single SeekBar and
     * a single listener instead of four near-identical ones.
     */
    private fun onPanelProgress(progress: Int, fromUser: Boolean) {
        when (openControl) {
            ExposureControl.SHUTTER -> {
                val next = progress.coerceIn(0, shutterStopsNs.lastIndex)
                if (fromUser && next != shutterIndex) tickDetent()
                shutterIndex = next
                if (fromUser) rebuildCaptureOptions()
            }
            ExposureControl.ISO -> {
                val next = progress.coerceIn(0, isoStops.lastIndex)
                if (fromUser && next != isoIndex) tickDetent()
                isoIndex = next
                if (fromUser) rebuildCaptureOptions()
            }
            ExposureControl.EV -> {
                val cam = camera ?: return
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                evIndex = mapProgressToRange(progress, range)
                cam.cameraControl.setExposureCompensationIndex(evIndex)
            }
            ExposureControl.FOCUS -> {
                focusProgress = progress
                applyFocusDistance(progress)
            }
            null -> return
        }
        updateExposureReadouts()
    }

    /**
     * One click of feedback per stop crossed, the way a physical dial detents.
     *
     * This is what makes 1/6-stop density controllable rather than just
     * denser: the stops are ~4dp apart on the rail, close enough that the eye
     * cannot confirm a single-stop nudge mid-drag, and CLOCK_TICK — the same
     * constant the platform's own pickers use — reports each one through the
     * finger instead. Only ever fired for a real value change, so holding
     * still is silent.
     *
     * Deliberately without FLAG_IGNORE_GLOBAL_SETTING: someone who turned
     * system haptics off meant it, and a camera app is not the place to
     * overrule that.
     */
    private fun tickDetent() {
        binding.panelSeekBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Opens [control]'s slider, or closes it if it is already the open one. */
    private fun toggleControl(control: ExposureControl) {
        if (openControl == control) closePanel() else openPanel(control)
    }

    /**
     * Slides the shared slider out from behind the rail.
     *
     * translationX rather than an animated width: the SeekBar inside is
     * rotated 270°, so its measured box is its own length and animating that
     * re-lays-out (and visibly re-rotates) the track on every frame. Sliding
     * a laid-out panel is one property, runs on the render thread, and is the
     * same idiom animateEntrance already uses for this rail.
     */
    private fun openPanel(control: ExposureControl) {
        openControl = control
        val (title, max, progress) = when (control) {
            ExposureControl.SHUTTER ->
                Triple(getString(R.string.shutter_label), shutterStopsNs.lastIndex, shutterIndex)
            ExposureControl.ISO ->
                Triple(getString(R.string.iso_label), isoStops.lastIndex, isoIndex)
            ExposureControl.EV -> {
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                Triple(
                    getString(R.string.ev_label),
                    100,
                    if (range == null) 50 else mapRangeToProgress(evIndex, range),
                )
            }
            ExposureControl.FOCUS ->
                Triple(getString(R.string.manual_focus_label), 100, focusProgress)
        }
        binding.panelTitle.text = title
        // max before progress: a progress above the previous max is clamped.
        binding.panelSeekBar.max = max.coerceAtLeast(1)
        binding.panelSeekBar.progress = progress.coerceIn(0, max.coerceAtLeast(1))
        binding.panelSeekBar.thumbTintList = android.content.res.ColorStateList.valueOf(
            if (control == ExposureControl.FOCUS) getColor(R.color.focus_ring) else 0xFFFFFFFF.toInt(),
        )
        updateExposureReadouts()
        updateChipHighlight()

        val panel = binding.exposurePanel
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            // Starts tucked behind the rail (positive X is toward it) and
            // slides left into place.
            panel.translationX = dp(28).toFloat()
        }
        panel.animate().cancel()
        panel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    private fun closePanel() {
        openControl = null
        updateChipHighlight()
        val panel = binding.exposurePanel
        if (panel.visibility != View.VISIBLE) return
        panel.animate().cancel()
        panel.animate()
            .translationX(dp(28).toFloat())
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { panel.visibility = View.GONE }
            .start()
    }

    /** The open chip reads as selected; the rest stay quiet. */
    private fun updateChipHighlight() {
        listOf(
            ExposureControl.SHUTTER to binding.shutterChipValue,
            ExposureControl.ISO to binding.isoChipValue,
            ExposureControl.EV to binding.evChipValue,
            ExposureControl.FOCUS to binding.mfChipValue,
        ).forEach { (control, valueView) ->
            valueView.setTextColor(
                if (openControl == control) getColor(R.color.focus_ring) else 0xFFFFFFFF.toInt(),
            )
        }
    }

    /**
     * Every readout that shows an exposure value: the chips (always visible)
     * and the open panel (when there is one). One function so a chip can never
     * disagree with the slider that set it.
     */
    private fun updateExposureReadouts() {
        val shutterText = ExposureScale.formatShutter(currentShutterNs())
        val isoText = currentIso().toString()
        val evText = "%.1f".format(evIndex * evStep())
        binding.shutterChipValue.text = shutterText
        binding.isoChipValue.text = isoText
        binding.evChipValue.text = evText

        when (openControl) {
            ExposureControl.SHUTTER -> {
                binding.panelValue.text = shutterText
                // The honest part: a shutter slower than the frame interval
                // caps the capture rate, so say which rate rather than let the
                // stream quietly halve.
                val ceiling = ExposureScale.frameRateCeiling(currentShutterNs())
                val targetFps = currentConfig?.fps ?: 30
                if (ceiling < targetFps) {
                    binding.panelHint.text = getString(R.string.fps_ceiling_format, ceiling)
                    binding.panelHint.visibility = View.VISIBLE
                    binding.panelValue.setTextColor(getColor(R.color.accent_amber))
                } else {
                    binding.panelHint.visibility = View.GONE
                    binding.panelValue.setTextColor(0xFFFFFFFF.toInt())
                }
            }
            ExposureControl.ISO -> {
                binding.panelValue.text = isoText
                binding.panelValue.setTextColor(0xFFFFFFFF.toInt())
                binding.panelHint.visibility = View.GONE
            }
            ExposureControl.EV -> {
                binding.panelValue.text = evText
                binding.panelValue.setTextColor(0xFFFFFFFF.toInt())
                binding.panelHint.visibility = View.GONE
            }
            ExposureControl.FOCUS -> {
                binding.panelValue.text = binding.mfChipValue.text
                binding.panelValue.setTextColor(0xFFFFFFFF.toInt())
                binding.panelHint.visibility = View.GONE
            }
            null -> Unit
        }
    }

    private fun evStep(): Float =
        camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: (1f / 6f)

    private fun saveExposurePrefs() {
        getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
            .putBoolean("auto_exposure", aeEnabled)
            .putInt("manual_iso", currentIso())
            .putLong("manual_shutter_ns", currentShutterNs())
            .apply()
    }

    /** Show EV under auto-exposure; show shutter+ISO under manual. */
    private fun updateExposureUi() {
        if (!manualSensorSupported) {
            // Camera can't do manual sensor control — EV-only, hide the AE toggle
            binding.aeToggle.visibility = View.GONE
            binding.shutterChip.visibility = View.GONE
            binding.isoChip.visibility = View.GONE
            binding.evChip.visibility = View.VISIBLE
            return
        }
        binding.aeToggle.visibility = View.VISIBLE
        binding.aeToggle.setTextColor(
            if (aeEnabled) getColor(R.color.focus_ring) else 0xFFFFFFFF.toInt(),
        )
        binding.aeToggle.text =
            if (aeEnabled) getString(R.string.ae_label) else getString(R.string.manual_exposure_label)
        binding.shutterChip.visibility = if (aeEnabled) View.GONE else View.VISIBLE
        binding.isoChip.visibility = if (aeEnabled) View.GONE else View.VISIBLE
        binding.evChip.visibility = if (aeEnabled) View.VISIBLE else View.GONE
        updateExposureReadouts()
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
            val exposureNs = currentShutterNs()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso())
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            // Without this, a long shutter is a request the HAL is entitled to
            // ignore. Camera2's contract is exposure_time <= frame_duration,
            // and frame duration otherwise comes from the session's target fps
            // — so at 60fps every exposure longer than 16.7ms was silently
            // clamped back to 16.7ms, which is why the slider's slow end used
            // to do visibly nothing. Asking for a frame duration that fits the
            // exposure is what actually buys the long end of the range; the
            // frame rate drops to match, which is what the panel's amber
            // "≤N fps" is warning about.
            val framePeriodNs = 1_000_000_000L / (currentConfig?.fps ?: 30).coerceAtLeast(1)
            b.setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(exposureNs, framePeriodNs),
            )
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
        if (screenSessionActive) {
            AppToast.info(this, getString(R.string.screen_controls_unavailable))
            return
        }
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
        // Mid-stream, a facing change is a session restart — the same
        // contract Settings geometry changes follow (StreamConfig.
        // requiresSessionRestart lists lensFacing), and the only path that
        // tears the Camera2 backend down. The old bare startCamera() rebind
        // left camera2Active alive across the flip: the zombie source kept
        // capturing into a released surface, and the orientation listener
        // kept overwriting the front stream's rotation with back-sensor
        // math — two writers, last one wins. stopCamera2Backend has exactly
        // one call site (stopStreaming), so restarting is what guarantees
        // the teardown.
        if (isStreaming) {
            Log.i(TAG, "camera change while streaming — restarting the session")
            stopStreaming()
            startStreaming()
        } else {
            startCamera()
        }
    }

    private fun mapProgressToRange(progress: Int, range: Range<Int>): Int {
        val fraction = progress / 100f
        return (range.lower + fraction * (range.upper - range.lower)).toInt()
    }

    /**
     * The inverse, so reopening the EV slider puts the thumb where the value
     * already is instead of snapping back to the middle of the range.
     */
    private fun mapRangeToProgress(value: Int, range: Range<Int>): Int {
        val span = range.upper - range.lower
        if (span <= 0) return 50
        return (((value - range.lower).toFloat() / span) * 100).roundToInt().coerceIn(0, 100)
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
                        // Same restart contract as flipCamera: a physical-lens
                        // change mid-stream re-selects the backend (only the
                        // wide 1x is Camera2-eligible) and must tear the old
                        // one down — the bare rebind left it running.
                        if (isStreaming) {
                            Log.i(TAG, "lens change while streaming — restarting the session")
                            stopStreaming()
                            startStreaming()
                        } else {
                            startCamera() // rebind onto that physical camera
                        }
                    } else if (activeDigitalZoom != null) {
                        cam.cameraControl.setZoomRatio(1f) // back to plain 1x
                        buildZoomChips(cam, cfg)
                    }
                })
            }
            return
        }

        // Unknown model, but the probe found real lenses: build the chips from
        // those instead of from generic digital-zoom steps.
        //
        // This is the path every phone that is not in the database takes, and
        // it used to end at the digital fallback below — measured on a Redmi
        // Note 11S, which has a real 0.6x ultra-wide and was showing 1x/2x/5x
        // digital crops of the main sensor and no way to reach the second
        // lens at all.
        // One chip per lens category, and where a category has several
        // cameras the biggest sensor wins. Phones expose more back ids than
        // they have lenses — a Redmi Note 11S reports both the 12MP main at
        // 1.0x and a logical wrapper around it at 0.8x, and picking by list
        // order would have made the wrapper the "1x". Sensor size is the
        // property that actually distinguishes the real lens from a wrapper.
        val probedLenses = deviceInfo?.cameras
            ?.filter { it.facing == "back" && it.zoomFactor != null }
            ?.groupBy { it.lens }
            ?.mapNotNull { (_, cams) -> cams.maxByOrNull { it.sensorMegapixels } }
            ?.sortedBy { it.zoomFactor }
        if (probedLenses != null && probedLenses.size >= 2) {
            row.visibility = View.VISIBLE
            probedLenses.forEach { probed ->
                val active = probed.lens == cfg.lensType
                row.addView(makeZoomChip(formatZoomFactor(probed.zoomFactor ?: 1f), active) {
                    if (probed.lens == cfg.lensType) return@makeZoomChip
                    getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
                        .putInt("lens", lensPrefIndex[probed.lens] ?: 0)
                        .apply()
                    // Same restart contract as the known-model branch above.
                    if (isStreaming) {
                        Log.i(TAG, "lens change while streaming — restarting the session")
                        stopStreaming()
                        startStreaming()
                    } else {
                        startCamera()
                    }
                })
            }
            return
        }

        // Nothing distinct to switch to: generic digital-zoom steps clipped to
        // the camera's range, which is all a single-lens phone can offer.
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
                if (screenSessionActive) {
                    AppToast.info(this@MainActivity, getString(R.string.screen_controls_unavailable))
                    return@setOnClickListener
                }
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

    /**
     * The focus distance the MF chip's slider maps onto, in diopters — kept as
     * state because the slider that drives it is now shared with ISO, shutter
     * and EV, so the conversion can no longer live in a closure that only
     * exists while the MF slider does.
     */
    private var minFocusDiopters = 0f

    private fun applyFocusDistance(progress: Int) {
        if (minFocusDiopters <= 0f) return
        val diopters = (progress / 100f) * minFocusDiopters
        manualFocusDiopters = diopters
        rebuildCaptureOptions()
        binding.mfChipValue.text = if (diopters < 0.05f) "∞" else "%.1fm".format(1f / diopters)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusMode(cam: Camera, cfg: StreamConfig) {
        val manual = cfg.autofocusMode == StreamConfig.AutofocusMode.MANUAL

        fun disableManualFocus() {
            binding.mfChip.visibility = View.GONE
            if (openControl == ExposureControl.FOCUS) closePanel()
            minFocusDiopters = 0f
            manualFocusDiopters = null
            rebuildCaptureOptions()
        }

        if (!manual) {
            disableManualFocus()
            return
        }

        // LENS_INFO_MINIMUM_FOCUS_DISTANCE is in diopters (1/meters); 0 = infinity.
        val minFocusDistance = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        if (minFocusDistance <= 0f) {
            // Fixed-focus camera: manual mode is meaningless, quietly fall back
            disableManualFocus()
            return
        }

        binding.mfChip.visibility = View.VISIBLE
        minFocusDiopters = minFocusDistance
        applyFocusDistance(focusProgress)
    }

    /**
     * Reads the sensor's own limits and builds the stops the manual controls
     * offer from them.
     *
     * **No clamp.** The previous version narrowed the shutter to a hardcoded
     * 1/8000..1/15 "video-sensible" window. On the S23 Ultra the sensor
     * reports 1/18570..0.15s, so a third of the real range was unreachable,
     * and on a phone whose HAL reports whole seconds it would have hidden all
     * of them. Whatever SENSOR_INFO_EXPOSURE_TIME_RANGE and
     * SENSOR_INFO_SENSITIVITY_RANGE say is what the user gets, ISO 50 and
     * 30-second exposures included where the hardware has them.
     *
     * Called on every bind, and the stops change with the camera (the
     * ultra-wide and the tele report different floors), so the saved values
     * are re-snapped to the new list rather than the raw index being reused —
     * index 12 means a different ISO on a different lens.
     */
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
            )?.let { isoStops = ExposureScale.isoStops(it.lower, it.upper) }
            info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )?.let {
                // The fast end is the sensor's; the slow end is the slower of
                // the sensor's and 1/24 — see ExposureScale.slowestForVideo.
                // Clamped at the call site rather than inside shutterStopsNs
                // so the policy is visible where the hardware range is read,
                // and so the table itself stays a plain description of what a
                // shutter dial offers.
                shutterStopsNs =
                    ExposureScale.shutterStopsNs(it.lower, ExposureScale.slowestForVideo(it.upper))
            }
        }

        val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE)
        aeEnabled = prefs.getBoolean("auto_exposure", true)
        // Restore by VALUE, not by slider position: the last ISO the user
        // chose is a number, and it should come back as that number (or the
        // nearest this camera can do) rather than as whatever "60% along the
        // rail" happens to mean here. Defaults land on 1/60 and ISO 400, a
        // usable video starting point instead of the two arbitrary slider
        // positions the layout used to hardcode.
        val savedIso = prefs.getInt("manual_iso", 400).toLong()
        val savedShutter = prefs.getLong("manual_shutter_ns", 16_666_666L)
        isoIndex = ExposureScale.nearestIndex(isoStops.map { it.toLong() }, savedIso)
        shutterIndex = ExposureScale.nearestIndex(shutterStopsNs, savedShutter)
        evIndex = cam.cameraInfo.exposureState.exposureCompensationIndex

        Log.i(
            TAG,
            "exposure: manual=$manualSensorSupported iso=${isoStops.first()}..${isoStops.last()} " +
                "(${isoStops.size} stops) shutter=${ExposureScale.formatShutter(shutterStopsNs.first())}.." +
                "${ExposureScale.formatShutter(shutterStopsNs.last())} (${shutterStopsNs.size} stops) " +
                "-> iso=${currentIso()} ss=${ExposureScale.formatShutter(currentShutterNs())}",
        )

        // A rebind can arrive with a control open whose stop list just changed
        // under it; reopening re-reads max/progress from the new list.
        openControl?.let { openPanel(it) }
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
        } else if (captureSourceIsScreen() && mediaProjection == null) {
            // Notification permission first, projection consent second: the
            // notification is the only way to stop a cast from outside the
            // app, and asking for it after the projection dialog would put a
            // second prompt on top of a session that already started. One
            // prompt at a time, in the order the session needs them.
            if (needsNotificationPermission()) {
                pendingScreenStart = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        } else {
            startStreaming()
        }
    }

    // ─────────────── capture source: camera vs screen ───────────────

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun captureSourceIsScreen(): Boolean =
        getSharedPreferences("stream_settings", MODE_PRIVATE).getInt("capture_source", 0) == 1

    private fun updateSourceIcon() {
        binding.sourceButton.setImageResource(
            if (captureSourceIsScreen()) R.drawable.ic_source_screen else R.drawable.ic_source_camera,
        )
    }

    private fun showSourceMenu() {
        val popup = PopupMenu(this, binding.sourceButton)
        popup.menu.add(0, 0, 0, getString(R.string.source_camera))
        popup.menu.add(0, 1, 1, getString(R.string.source_screen))
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.getItem(if (captureSourceIsScreen()) 1 else 0).isChecked = true
        popup.setOnMenuItemClickListener { item ->
            onSourceSelected(screen = item.itemId == 1)
            true
        }
        popup.show()
    }

    private fun onSourceSelected(screen: Boolean) {
        val wasScreen = captureSourceIsScreen()
        getSharedPreferences("stream_settings", MODE_PRIVATE).edit()
            .putInt("capture_source", if (screen) 1 else 0).apply()
        updateSourceIcon()
        if (screen) {
            // The honest numbers for this mode, up front: what limits the
            // stream here is the panel — its resolution and refresh rate —
            // not the camera sensor.
            val dm = realDisplayMetrics()
            AppToast.info(
                this,
                getString(R.string.screen_mode_limits, dm.widthPixels, dm.heightPixels, displayRefreshHz()),
            )
        }
        if (wasScreen == screen) return
        if (isStreaming) {
            // Same treatment as a Settings geometry change: restart the
            // session into the newly chosen source.
            stopStreaming()
            onToggleStreamClicked()
        }
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    @Suppress("DEPRECATION")
    private fun displayRefreshHz(): Int = windowManager.defaultDisplay.refreshRate.roundToInt()

    /**
     * Consent granted: bring up the foreground service the projection APIs
     * require, and only once it reports startForeground has run (the moment
     * the mediaProjection type check passes) create the projection and start
     * the session. Doing it in that order is not style — Android 14 throws
     * on any other sequence.
     */
    private fun startScreenServiceThenStream(resultCode: Int, data: Intent) {
        ScreenCaptureService.onReady = {
            uiHandler.post {
                try {
                    val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        ?: throw IllegalStateException("system returned no MediaProjection")
                    projection.registerCallback(projectionCallback, uiHandler)
                    mediaProjection = projection
                    startStreaming()
                } catch (e: Exception) {
                    Log.e(TAG, "getMediaProjection failed", e)
                    AppToast.error(this, getString(R.string.screen_capture_denied))
                    ScreenCaptureService.stop(this)
                }
            }
        }
        ScreenCaptureService.start(this)
    }

    /**
     * Screen-capture backend: the mirrored display replaces the camera as the
     * producer into the streamer's input surface — everything downstream
     * (watermark, tier gating, encoder, socket, OBS sync) is the same code the
     * camera paths run. The camera itself is released for the session: the
     * sensor has no business being on while the screen is what streams.
     */
    private fun startScreenBackend(projection: MediaProjection, activeStreamer: CameraStreamer) {
        screenSessionActive = true
        // The notification's Stop action, wired to the same teardown the REC
        // button uses — the only control that exists while the user is in
        // another app, which is where screen casting puts them.
        ScreenCaptureService.onStopRequested = {
            uiHandler.post { if (isStreaming) stopStreaming() }
        }
        stopCamera2Backend()
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll before screen capture failed", e)
        }
        camera = null

        val dm = realDisplayMetrics()
        val refreshHz = displayRefreshHz()
        // The panel is this session's true fps ceiling, exactly as the
        // sensor's negotiated range is for the camera backends.
        activeStreamer.cameraFpsCeiling = refreshHz
        activeStreamer.setRotationDegrees(0) // screen content is already upright

        activeStreamer.attachScreenSource(
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            onSurfaceReady = { surface, encWidth, encHeight ->
                uiHandler.post {
                    if (!isStreaming || !screenSessionActive) return@post
                    try {
                        virtualDisplay = projection.createVirtualDisplay(
                            "FrameCast",
                            encWidth, encHeight, dm.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            surface, null, null,
                        )
                        screenSessionLabel = "${encWidth}x$encHeight"
                        binding.screenModeLabel.visibility = View.VISIBLE
                        binding.previewView.visibility = View.INVISIBLE
                        binding.camera2PreviewView.visibility = View.GONE
                        AppToast.info(
                            this,
                            getString(
                                R.string.screen_mode_limits,
                                dm.widthPixels, dm.heightPixels, refreshHz,
                            ),
                        )
                        Log.i(
                            TAG,
                            "screen session: display=${dm.widthPixels}x${dm.heightPixels}@${refreshHz}Hz " +
                                "encoder=${encWidth}x$encHeight",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createVirtualDisplay failed", e)
                        AppToast.error(this, getString(R.string.screen_capture_denied))
                        stopStreaming()
                    }
                }
            },
            onFailed = {
                uiHandler.post {
                    Log.e(TAG, "screen session could not get an encoder surface")
                    stopStreaming()
                }
            },
        )
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
        // Screen mode without a live projection cannot start a session —
        // onToggleStreamClicked routes through the consent dialog first, so
        // landing here without one means the projection died in between.
        if (captureSourceIsScreen() && mediaProjection == null) {
            AppToast.warning(this, getString(R.string.screen_capture_denied))
            return
        }
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

        // Asked for here, not only by the level-meter toggle: audio is really
        // streamed now, so a user with "Record audio" on but the permission
        // never granted would otherwise get a silent stream with nothing
        // saying why. Fire-and-forget on purpose — the session has to start
        // immediately either way, and CameraStreamer already treats a refused
        // microphone as a video-only session rather than a failure, so the
        // grant simply takes effect on the next stream.
        if (cfg.audioEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val newStreamer = CameraStreamer(
            hostResolver = { resolveStreamHost() },
            port = 8787,
            rewardManager = rewardManager,
            streamConfig = cfg,
        )
        streamer = newStreamer
        // What this session's encoder and Hello were built from. onResume
        // compares the freshly-saved Settings against it to know whether a
        // rebind is enough or the session itself is stale — see
        // StreamConfig.requiresSessionRestart.
        streamingConfig = cfg

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

        val projection = if (captureSourceIsScreen()) mediaProjection else null
        if (projection != null) {
            newStreamer.metrics.backend = "screen"
            startScreenBackend(projection, newStreamer)
        } else if (shouldUseCamera2(cfg)) {
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
            val token = getSharedPreferences("stream_settings", MODE_PRIVATE)
                .getString("pc_token", "").orEmpty()
            PcControl.startServices(host, token)
        }
    }

    /**
     * Releases everything a screen session owns, and returns whether there was
     * one. Safe to call twice, and safe to call while the activity is being
     * destroyed — which is precisely why it is separate from [stopStreaming]:
     * onDestroy must free the projection without rebinding a camera.
     *
     * Leaving this out of onDestroy was a real defect: an activity destroyed
     * in the background (which screen casting invites, since the point is to
     * be in another app) left the projection and its foreground service alive
     * with no owner. The system kept sharing the screen, and reopening the
     * app showed an idle-looking UI that could not stop it.
     */
    private fun releaseScreenSession(): Boolean {
        // Flag first: projection.stop() fires projectionCallback.onStop, whose
        // re-entry guard is this flag.
        val wasScreen = screenSessionActive
        screenSessionActive = false
        screenSessionLabel = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.let { projection ->
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        }
        mediaProjection = null
        ScreenCaptureService.onStopRequested = null
        if (wasScreen) ScreenCaptureService.stop(this)
        return wasScreen
    }

    private fun stopStreaming() {
        if (releaseScreenSession()) {
            binding.screenModeLabel.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
        }

        stopCamera2Backend()
        streamer?.stop()
        streamer = null
        isStreaming = false
        streamingConfig = null
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
        val (width, height) = StreamConfig.outputSizeFor(cfg.qualityLabel, cfg.aspectRatio)
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
        // Composition-aware, and on this backend that is the only thing that
        // applies it at all: the Camera2 path has no CameraX ViewPort, so the
        // capture size *is* the streamed frame's shape.
        val (width, height) = StreamConfig.outputSizeFor(cfg.qualityLabel, cfg.aspectRatio)
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
                // The REAL hold, not an upright assumption. This used to be a
                // hardcoded ROTATION_0, and the combination with the
                // debouncer's dedup made it permanent: the idle preview had
                // already saturated the debouncer with the current bucket, so
                // with the phone mounted any way but upright the correction
                // reading was swallowed forever and the 1x streamed a quarter
                // turn off for the whole session (pinned in
                // Camera2RotationStateForensicsTest). Capture the known hold,
                // then reset so the next stable reading re-emits regardless —
                // the same self-heal the CameraX branch has always had via
                // startCamera()'s rebind.
                //
                // Order: attach FIRST (it records the texture-carried rotation
                // on the streamer), THEN push the hold. applyCamera2Rotation
                // logs the geom line, and firing it before the attach printed
                // texRot=0 — a lie that cost real audit time.
                val knownBucket = rotationDebouncer.lastAppliedBucket() ?: Surface.ROTATION_0
                rotationDebouncer.reset()

                activeStreamer.attachCamera2Surface(
                    cameraWidth = captureSize.width,
                    cameraHeight = captureSize.height,
                    // The HAL folds the sensor mounting into the buffers'
                    // texture matrix on this path (measured: content upright
                    // at a vertical hold with zero vertex rotation), so the
                    // renderer must subtract it — see ContentGeometry.
                    textureRotationDegrees = source.sensorOrientation,
                    onSurfaceReady = { encoderSurface -> source.start(encoderSurface, previewSurface) },
                    onFailed = { runOnUiThread { fallbackToCameraX("encoder unavailable") } },
                )
                applyCamera2Rotation(knownBucket)
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
            // F12: a TextureView stretches its buffer to its bounds, and this
            // one is pinned to the full portrait screen — a 16:9 preview
            // stream was forced fullscreen-tall the moment recording started
            // (screenshot-documented on device). A centered aspect-preserving
            // fit keeps the viewfinder honest; posted so the view has been
            // laid out (it was GONE until a moment ago).
            view.post { applyCamera2PreviewTransform(view, previewSize) }
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

            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
                applyCamera2PreviewTransform(view, previewSize)
            }
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
     *
     * The reference is the RAW physical bucket. A previous fix routed this
     * through a quarter-turn shift so both backends would agree — and they
     * did, but on a reference that was itself 90° off: Round 3's on-device
     * geometry lines measured θ=0 applied where the content needed 90
     * (vertical) and θ=270 where it needed 0 (horizontal), i.e. a constant
     * −90° on every hold. The parity machinery stays; only the shared
     * reference changes to the one the hardware validated.
     */
    @android.annotation.SuppressLint("NewApi") // camera2Source is only ever non-null on API 28+ (isSupported gates)
    private fun applyCamera2Rotation(surfaceRotation: Int) {
        if (camera2AppliedRotation == surfaceRotation) return
        val source = camera2Source ?: return
        streamer?.setRotationDegrees(
            RotationPolicy.sensorRotationDegrees(source.sensorOrientation, surfaceRotation),
        )
        camera2AppliedRotation = surfaceRotation
    }

    /**
     * Centered aspect-preserving fit for the Camera2 viewfinder (F12): scales
     * the TextureView's default stretch-to-bounds back down so the preview
     * stream keeps its shape, letterboxed like the CameraX viewfinder.
     */
    private fun applyCamera2PreviewTransform(view: TextureView, bufferSize: Size) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || bufferSize.width <= 0 || bufferSize.height <= 0) return
        val scale = minOf(vw / bufferSize.width, vh / bufferSize.height)
        val matrix = android.graphics.Matrix().apply {
            setScale(
                bufferSize.width * scale / vw,
                bufferSize.height * scale / vh,
                vw / 2f,
                vh / 2f,
            )
        }
        view.setTransform(matrix)
    }

    @android.annotation.SuppressLint("NewApi") // camera2Source is only ever non-null on API 28+ (isSupported gates)
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

            // Composition-aware: the ViewPort below crops the capture to this
            // ratio, so asking the camera for a differently-shaped target just
            // means throwing away pixels it was asked to produce. See
            // StreamConfig.outputSizeFor.
            val (targetWidth, targetHeight) =
                StreamConfig.outputSizeFor(cfg.qualityLabel, cfg.aspectRatio)

            // The preview genuinely captures at the chosen resolution (down to
            // 360p etc, real pixels in, not a post-processing effect) — only
            // capped downward for very high targets, since pushing a raw 4K/8K
            // surface into PreviewView is what caused the ~0.5s viewfinder lag.
            //
            // Two things this has to get right, and both were measured wrong:
            //
            //  - The cap is a PIXEL BUDGET, not a 1920x1080 box. The box was
            //    the same defect fitPixelBudget already removed from the
            //    reward-tier ceiling: it charged every vertical composition
            //    44% of its linear size for nothing, turning a 1080x1920
            //    request into 608x1080.
            //  - The bound is expressed in SENSOR orientation. CameraX never
            //    rotates it — see StreamConfig.sensorOriented for the measured
            //    consequence (a 9:16 viewfinder bound at 320x240).
            //
            // Together those two produced the "vertical looks pixelated, but
            // fake-pixelated" report: 320x240 stretched across a 1440x2560
            // view. Neither ever touched the streamed frames, which is why the
            // PC side looked fine while the phone did not.
            val (budgetWidth, budgetHeight) =
                StreamConfig.fitPixelBudget(targetWidth, targetHeight, 1920L * 1080)
            val (previewWidth, previewHeight) =
                StreamConfig.sensorOriented(budgetWidth, budgetHeight)
            val previewSelector = ResolutionSelector.Builder()
                // Without this the search runs in CameraX's default 4:3 group
                // (RATIO_4_3_FALLBACK_AUTO_STRATEGY), so even a correct
                // 1920x1080 bound comes back as 1440x1080 and the ViewPort
                // then crops a 16:9 composition out of it at 1440x810. Asking
                // in the composition's own shape is the same principle the
                // capture side already follows: don't make the camera produce
                // pixels the crop is going to throw away.
                .setAspectRatioStrategy(aspectRatioStrategyFor(previewWidth, previewHeight))
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(previewWidth, previewHeight),
                        // Kept deliberately: "closest lower" is what makes the
                        // viewfinder honest about a 480p or 360p preset instead
                        // of showing a sharper image than the PC receives.
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
            // Sensor-oriented for the same reason as the preview. In practice
            // this selector is not consulted at all — VideoCapture's
            // OPTION_CUSTOM_ORDERED_RESOLUTIONS, built from StreamingVideoOutput's
            // MediaSpec, outranks it and getSortedSupportedOutputSizes returns
            // that list before ever reading a ResolutionSelector — but the
            // portrait bound sat here as a loaded gun aimed at the streamed
            // frames for the day that list comes back empty, which is exactly
            // the failure getMediaCapabilities was added to prevent.
            val (videoBoundWidth, videoBoundHeight) =
                StreamConfig.sensorOriented(targetWidth, targetHeight)
            val videoSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(videoBoundWidth, videoBoundHeight),
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

            // The physical hold this bind starts from — read BEFORE the
            // debouncer reset below, used by both targetRotation and the
            // ViewPort so rotation and crop share one reference. Null (fresh
            // launch, phone flat) falls back to upright.
            val knownBucket = rotationDebouncer.lastAppliedBucket() ?: Surface.ROTATION_0

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
                // ResolutionSelector below. The selection itself is unchanged
                // (short edge of the need, see shortEdgeOrderIndices); what
                // the composition×hold calculation buys is the diagnostic
                // line below, which is how M2's premise was measured and
                // refuted on device. Pair it with the geom line's buffer= and
                // encoder= to see, per session, whether this phone's CameraX
                // under-provisions the capture. On the S23 Ultra it does not.
                val (needW, needH) = StreamConfig.neededCaptureFor(targetWidth, targetHeight, knownBucket)
                mainVideoOutput.neededWidth = needW
                mainVideoOutput.neededHeight = needH
                Log.i(TAG, "capture need: ${needW}x$needH (encoder=${targetWidth}x$targetHeight hold=$knownBucket)")
                // WrongConstant: knownBucket IS a Surface.ROTATION_* value — it
                // comes from the rotation debouncer's bucket — lint just can't
                // see through the Int.
                @android.annotation.SuppressLint("WrongConstant")
                val videoCaptureBuilder = VideoCapture.Builder(mainVideoOutput)
                    .setResolutionSelector(videoSelector)
                    // The RAW physical hold, not the display rotation (locked
                    // to portrait, so always ROTATION_0) and not the old
                    // quarter-shifted value: Round 3 measured the shift as a
                    // constant −90° on every hold, on both backends. The
                    // debouncer's last stable bucket is the best estimate of
                    // how the phone is held at bind time; the listener keeps
                    // it updated live from here on.
                    .setTargetRotation(knownBucket)
                    .setTargetFrameRate(android.util.Range(cfg.fps, cfg.fps))
                applyPhysicalCameraId(videoCaptureBuilder, physicalCameraId, fpsRange)
                videoCaptureBuilder.build()
            } else {
                null
            }
            currentVideoCapture = videoCapture
            // Reset AFTER the bucket was captured above: the next stable
            // reading re-emits the current hold even if it matches, so the
            // listener re-applies it to the fresh use case — same self-heal
            // the Camera2 start sequence uses.
            rotationDebouncer.reset()

            // ViewPort crops every bound use case (preview AND the capture stream that
            // feeds the network encoder) to the same rectangle, so "Composition" actually
            // changes what's sent to the PC — not just a cosmetic letterbox over an
            // uncropped 16:9 sensor feed.
            val (ratioNum, ratioDenom) = StreamConfig.aspectRatioParts(cfg.aspectRatio)
            // Natural form: the ratio as the user picked it, expressed in the
            // frame of the CURRENT hold — the same reference targetRotation
            // uses now. CameraX maps it into sensor space itself. The previous
            // code inverted the rational to compensate for the quarter-shifted
            // reference; with the shift gone (measured wrong on hardware), the
            // inversion goes with it. Held landscape, 16:9 crops the full
            // sensor; held vertical, 16:9 is the upright wide slice — exactly
            // what the letterboxed viewfinder shows.
            // WrongConstant: knownBucket is a Surface.ROTATION_* bucket, same
            // as setTargetRotation above.
            @android.annotation.SuppressLint("WrongConstant")
            val viewPort = ViewPort.Builder(
                android.util.Rational(ratioNum, ratioDenom),
                knownBucket,
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
                // What the bound camera will actually zoom to, as opposed to
                // what its characteristics advertise. A minZoomRatio below 1
                // means the wider lens is reachable through this camera by
                // zoom alone — no second id to open, no hidden id to guess.
                camera?.cameraInfo?.zoomState?.value?.let { z ->
                    Log.i(TAG, "zoomState: min=${z.minZoomRatio} max=${z.maxZoomRatio} current=${z.zoomRatio}")
                }
                // The viewfinder's own geometry line, and the one number that
                // had no log at all while the vertical viewfinder was running
                // at 320x240: what the selector ASKED for versus what CameraX
                // actually bound. `dumpsys media.camera` was the only way to
                // see it, and only while the session was live.
                Log.i(
                    TAG,
                    "preview: asked=${previewWidth}x$previewHeight " +
                        "bound=${preview.resolutionInfo?.resolution} view=${cfg.aspectRatio}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed for ${cfg.qualityLabel}", e)
                AppToast.warning(this, "${cfg.qualityLabel} not supported by this camera")
                cameraProvider.unbindAll()
                if (isStreaming) {
                    // The old fallback re-bound buildUseCaseGroup(...), whose
                    // closure still carried the exact VideoCapture that just
                    // failed — so when the video use case WAS the problem, the
                    // "fallback" threw the same exception again, this time
                    // outside any catch, and the alternative outcome was a
                    // session left half-alive: record button lit, viewfinder
                    // fine, nothing streaming. If a stream is up, fail it
                    // honestly: stopStreaming() resets the UI and rebinds
                    // preview-only through the normal path (its startCamera
                    // runs with isStreaming=false, so no video use case — the
                    // recursion terminates by construction).
                    stopStreaming()
                } else {
                    // Idle: a bare preview with none of the parts that can
                    // have caused the failure — no video use case, no
                    // ViewPort, no Camera2Interop options.
                    val fallbackPreview = Preview.Builder().build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
                    camera = cameraProvider.bindToLifecycle(cameraLifecycleOwner, selector, fallbackPreview)
                }
            }

            setupCameraDependentControls(cfg)
            if (torchOn) camera?.cameraControl?.enableTorch(true)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Which of CameraX's two aspect-ratio groups a sensor-oriented
     * [width]x[height] belongs in.
     *
     * The candidate list is grouped by aspect ratio *before* the resolution
     * strategy runs, and the group matching the preferred ratio is searched
     * first — so the preferred ratio, not the bound size, decides which sizes
     * are even considered. RATIO_16_9 and RATIO_4_3 are the only two values
     * the API takes; every composition this app offers is nearer one or the
     * other (16:9 and 9:16 land on 16:9 once sensor-oriented; 4:3, 3:4 and 1:1
     * on 4:3). FALLBACK_RULE_AUTO keeps the other group available underneath,
     * so a camera that offers only one of the two still binds.
     */
    private fun aspectRatioStrategyFor(width: Int, height: Int): AspectRatioStrategy {
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        val nearerWide = kotlin.math.abs(ratio - 16f / 9) < kotlin.math.abs(ratio - 4f / 3)
        return if (nearerWide) {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
    }

    /**
     * Video stabilization and white balance were previously Settings toggles with no
     * effect at all — saved to prefs, never read by the camera pipeline. Camera2Interop
     * lets CameraX carry raw CaptureRequest options through to the capture session.
     */
    // NewApi: setPhysicalCameraId is API 28, but physicalCameraId is only ever
    // non-null on API 28+ — every discovery path (physicalIdFor, the inventory)
    // returns null/empty below P.
    @android.annotation.SuppressLint("NewApi")
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
    // NewApi: same argument as applyCamera2Options — null below API 28.
    @android.annotation.SuppressLint("NewApi")
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

    @OptIn(ExperimentalCamera2Interop::class) // Camera2CameraInfo.from in the camera filter
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
        // Re-apply settings in case the user changed something in Settings.
        // If the change touched session geometry while streaming, a bare
        // rebind is exactly the bug: the camera starts cropping to the new
        // composition while the encoder (and the Hello the receiver sized
        // everything from) keeps the shape this session started with — the
        // stream deforms and only OBS shows it, because the viewfinder
        // corrects itself. The session restart is announced by the same
        // UI the record button uses, and costs the same reconnect it would
        // have cost to stop and start by hand — which was the only correct
        // manual workaround anyway.
        if (camera != null) {
            val sessionCfg = streamingConfig
            if (isStreaming && sessionCfg != null &&
                StreamConfig.requiresSessionRestart(sessionCfg, StreamConfig.load(this))
            ) {
                Log.i(TAG, "session geometry changed in Settings — restarting the stream")
                stopStreaming()
                startStreaming()
            } else {
                startCamera()
            }
        }
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

    // Last time the frozen-capture watchdog restarted the session, so a block
    // the system re-applies immediately can't put the app in a restart loop.
    private var lastWatchdogRestartMs = 0L

    private fun startUiTicker() {
        uiHandler.post(object : Runnable {
            override fun run() {
                refreshStatusUi()
                watchFrozenCapture()
                uiHandler.postDelayed(this, 1000)
            }
        })
    }

    /**
     * Auto-recovery for an externally killed capture. Observed on-device
     * (hardware validation, 00:40:46): Samsung's CameraService issued
     * "block for PID <app>" against a healthy 30fps session — frames stopped
     * cold for 80 seconds, no in-process callback fired, and the stream sat
     * frozen until a manual restart. The camera churn that provoked it is
     * gone (camera/lens changes restart the session now), but the block is
     * the system's call and can recur; when it does, this turns "the user
     * must notice and restart by hand" into one automatic restart, capped to
     * once a minute so a persistent block degrades to a log instead of a
     * restart loop.
     */
    private fun watchFrozenCapture() {
        if (!isStreaming) return
        val age = streamer?.millisSinceLastFrame() ?: return
        if (age < 8_000) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWatchdogRestartMs < 60_000) return
        lastWatchdogRestartMs = now
        Log.w(TAG, "capture frozen for ${age}ms — restarting the session (watchdog)")
        AppToast.warning(this, getString(R.string.toast_capture_frozen_restart))
        stopStreaming()
        // Through the toggle, not startStreaming() directly: a screen session
        // lost its projection in stopStreaming() and needs the consent flow
        // again; camera sessions take the direct path as before.
        onToggleStreamClicked()
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
        // Screen sessions ignore the camera Settings entirely; what the
        // encoder was really built at is the only honest label.
        screenSessionLabel?.let { return it }
        // Same rule for the camera: what is actually being encoded and sent,
        // not what was asked for. The two differ whenever a backend cannot
        // honour the request — 60fps on CameraX here really is 30, and an 8K
        // pick is delivered as 4K because that is all the PC's virtual camera
        // can carry (see FrameEncoder's tier ceiling).
        streamer?.let { active ->
            if (active.streamedWidth > 0 && active.streamedFps > 0) {
                return "${active.streamedWidth}x${active.streamedHeight} ${active.streamedFps}fps"
            }
        }
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
        // Before the streamer: the projection feeds the encoder, so it is the
        // producer that has to go first. See releaseScreenSession.
        releaseScreenSession()
        streamer?.stop()
        stopAudioMeter()
        cameraExecutor.shutdown()
        pcConnectExecutor.shutdown()
    }
}
