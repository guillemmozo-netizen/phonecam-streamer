package com.phonecam.streamer

import android.content.Context
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.CameraSelector

/**
 * Reads user settings from SharedPreferences and turns them into
 * types the CameraX pipeline needs. All values fall back to safe defaults
 * so the camera always starts even if a preference is missing.
 */
data class StreamConfig(
    // One of the fixed preset labels ("1080p" etc) or, for a custom
    // resolution, the literal "WIDTHxHEIGHT" string — see pixelSizeFor,
    // which is the single place that turns either shape into actual pixels.
    val qualityLabel: String,
    val fps: Int,
    val videoBitrateBps: Int,
    val hdr: Boolean,
    val stabilization: Boolean,
    val whiteBalanceMode: Int,         // CaptureRequest.CONTROL_AWB_MODE_*
    val lensFacing: Int,               // CameraSelector.LENS_FACING_BACK/FRONT
    val lensType: String,              // "wide" / "ultra-wide" / "telephoto" — only meaningful when facing back
    val mirror: Boolean,
    val grid: Boolean,
    val autofocusMode: AutofocusMode,
    val aspectRatio: String,           // "4:3" / "16:9" / "1:1"
    val autofocusSpeed: AutofocusSpeed,
    val audioMeterEnabled: Boolean,
    // Real streaming audio, as of this version — these four used to be saved
    // preferences with nothing behind them ("decorative", as the docs put
    // it): only the on-screen level meter ever touched the microphone, and
    // nothing was ever sent to the PC. They now drive AudioCapture and
    // AudioEncoder for real.
    val audioEnabled: Boolean,
    val audioSampleRate: Int,
    val audioBitrateBps: Int,
    val audioCodec: String,            // "aac" — see createAudioEncoder for what the other choices do
    val noiseReduction: Boolean,
    val windFilter: Boolean,           // a ~100Hz low-cut — see HighPassFilter
    // Auto-syncs OBS's canvas resolution/fps/bitrate to match this stream —
    // see pc_receiver/obs_sync.py. On by default; some users run OBS for
    // something else at the same time and don't want it reconfigured out
    // from under them.
    val syncObs: Boolean,
) {
    enum class AutofocusMode { CONTINUOUS, TAP, MANUAL }

    /**
     * Camera2 has no direct "AF motor speed" knob — HALs don't expose it. These presets
     * combine the params that DO tangibly change tap-to-focus feel: AF mode (video vs
     * picture pulls differently by design), the metering region size (tight = snappy
     * lock, broad = smooth averaging), and how long a tap holds focus before reverting
     * to continuous tracking.
     */
    enum class AutofocusSpeed(val meteringPointSize: Float, val autoCancelSeconds: Long, val continuousVideo: Boolean) {
        STANDARD(meteringPointSize = 0.15f, autoCancelSeconds = 3, continuousVideo = false),
        CINEMATIC(meteringPointSize = 0.25f, autoCancelSeconds = 6, continuousVideo = true),
        ACTION(meteringPointSize = 0.08f, autoCancelSeconds = 1, continuousVideo = false),
        MACRO(meteringPointSize = 0.08f, autoCancelSeconds = 5, continuousVideo = false),
    }

    companion object {
        private const val PREFS = "stream_settings"

        fun load(context: Context): StreamConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val resIdx = p.getInt("resolution", 3)
            val qualityLabel = if (resIdx == 7) {
                // Custom: stored as separate width/height prefs (matching the
                // two-field entry row in Settings) rather than one free-text
                // "WxH" field, so there's no user-typed separator to parse —
                // just normalize to canonical "WxH" here, once.
                val w = p.getInt("custom_res_width", 1920).coerceIn(16, 7680)
                val h = p.getInt("custom_res_height", 1080).coerceIn(16, 4320)
                "${w}x${h}"
            } else {
                resolutionFor(resIdx)
            }

            val fpsIdx = p.getInt("fps", 2)
            val customFps = p.getString("custom_fps", "")?.toIntOrNull()
            val fps = when {
                // Was coerceIn(15, 240) — silently rewrote "1 fps" (or anything
                // below 15) up to 15, so there was no way to actually request a
                // slow-motion-style low frame rate. 1 is the real floor: fps has
                // to be a positive integer, nothing else about the pipeline
                // requires more than that (see CameraStreamer's frame-pacing,
                // which is what actually makes a low fps stick regardless of
                // what the camera hardware's own capture rate is).
                fpsIdx == 4 && customFps != null -> customFps.coerceIn(1, 240)
                fpsIdx == 0 -> 24
                fpsIdx == 1 -> 30
                fpsIdx == 2 -> 60
                fpsIdx == 3 -> 120
                else -> 30
            }

            val bitrateIdx = p.getInt("video_bitrate", 1)
            val customBitrate = p.getString("custom_bitrate", "")?.toIntOrNull()
            val bitrateMbps = when {
                bitrateIdx == 5 && customBitrate != null -> customBitrate.coerceIn(1, 500)
                bitrateIdx == 0 -> 10
                bitrateIdx == 1 -> 20
                bitrateIdx == 2 -> 35
                bitrateIdx == 3 -> 50
                bitrateIdx == 4 -> 100
                else -> 20
            }

            return StreamConfig(
                qualityLabel = qualityLabel,
                fps = fps,
                videoBitrateBps = bitrateMbps * 1_000_000,
                hdr = p.getBoolean("hdr", false),
                stabilization = p.getBoolean("stabilization", true),
                whiteBalanceMode = whiteBalanceModeFor(p.getInt("white_balance", 0)),
                lensFacing = lensFacingFor(p.getInt("camera_facing", 0)),
                lensType = lensTypeFor(p.getInt("lens", 0)),
                mirror = p.getBoolean("mirror", false),
                grid = p.getBoolean("grid", false),
                autofocusMode = when (p.getInt("autofocus", 0)) {
                    1 -> AutofocusMode.TAP
                    2 -> AutofocusMode.MANUAL
                    else -> AutofocusMode.CONTINUOUS
                },
                aspectRatio = aspectRatioFor(p.getInt("aspect_ratio", 1)),
                autofocusSpeed = when (p.getInt("af_speed", 0)) {
                    1 -> AutofocusSpeed.CINEMATIC
                    2 -> AutofocusSpeed.ACTION
                    3 -> AutofocusSpeed.MACRO
                    else -> AutofocusSpeed.STANDARD
                },
                audioMeterEnabled = p.getBoolean("audio_meter", false),
                audioEnabled = p.getBoolean("audio_enabled", true),
                audioSampleRate = sampleRateFor(p.getInt("sample_rate", 1)),
                audioBitrateBps = audioBitrateFor(p.getInt("audio_bitrate", 1)),
                audioCodec = audioCodecFor(p.getInt("audio_codec", 0)),
                noiseReduction = p.getBoolean("noise_reduction", false),
                windFilter = p.getBoolean("wind_filter", false),
                syncObs = p.getBoolean("sync_obs", true),
            )
        }

        // Matches SettingsActivity's spinnerSampleRate: 44.1 / 48 (default) / 96 kHz.
        // A device that won't open the chosen rate falls back inside
        // AudioCapture rather than losing audio entirely.
        private fun sampleRateFor(idx: Int): Int = when (idx) {
            0 -> 44100
            2 -> 96000
            else -> 48000
        }

        // Matches spinnerAudioBitrate: 128 / 192 (default) / 256 / 320 kbps.
        private fun audioBitrateFor(idx: Int): Int = when (idx) {
            0 -> 128_000
            2 -> 256_000
            3 -> 320_000
            else -> 192_000
        }

        // Matches spinnerAudioCodec: AAC (default) / OPUS / FLAC. Only AAC is
        // implemented for streaming; the other two are carried through as the
        // user's stated choice and resolved — with a log line — in
        // createAudioEncoder, rather than being silently rewritten here where
        // nobody would see it happen.
        private fun audioCodecFor(idx: Int): String = when (idx) {
            1 -> "opus"
            2 -> "flac"
            else -> "aac"
        }

        // Index order matches the Composition spinner: 4:3, 16:9 (default), 1:1, 9:16, 3:4
        private fun aspectRatioFor(idx: Int): String = when (idx) {
            0 -> "4:3"
            2 -> "1:1"
            3 -> "9:16"
            4 -> "3:4"
            else -> "16:9"
        }

        /** Numerator/denominator for a ratio label, e.g. "4:3" -> 4 to 3. */
        fun aspectRatioParts(label: String): Pair<Int, Int> = when (label) {
            "4:3" -> 4 to 3
            "3:4" -> 3 to 4
            "1:1" -> 1 to 1
            "9:16" -> 9 to 16
            else -> 16 to 9
        }

        private fun resolutionFor(idx: Int): String = when (idx) {
            0 -> "360p"
            1 -> "480p"
            2 -> "720p"
            3 -> "1080p"
            4 -> "1440p"
            5 -> "2160p"
            6 -> "4320p"
            else -> "1080p"
        }

        private val presetPixelSizes = mapOf(
            "360p" to (640 to 360),
            "480p" to (854 to 480),
            "720p" to (1280 to 720),
            "1080p" to (1920 to 1080),
            "1440p" to (2560 to 1440),
            "2160p" to (3840 to 2160),
            "4320p" to (7680 to 4320),
        )

        /**
         * Pixel dimensions for a qualityLabel, shared by camera binding and the
         * network encoder. Handles both a fixed preset label ("1080p") and a
         * custom "WIDTHxHEIGHT" label (see load()) — the two are otherwise
         * carried around identically everywhere else in the app, so this is
         * the one place that needs to know the difference.
         */
        fun pixelSizeFor(label: String): Pair<Int, Int> {
            presetPixelSizes[label]?.let { return it }
            val parts = label.lowercase().split("x")
            if (parts.size == 2) {
                val w = parts[0].trim().toIntOrNull()
                val h = parts[1].trim().toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) return w to h
            }
            return 1920 to 1080
        }

        private fun lensFacingFor(facingIdx: Int): Int {
            return if (facingIdx == 1) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        }

        private fun lensTypeFor(lensIdx: Int): String = when (lensIdx) {
            1 -> "ultra-wide"
            2 -> "telephoto"
            3 -> "supertelephoto"
            else -> "wide"
        }

        // Matches SettingsActivity's spinnerWhiteBalance: Auto/Daylight/Cloudy/Tungsten/Fluorescent
        private fun whiteBalanceModeFor(idx: Int): Int = when (idx) {
            1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            3 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            4 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
    }
}
