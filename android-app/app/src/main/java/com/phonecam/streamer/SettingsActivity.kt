package com.phonecam.streamer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.File
import org.json.JSONObject
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.phonecam.streamer.databinding.ActivitySettingsBinding
import com.phonecam.streamer.device.DeviceCapabilities
import com.phonecam.streamer.network.PcControl
import com.phonecam.streamer.network.PcDiscovery
import com.phonecam.streamer.rewards.RewardManager
import com.phonecam.streamer.speedtest.SpeedTestManager
import com.phonecam.streamer.speedtest.SpeedTestPhase
import com.phonecam.streamer.ui.ExpandableChoiceRow
import com.phonecam.streamer.ui.AppToast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val testExecutor = Executors.newSingleThreadExecutor()
    private var copyrightImageUri: Uri? = null
    private var deviceInfo: DeviceCapabilities.DeviceInfo? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            // The photo picker's content:// URI is only valid for this process — it won't
            // survive an app restart. Copy the bytes into our own storage so Settings can
            // reload the image later without crashing on a revoked permission.
            testExecutor.execute {
                val localUri = copyImageToPrivateStorage(picked)
                runOnUiThread {
                    if (localUri != null) {
                        copyrightImageUri = localUri
                        binding.imgCopyrightPreview.setImageURI(localUri)
                        binding.imgCopyrightPreview.visibility = View.VISIBLE
                    } else {
                        AppToast.error(this, getString(R.string.toast_image_load_failed))
                    }
                }
            }
        }
    }

    private fun copyImageToPrivateStorage(source: Uri): Uri? {
        return try {
            val file = File(filesDir, "copyright_overlay.jpg")
            contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "failed to copy copyright image", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_down)
        }

        binding.upgradeButton.setOnClickListener {
            AppToast.info(this, getString(R.string.sub_coming_soon))
        }
        // AppPhase.MONETIZATION_ENABLED is false during Alpha/Beta — no purchase
        // to offer, so the price+button are hidden in favor of a note that Pro
        // unlocks free via ads. Flip the flag and both sides swap back on their
        // own, no other changes needed (see AppPhase.kt's doc).
        binding.subPrice.visibility = if (AppPhase.MONETIZATION_ENABLED) View.VISIBLE else View.GONE
        binding.upgradeButton.visibility = if (AppPhase.MONETIZATION_ENABLED) View.VISIBLE else View.GONE
        binding.proAdsNotice.visibility = if (AppPhase.MONETIZATION_ENABLED) View.GONE else View.VISIBLE
        updateSubBadge()

        binding.discoverButton.setOnClickListener { discoverPc() }

        binding.btnStartPc.setOnClickListener { startPcServices() }
        binding.btnStopPc.setOnClickListener { stopPcServices() }
        binding.btnRefreshPcStatus.setOnClickListener { checkPcStatus() }
        binding.btnAdbReverse.setOnClickListener { setupAdbReverse() }
        binding.btnSpeedTest.setOnClickListener { runSpeedTest() }

        setupSpinners()
        loadPreferences()
        populateDeviceInfo()
        updateProTimeRemaining()
        animateEntrance()
        checkPcStatus()
    }

    private fun updateProTimeRemaining() {
        val raw = getSharedPreferences("reward_state", MODE_PRIVATE).getString("state_json", null)
        val manager = raw?.let {
            try {
                RewardManager.fromJson(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
        val seconds = manager?.balanceSeconds() ?: 0.0
        binding.proTimeRemainingText.text = if (seconds > 0) {
            val h = (seconds / 3600).toInt()
            val m = ((seconds % 3600) / 60).toInt()
            if (h > 0) {
                getString(R.string.pro_time_remaining_hm, h, m)
            } else {
                getString(R.string.pro_time_remaining_m, m)
            }
        } else {
            val config = (manager ?: RewardManager()).config
            val hours = (config.secondsPerReward / 3600.0).roundToInt()
            getString(R.string.pro_time_none, config.adsPerReward, hours)
        }
    }

    /** The "FREE"/"PRO" pill next to the PhoneCam Pro title — was always the
     * layout's static default ("FREE") no matter the user's actual ad-earned
     * status, since nothing ever wrote to it after inflation. */
    private fun updateSubBadge() {
        binding.subBadge.text = if (isPro) getString(R.string.sub_pro) else getString(R.string.sub_free)
        binding.subBadge.setBackgroundResource(if (isPro) R.drawable.badge_pro else R.drawable.badge_free)
    }

    private val isPro: Boolean
        get() {
            val subscribed = getSharedPreferences("stream_settings", MODE_PRIVATE).getBoolean("is_pro", false)
            if (subscribed) return true
            // Ad-earned Pro time lives in RewardManager's own prefs, not stream_settings —
            // this used to only check the (never-set) subscription flag above, so watching
            // a rewarded ad never actually unlocked the Pro-gated options here.
            val raw = getSharedPreferences("reward_state", MODE_PRIVATE).getString("state_json", null)
                ?: return false
            return try {
                RewardManager.fromJson(JSONObject(raw)).currentProfile().premiumActive
            } catch (e: Exception) {
                false
            }
        }

    private val proResolutions = setOf(4, 5, 6)
    private val proBitrates = setOf(4)
    private val proFps = setOf(3)

    private fun setupSpinners() {
        val custom = getString(R.string.option_custom)
        setupSpinner(
            binding.spinnerResolution,
            listOf("360p", "480p", "720p", "1080p", "1440p ⟐ Pro", "2160p 4K ⟐ Pro", "4320p 8K ⟐ Pro", custom),
        )
        setupSpinner(binding.spinnerAspectRatio, listOf("4:3", "16:9", "1:1", "9:16", "3:4"))
        setupSpinner(binding.spinnerVideoCodec, listOf("H.264", "H.265 (HEVC)", "AV1"))
        setupSpinner(binding.spinnerVideoBitrate, listOf("10 Mbps", "20 Mbps", "35 Mbps", "50 Mbps", "100 Mbps ⟐ Pro", custom))
        setupSpinner(binding.spinnerFps, listOf("24 fps", "30 fps", "60 fps", "120 fps ⟐ Pro", custom))
        setupSpinner(
            binding.spinnerAutofocus,
            listOf(getString(R.string.af_continuous), getString(R.string.af_tap_to_focus), getString(R.string.af_manual)),
        )
        setupSpinner(
            binding.spinnerAfSpeed,
            listOf(
                getString(R.string.af_speed_standard), getString(R.string.af_speed_cinematic),
                getString(R.string.af_speed_action), getString(R.string.af_speed_macro),
            ),
        )
        setupSpinner(binding.spinnerSampleRate, listOf("44.1 kHz", "48 kHz", "96 kHz"))
        setupSpinner(binding.spinnerAudioBitrate, listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps"))
        setupSpinner(binding.spinnerAudioCodec, listOf("AAC", "OPUS", "FLAC"))
        setupSpinner(
            binding.spinnerWhiteBalance,
            listOf(
                getString(R.string.wb_auto), getString(R.string.wb_daylight), getString(R.string.wb_cloudy),
                getString(R.string.wb_tungsten), getString(R.string.wb_fluorescent),
            ),
        )
        setupSpinner(
            binding.spinnerLens,
            listOf(getString(R.string.lens_wide), getString(R.string.lens_ultrawide), getString(R.string.lens_telephoto)),
        )
        setupSpinner(binding.spinnerConnectionMode, listOf("WiFi", "USB (adb)", getString(R.string.wb_auto)))
        setupSpinner(binding.spinnerProtocol, listOf("TCP", "UDP", "WebRTC"))
        setupSpinner(
            binding.spinnerWatermarkPos,
            listOf(
                getString(R.string.watermark_bottom_left), getString(R.string.watermark_bottom_right),
                getString(R.string.watermark_top_left), getString(R.string.watermark_top_right),
            ),
        )
        setupSpinner(binding.spinnerCopyrightMode, listOf(getString(R.string.settings_copyright_text), getString(R.string.settings_copyright_image)))
        setupSpinner(
            binding.spinnerStreamBrightness,
            listOf(getString(R.string.brightness_normal), getString(R.string.brightness_full), getString(R.string.brightness_dimmed)),
        )

        guardProSpinner(binding.spinnerResolution, proResolutions, 3)
        guardProSpinner(binding.spinnerVideoBitrate, proBitrates, 3)
        guardProSpinner(binding.spinnerFps, proFps, 2)

        setupCustomExpand(binding.spinnerResolution, binding.rowCustomResolution, 7)
        setupCustomExpand(binding.spinnerVideoBitrate, binding.rowCustomBitrate, 5)
        setupCustomExpand(binding.spinnerFps, binding.rowCustomFps, 4)

        addCompatibilityCheck(binding.spinnerResolution)
        addCompatibilityCheck(binding.spinnerFps)

        binding.spinnerCopyrightMode.onChoiceSelectedListener =
            ExpandableChoiceRow.OnChoiceSelectedListener { _, pos ->
                val isText = pos == 0
                binding.rowCopyrightText.visibility = if (isText) View.VISIBLE else View.GONE
                binding.sepCopyrightText.visibility = if (isText) View.VISIBLE else View.GONE
                binding.rowCopyrightImage.visibility = if (isText) View.GONE else View.VISIBLE
                binding.sepCopyrightImage.visibility = if (isText) View.GONE else View.VISIBLE
            }

        binding.btnPickCopyrightImage.setOnClickListener { pickImage.launch("image/*") }
    }

    private fun guardProSpinner(row: ExpandableChoiceRow, proIndices: Set<Int>, fallback: Int) {
        row.onChoiceSelectedListener = ExpandableChoiceRow.OnChoiceSelectedListener { _, pos ->
            if (pos in proIndices && !isPro) {
                row.setSelection(fallback)
                // AppPhase.MONETIZATION_ENABLED is false during Alpha/Beta (see its
                // doc) — no purchase to offer, so this is informational only, no
                // button. Once monetization is live this branches to an upgrade
                // button+action instead, same as the rest of the Pro UI.
                if (AppPhase.MONETIZATION_ENABLED) {
                    AppToast.show(this, getString(R.string.pro_feature_locked_desc)) {
                        AppToast.info(this, getString(R.string.sub_coming_soon))
                    }
                } else {
                    AppToast.show(this, getString(R.string.pro_feature_locked_desc))
                }
            }
        }
    }

    private fun addCompatibilityCheck(row: ExpandableChoiceRow) {
        val existing = row.onChoiceSelectedListener
        row.onChoiceSelectedListener = ExpandableChoiceRow.OnChoiceSelectedListener { r, pos ->
            existing?.onChoiceSelected(r, pos)
            updateCompatibilityWarnings()
        }
    }

    private fun setupCustomExpand(row: ExpandableChoiceRow, customRow: View, customIndex: Int) {
        val existing = row.onChoiceSelectedListener
        row.onChoiceSelectedListener = ExpandableChoiceRow.OnChoiceSelectedListener { r, pos ->
            existing?.onChoiceSelected(r, pos)
            customRow.visibility = if (pos == customIndex) View.VISIBLE else View.GONE
        }
    }

    private fun setupSpinner(row: ExpandableChoiceRow, items: List<String>) {
        row.setItems(items)
    }

    /**
     * Blocking — every caller already runs this on testExecutor, never the
     * main thread. In WiFi/Auto mode with no IP saved yet, this used to just
     * fall back to "127.0.0.1" — the *phone's own* loopback, which can never
     * reach the PC, so every PC-control action (Start PC, Check, speed test,
     * ...) failed with "Cannot reach PC" until the user separately visited
     * Settings and tapped Discover first. Now it discovers automatically the
     * same way MainActivity's startStreaming already does, and saves the
     * result so the next call (and the IP field on screen) doesn't need to
     * re-discover.
     */
    private fun getPcHost(): String {
        val connMode = binding.spinnerConnectionMode.selectedItemPosition
        if (connMode == 1) return "127.0.0.1"

        val input = binding.inputPcIp.text.toString().trim()
        if (input.isNotEmpty()) return input

        PcDiscovery.findPc(timeoutMs = 3000)?.let { discovered ->
            runOnUiThread { binding.inputPcIp.setText(discovered) }
            getSharedPreferences("stream_settings", MODE_PRIVATE).edit().putString("pc_ip", discovered).apply()
            return discovered
        }
        return "127.0.0.1"
    }

    private fun discoverPc() {
        binding.discoverButton.isEnabled = false
        binding.discoverButton.text = "..."

        testExecutor.execute {
            val ip = PcDiscovery.findPc(timeoutMs = 3000)
            runOnUiThread {
                binding.discoverButton.isEnabled = true
                if (ip != null) {
                    binding.inputPcIp.setText(ip)
                    binding.discoverButton.text = getString(R.string.settings_discover_found)
                } else {
                    binding.discoverButton.text = getString(R.string.settings_discover_not_found)
                }
                binding.discoverButton.postDelayed({
                    binding.discoverButton.text = getString(R.string.settings_discover_scan)
                }, 2000)
            }
        }
    }

    private fun checkPcStatus() {
        testExecutor.execute {
            val host = getPcHost()
            val status = PcControl.getStatus(host)
            runOnUiThread {
                if (status != null) {
                    if (status.running) {
                        binding.pcStatusDot.setBackgroundResource(R.drawable.dot_live)
                        binding.pcStatusText.text = getString(R.string.pc_status_running, status.services.joinToString(", "))
                        binding.pcStatusText.setTextColor(getColor(R.color.accent_green))
                    } else {
                        binding.pcStatusDot.setBackgroundResource(R.drawable.dot_accent)
                        binding.pcStatusText.text = getString(R.string.pc_status_connected_stopped)
                        binding.pcStatusText.setTextColor(getColor(R.color.accent))
                    }
                } else {
                    binding.pcStatusDot.setBackgroundResource(R.drawable.dot_idle)
                    binding.pcStatusText.text = getString(R.string.pc_status_unreachable)
                    binding.pcStatusText.setTextColor(getColor(R.color.text_tertiary))
                }
            }
        }
    }

    private fun startPcServices() {
        binding.btnStartPc.isEnabled = false
        binding.btnStartPc.text = getString(R.string.pc_starting)
        testExecutor.execute {
            val host = getPcHost()
            val ok = PcControl.startServices(host)
            runOnUiThread {
                binding.btnStartPc.isEnabled = true
                binding.btnStartPc.text = getString(R.string.pc_start)
                if (ok) {
                    AppToast.success(this, getString(R.string.pc_services_started))
                } else {
                    AppToast.error(this, getString(R.string.pc_unreachable_hint))
                }
                checkPcStatus()
            }
        }
    }

    private fun stopPcServices() {
        testExecutor.execute {
            val host = getPcHost()
            PcControl.stopServices(host)
            runOnUiThread {
                AppToast.info(this, getString(R.string.pc_services_stopped))
                checkPcStatus()
            }
        }
    }

    private fun setupAdbReverse() {
        binding.btnAdbReverse.isEnabled = false
        testExecutor.execute {
            val host = getPcHost()
            val ok = PcControl.setupAdbReverse(host)
            runOnUiThread {
                binding.btnAdbReverse.isEnabled = true
                if (ok) {
                    AppToast.success(this, getString(R.string.pc_usb_forwarded))
                } else {
                    AppToast.error(this, getString(R.string.pc_usb_failed))
                }
            }
        }
    }

    private fun populateDeviceInfo() {
        testExecutor.execute {
            val info = try {
                DeviceCapabilities.probe(this)
            } catch (e: Exception) {
                Log.e("SettingsActivity", "device probe failed", e)
                runOnUiThread {
                    binding.deviceModelText.text = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    binding.deviceOsText.text = getString(R.string.device_capabilities_unavailable)
                }
                null
            } ?: return@execute

            deviceInfo = info

            runOnUiThread {
                binding.deviceModelText.text = "${info.manufacturer} ${info.model}"
                binding.deviceOsText.text = "Android ${info.androidVersion} · SDK ${info.sdkInt}"
                binding.deviceRamText.text = DeviceCapabilities.formatRamGb(info.ramTotalMb)
                binding.deviceStorageText.text = "%.0f / %.0f GB".format(info.storageFreeGb, info.storageTotalGb)
                binding.deviceCodecsText.text = info.supportedCodecs.joinToString(" ")

                if (info.notes.isNotEmpty()) {
                    binding.deviceNotesText.text = "ⓘ " + info.notes.joinToString(" · ")
                    binding.deviceNotesText.visibility = View.VISIBLE
                } else {
                    binding.deviceNotesText.visibility = View.GONE
                }

                val list = binding.deviceCamerasList
                list.removeAllViews()
                if (info.cameras.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.device_camera_info_unavailable)
                        setTextColor(getColor(R.color.text_tertiary))
                        textSize = 12f
                    }
                    list.addView(empty)
                } else {
                    info.cameras.forEach { cam ->
                        list.addView(buildCameraRow(cam))
                    }
                }

                addSuperTelephotoLensOptionIfPresent(info)

                // HDR is only offered when some camera on this device can capture 10-bit
                val hdrSupported = info.cameras.any { it.supportsHdr }
                binding.switchHdr.isEnabled = hdrSupported
                if (!hdrSupported) {
                    binding.switchHdr.isChecked = false
                    binding.switchHdr.alpha = 0.4f
                }

                updateCompatibilityWarnings()
            }
        }
    }

    /**
     * The lens row starts with Wide/Ultra-wide/Telephoto (set up before the async
     * device probe finishes). Super-telephoto (periscope zoom) isn't on every device, so
     * it's appended here only once the probe confirms the phone actually has one.
     */
    private fun addSuperTelephotoLensOptionIfPresent(info: DeviceCapabilities.DeviceInfo) {
        val hasSuperTele = info.cameras.any { it.facing == "back" && it.lens == "supertelephoto" }
        if (!hasSuperTele) return
        if (binding.spinnerLens.itemCount >= 4) return
        binding.spinnerLens.addItem(getString(R.string.lens_supertelephoto))

        val savedLensIdx = getSharedPreferences("stream_settings", MODE_PRIVATE).getInt("lens", 0)
        if (savedLensIdx == 3) binding.spinnerLens.setSelection(3)
    }

    private fun buildCameraRow(cam: DeviceCapabilities.CameraInfo): View {
        // Stacked (not side-by-side): localized labels can run long enough in some
        // languages that a shared horizontal row squeezes the label into narrow,
        // ugly multi-line wrapping on narrower phone screens.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val zoomTag = cam.zoomFactor?.let { z ->
            " · " + (if (z == z.toInt().toFloat()) "${z.toInt()}x" else "%.1fx".format(z))
        } ?: ""
        val label = TextView(this).apply {
            text = "${localizedFacing(cam.facing)} · ${localizedLens(cam.lens)}$zoomTag"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
        }

        val topRes = cam.supportedResolutions.maxByOrNull { it.size.width.toLong() * it.size.height }
        val mpTag = if (cam.sensorMegapixels > 0f) "%.0fMP · ".format(cam.sensorMegapixels) else ""
        val readout = TextView(this).apply {
            text = topRes?.let {
                "$mpTag${it.size.width}×${it.size.height} · ${it.maxFps}fps"
            } ?: "$mpTag${cam.maxResolution.width}×${cam.maxResolution.height}"
            setTextColor(getColor(R.color.readout))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) }
        }

        row.addView(label)
        row.addView(readout)
        return row
    }

    /** [DeviceCapabilities.CameraInfo.facing]/[.lens] are internal identifier
     * strings ("back", "ultra-wide", ...), not display text — this maps them
     * to the same localized strings used elsewhere (e.g. the lens spinner),
     * so the CAMERAS list in device info isn't stuck in English regardless
     * of app language. */
    private fun localizedFacing(facing: String): String = when (facing) {
        "back" -> getString(R.string.camera_facing_back)
        "front" -> getString(R.string.camera_facing_front)
        "external" -> getString(R.string.camera_facing_external)
        else -> getString(R.string.label_unknown)
    }

    private fun localizedLens(lens: String): String = when (lens) {
        "wide" -> getString(R.string.lens_wide)
        "ultra-wide" -> getString(R.string.lens_ultrawide)
        "telephoto" -> getString(R.string.lens_telephoto)
        "supertelephoto" -> getString(R.string.lens_supertelephoto)
        "front" -> getString(R.string.camera_facing_front)
        else -> getString(R.string.label_unknown)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val resolutionLabels = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p", "4320p")
    private val fpsValues = listOf(24, 30, 60, 120)

    private fun updateCompatibilityWarnings() {
        val info = deviceInfo ?: return
        val backCam = DeviceCapabilities.findBackCamera(info) ?: return

        val fpsIdx = binding.spinnerFps.selectedItemPosition
        val fps = if (fpsIdx < fpsValues.size) fpsValues[fpsIdx] else 30

        // Custom resolution has no fixed preset label to look up in this
        // device's supportedResolutions list (those are only ever named
        // "360p".."4320p"), so there's nothing meaningful to compare against
        // — just skip the preset-only checks below rather than coercing the
        // index down to whatever the last preset happens to be and warning
        // about (or snapping back from) a resolution the user didn't pick.
        if (binding.spinnerResolution.selectedItemPosition == 7) {
            binding.fpsHintText.visibility = View.VISIBLE
            binding.fpsHintText.text = getString(R.string.fps_hint_custom_resolution)
            binding.fpsHintText.setTextColor(getColor(R.color.text_tertiary))
            return
        }

        val resIdx = binding.spinnerResolution.selectedItemPosition.coerceIn(0, resolutionLabels.size - 1)
        val resLabel = resolutionLabels[resIdx]

        // Always-visible capability hint under the Frame rate row
        val support = backCam.supportedResolutions.firstOrNull { it.label == resLabel }
        binding.fpsHintText.visibility = View.VISIBLE
        if (support == null) {
            binding.fpsHintText.text = getString(R.string.fps_hint_unsupported_resolution, resLabel)
            binding.fpsHintText.setTextColor(getColor(R.color.accent_red))
            // Don't let an impossible resolution be saved — snap back to 1080p
            if (resIdx != 3) {
                binding.spinnerResolution.setSelection(3)
                AppToast.warning(this, getString(R.string.toast_resolution_unavailable, resLabel))
            }
        } else {
            binding.fpsHintText.text = getString(R.string.fps_hint_max_fps, support.maxFps, resLabel)
            binding.fpsHintText.setTextColor(getColor(R.color.text_tertiary))
        }

        val supported = DeviceCapabilities.isCombinationSupported(backCam, resLabel, fps)
        if (!supported && support != null) {
            AppToast.warning(this, getString(R.string.toast_resolution_maxes_out, resLabel, support.maxFps))
        }
    }

    private fun runSpeedTest() {
        binding.btnSpeedTest.isEnabled = false
        binding.speedTestResult.visibility = View.VISIBLE
        binding.speedTestResult.text = getString(R.string.speedtest_running)

        testExecutor.execute {
            try {
                val manager = SpeedTestManager(getPcHost(), 8788)
                val result = manager.runTest(cacheDir) { phase ->
                    val textRes = when (phase) {
                        SpeedTestPhase.GENERATING_FILE -> R.string.speedtest_generating
                        SpeedTestPhase.UPLOADING -> R.string.speedtest_uploading
                        SpeedTestPhase.DOWNLOADING -> R.string.speedtest_downloading
                        SpeedTestPhase.CLEANING_UP -> R.string.speedtest_cleaning_up
                    }
                    runOnUiThread { binding.speedTestResult.text = getString(textRes) }
                }
                runOnUiThread {
                    binding.btnSpeedTest.isEnabled = true
                    binding.speedTestResult.text =
                        "↑ %.0f Mbps · ↓ %.0f Mbps".format(result.uploadSpeedMbps, result.downloadSpeedMbps)
                    binding.speedTestResult.setTextColor(getColor(R.color.accent_green))
                }
            } catch (e: Exception) {
                Log.w("SettingsActivity", "speed test failed", e)
                runOnUiThread {
                    binding.btnSpeedTest.isEnabled = true
                    binding.speedTestResult.text = getString(R.string.speedtest_error)
                    binding.speedTestResult.setTextColor(getColor(R.color.accent_red))
                }
            }
        }
    }

    private fun animateEntrance() {
        val root = binding.settingsRoot
        root.alpha = 0f
        root.translationY = 30f
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE)

        // Video
        binding.spinnerResolution.setSelection(prefs.getInt("resolution", 3))
        binding.spinnerAspectRatio.setSelection(prefs.getInt("aspect_ratio", 1))
        binding.spinnerVideoCodec.setSelection(prefs.getInt("video_codec", 0))
        binding.spinnerVideoBitrate.setSelection(prefs.getInt("video_bitrate", 1))
        binding.spinnerFps.setSelection(prefs.getInt("fps", 2))
        binding.inputCustomWidth.setText(prefs.getInt("custom_res_width", 1920).toString())
        binding.inputCustomHeight.setText(prefs.getInt("custom_res_height", 1080).toString())
        binding.inputCustomBitrate.setText(prefs.getString("custom_bitrate", ""))
        binding.inputCustomFps.setText(prefs.getString("custom_fps", ""))
        binding.switchHdr.isChecked = prefs.getBoolean("hdr", false)
        binding.switchStabilization.isChecked = prefs.getBoolean("stabilization", true)
        binding.spinnerAutofocus.setSelection(prefs.getInt("autofocus", 0))
        binding.spinnerAfSpeed.setSelection(prefs.getInt("af_speed", 0))

        // Audio
        binding.switchAudio.isChecked = prefs.getBoolean("audio_enabled", true)
        binding.spinnerSampleRate.setSelection(prefs.getInt("sample_rate", 1))
        binding.spinnerAudioBitrate.setSelection(prefs.getInt("audio_bitrate", 1))
        binding.spinnerAudioCodec.setSelection(prefs.getInt("audio_codec", 0))
        binding.switchNoiseReduction.isChecked = prefs.getBoolean("noise_reduction", false)
        binding.switchWindFilter.isChecked = prefs.getBoolean("wind_filter", false)
        binding.switchAudioMeter.isChecked = prefs.getBoolean("audio_meter", false)

        // Camera
        binding.spinnerWhiteBalance.setSelection(prefs.getInt("white_balance", 0))
        binding.spinnerLens.setSelection(prefs.getInt("lens", 0))
        binding.switchGrid.isChecked = prefs.getBoolean("grid", false)
        binding.switchMirror.isChecked = prefs.getBoolean("mirror", false)

        // Network
        binding.inputPcIp.setText(prefs.getString("pc_ip", ""))
        binding.spinnerConnectionMode.setSelection(prefs.getInt("connection_mode", 0))
        binding.spinnerProtocol.setSelection(prefs.getInt("protocol", 0))
        binding.switchLowLatency.isChecked = prefs.getBoolean("low_latency", true)
        binding.switchAutoReconnect.isChecked = prefs.getBoolean("auto_reconnect", true)
        binding.switchSyncObs.isChecked = prefs.getBoolean("sync_obs", true)

        // Overlay
        binding.switchCopyright.isChecked = prefs.getBoolean("copyright_enabled", false)
        binding.spinnerCopyrightMode.setSelection(prefs.getInt("copyright_mode", 0))
        binding.inputCopyright.setText(prefs.getString("copyright_text", ""))
        val savedUri = prefs.getString("copyright_image_uri", null)
        if (!savedUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(savedUri)
                binding.imgCopyrightPreview.setImageURI(uri)
                copyrightImageUri = uri
                binding.imgCopyrightPreview.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Uri no longer accessible (e.g. leftover from before the private-storage
                // fix, or the picker permission was revoked) — drop it instead of crashing.
                Log.w("SettingsActivity", "stale copyright image uri, clearing", e)
                copyrightImageUri = null
                prefs.edit().remove("copyright_image_uri").apply()
                binding.imgCopyrightPreview.visibility = View.GONE
            }
        }
        binding.switchTimestamp.isChecked = prefs.getBoolean("timestamp", false)
        binding.spinnerWatermarkPos.setSelection(prefs.getInt("watermark_pos", 0))

        // Display
        binding.switchKeepAwake.isChecked = prefs.getBoolean("keep_screen_on", true)
        binding.spinnerStreamBrightness.setSelection(prefs.getInt("stream_brightness", 0))
        binding.switchExperimentalCamera2.isChecked = prefs.getBoolean("experimental_camera2", false)
    }

    override fun onPause() {
        super.onPause()
        savePreferences()
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("stream_settings", MODE_PRIVATE).edit()

        // Video
        prefs.putInt("resolution", binding.spinnerResolution.selectedItemPosition)
        prefs.putInt("aspect_ratio", binding.spinnerAspectRatio.selectedItemPosition)
        prefs.putInt("video_codec", binding.spinnerVideoCodec.selectedItemPosition)
        prefs.putInt("video_bitrate", binding.spinnerVideoBitrate.selectedItemPosition)
        prefs.putInt("fps", binding.spinnerFps.selectedItemPosition)
        binding.inputCustomWidth.text.toString().toIntOrNull()?.let { prefs.putInt("custom_res_width", it) }
        binding.inputCustomHeight.text.toString().toIntOrNull()?.let { prefs.putInt("custom_res_height", it) }
        prefs.putString("custom_bitrate", binding.inputCustomBitrate.text.toString())
        prefs.putString("custom_fps", binding.inputCustomFps.text.toString())
        prefs.putBoolean("hdr", binding.switchHdr.isChecked)
        prefs.putBoolean("stabilization", binding.switchStabilization.isChecked)
        prefs.putInt("autofocus", binding.spinnerAutofocus.selectedItemPosition)
        prefs.putInt("af_speed", binding.spinnerAfSpeed.selectedItemPosition)

        // Audio
        prefs.putBoolean("audio_enabled", binding.switchAudio.isChecked)
        prefs.putInt("sample_rate", binding.spinnerSampleRate.selectedItemPosition)
        prefs.putInt("audio_bitrate", binding.spinnerAudioBitrate.selectedItemPosition)
        prefs.putInt("audio_codec", binding.spinnerAudioCodec.selectedItemPosition)
        prefs.putBoolean("noise_reduction", binding.switchNoiseReduction.isChecked)
        prefs.putBoolean("wind_filter", binding.switchWindFilter.isChecked)
        prefs.putBoolean("audio_meter", binding.switchAudioMeter.isChecked)

        // Camera
        prefs.putInt("white_balance", binding.spinnerWhiteBalance.selectedItemPosition)
        prefs.putInt("lens", binding.spinnerLens.selectedItemPosition)
        prefs.putBoolean("grid", binding.switchGrid.isChecked)
        prefs.putBoolean("mirror", binding.switchMirror.isChecked)

        // Network
        prefs.putString("pc_ip", binding.inputPcIp.text.toString())
        prefs.putInt("connection_mode", binding.spinnerConnectionMode.selectedItemPosition)
        prefs.putInt("protocol", binding.spinnerProtocol.selectedItemPosition)
        prefs.putBoolean("low_latency", binding.switchLowLatency.isChecked)
        prefs.putBoolean("auto_reconnect", binding.switchAutoReconnect.isChecked)
        prefs.putBoolean("sync_obs", binding.switchSyncObs.isChecked)

        // Overlay
        prefs.putBoolean("copyright_enabled", binding.switchCopyright.isChecked)
        prefs.putInt("copyright_mode", binding.spinnerCopyrightMode.selectedItemPosition)
        prefs.putString("copyright_text", binding.inputCopyright.text.toString())
        prefs.putString("copyright_image_uri", copyrightImageUri?.toString() ?: "")
        prefs.putBoolean("timestamp", binding.switchTimestamp.isChecked)
        prefs.putInt("watermark_pos", binding.spinnerWatermarkPos.selectedItemPosition)

        // Display
        prefs.putBoolean("keep_screen_on", binding.switchKeepAwake.isChecked)
        prefs.putInt("stream_brightness", binding.spinnerStreamBrightness.selectedItemPosition)
        prefs.putBoolean("experimental_camera2", binding.switchExperimentalCamera2.isChecked)

        prefs.apply()
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_down)
    }
}
