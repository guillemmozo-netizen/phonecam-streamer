package com.phonecam.streamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import com.phonecam.streamer.databinding.ActivityDiagnosticsBinding
import com.phonecam.streamer.device.BrandBadge
import com.phonecam.streamer.device.CameraDiagnostics
import com.phonecam.streamer.ui.AppToast
import com.phonecam.streamer.ui.ExpandableSection
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every Camera2 capability this device reports, on one screen.
 *
 * Two audiences, one probe. The card body is the handful of numbers that mean
 * something while choosing a lens — sensor size, aperture, ISO and shutter
 * range, resolution, what the hardware level actually is. Everything else, and
 * it is a great deal else, lives behind a per-section "see more" that animates
 * open, because a diagnostic nobody can read is just a wall.
 *
 * The expanded half is rendered **from the exported JSON itself**, not from a
 * parallel set of getters. That is the only way the screen and the file cannot
 * drift apart, and it means a field added to [CameraDiagnostics] appears here
 * with no UI work at all.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var report: CameraDiagnostics.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.copyJsonButton.setOnClickListener { copyJson() }
        binding.shareJsonButton.setOnClickListener { shareJson() }
        setExportEnabled(false)

        // Probing every camera, every physical sub-camera and every stream
        // configuration touches the camera service dozens of times; on the
        // main thread that is a visible freeze on entry.
        executor.execute {
            val probed = try {
                CameraDiagnostics.probe(this)
            } catch (e: Throwable) {
                Log.e(TAG, "diagnostics probe failed", e)
                null
            }
            runOnUiThread {
                binding.probeProgress.visibility = View.GONE
                if (probed == null) {
                    binding.diagnosticsContainer.addView(
                        bodyText(getString(R.string.diagnostics_probe_failed)),
                    )
                    return@runOnUiThread
                }
                report = probed
                setExportEnabled(true)
                render(probed)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun setExportEnabled(enabled: Boolean) {
        binding.copyJsonButton.isEnabled = enabled
        binding.shareJsonButton.isEnabled = enabled
        binding.copyJsonButton.alpha = if (enabled) 1f else 0.4f
        binding.shareJsonButton.alpha = if (enabled) 1f else 0.4f
    }

    // ──────────────────────────────────────────────────────────────────
    // Rendering
    // ──────────────────────────────────────────────────────────────────

    private fun render(report: CameraDiagnostics.Report) {
        val container = binding.diagnosticsContainer
        container.removeAllViews()

        container.addView(sectionLabel(getString(R.string.diagnostics_device_section)))
        container.addView(
            card {
                addView(brandTitle(report.deviceLine))
                addView(bodyText(report.systemLine))
                addView(bodyText(report.displayLine))
                addView(
                    expandable(getString(R.string.diagnostics_see_more)) {
                        listOf(
                            "system" to report.json.optJSONObject("system"),
                            "display" to report.json.optJSONObject("display"),
                        ).mapNotNull { (name, obj) -> obj?.let { name to it } }
                            .map { (name, obj) -> jsonBlock(name, obj) }
                    },
                )
            },
        )

        val camerasJson = report.json.optJSONArray("cameras") ?: JSONArray()
        container.addView(
            sectionLabel(getString(R.string.diagnostics_cameras_section, report.cameras.size)),
        )
        report.cameras.forEachIndexed { index, summary ->
            val json = camerasJson.optJSONObject(index)
            container.addView(cameraCard(summary, json))
        }
    }

    private fun cameraCard(summary: CameraDiagnostics.CameraSummary, json: JSONObject?): View = card {
        val kind = if (summary.isPhysical) {
            getString(R.string.diagnostics_physical_camera)
        } else {
            getString(R.string.diagnostics_logical_camera)
        }
        addView(titleText("${summary.facing} · ID ${summary.id}"))
        addView(bodyText("$kind · ${summary.hardwareLevel}"))

        // The always-visible half: what a person choosing a lens needs. Every
        // one of these is null-tolerant — a HAL that does not publish an
        // aperture must produce a row saying so, not a missing row that reads
        // as "the app did not look".
        addView(divider())
        listOfNotNull(
            summary.megapixels?.let { getString(R.string.diagnostics_sensor) to "%.1f MP".format(it) },
            summary.maxResolution?.let { getString(R.string.diagnostics_max_resolution) to it },
            summary.sensorSizeMm?.let { getString(R.string.diagnostics_sensor_size) to it },
            summary.pixelPitchUm?.let { getString(R.string.diagnostics_pixel_pitch) to "%.2f µm".format(it) },
            summary.focalLengthMm?.let { getString(R.string.diagnostics_focal_length) to "%.2f mm".format(it) },
            summary.apertureF?.let { getString(R.string.diagnostics_aperture) to "f/%.1f".format(it) },
            summary.isoRange?.let { getString(R.string.diagnostics_iso) to it },
            summary.shutterRange?.let { getString(R.string.diagnostics_shutter) to it },
            summary.maxFps?.let { getString(R.string.diagnostics_max_fps) to "$it fps" },
        ).forEach { (label, value) -> addView(keyValue(label, value)) }

        val badges = buildList {
            if (summary.hasOis) add("OIS")
            if (summary.hasFlash) add("FLASH")
            addAll(summary.capabilities)
        }
        if (badges.isNotEmpty()) addView(bodyText(badges.joinToString(" · ")).apply {
            setTextColor(getColor(R.color.accent))
        })

        if (json != null) {
            addView(
                expandable(getString(R.string.diagnostics_see_more)) {
                    json.keys().asSequence().toList().map { key -> jsonBlock(key, json.get(key)) }
                },
            )
        }
    }

    /**
     * A "see more" whose content is built only on first open.
     *
     * A full report is roughly two thousand rows across all cameras; building
     * every one up front is seconds of layout for a screen where most of them
     * are never looked at.
     */
    private fun expandable(title: String, content: () -> List<View>): ExpandableSection =
        ExpandableSection(this).apply {
            this.title = title
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            var built = false
            onToggle = { expanded ->
                if (expanded && !built) {
                    built = true
                    content().forEach { addContent(it) }
                }
            }
        }

    /** One JSON node as a titled block of rows, nesting as deep as it goes. */
    private fun jsonBlock(name: String, value: Any?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        addView(
            TextView(this@DiagnosticsActivity).apply {
                text = humanize(name)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.05f
            },
        )
        renderNode(value, this, depth = 0)
    }

    private fun renderNode(value: Any?, into: LinearLayout, depth: Int) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.get(key)
                if (child is JSONObject || (child is JSONArray && child.length() > 0 && child.opt(0) is JSONObject)) {
                    into.addView(
                        TextView(this).apply {
                            text = humanize(key)
                            setTextColor(getColor(R.color.text_tertiary))
                            textSize = 11f
                            setPadding(dp(8 * (depth + 1)), dp(6), 0, dp(2))
                        },
                    )
                    renderNode(child, into, depth + 1)
                } else {
                    into.addView(keyValue(humanize(key), scalarText(child), depth + 1))
                }
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    into.addView(keyValue("—", getString(R.string.diagnostics_empty), depth + 1))
                    return
                }
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) {
                        renderNode(item, into, depth + 1)
                        if (i != value.length() - 1) into.addView(divider())
                    } else {
                        into.addView(keyValue("", scalarText(item), depth + 1))
                    }
                }
            }
            else -> into.addView(keyValue("", scalarText(value), depth + 1))
        }
    }

    /**
     * JSON null is the report saying "this device does not publish it", which
     * is a real answer and has to look like one rather than like a blank.
     */
    private fun scalarText(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "—"
        value is Boolean -> if (value) "✓" else "✗"
        else -> value.toString()
    }

    /** `exposureTimeMaxNs` -> `Exposure time max ns`. */
    private fun humanize(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }

    // ──────────────────────────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────────────────────────

    private fun copyJson() {
        val json = report?.let { CameraDiagnostics.toJson(it) } ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FrameCast diagnostics", json))
        AppToast.success(this, getString(R.string.diagnostics_copied))
    }

    /**
     * Writes the report and hands it to the system chooser.
     *
     * The app picks no destination: this describes the user's own hardware, so
     * where it goes is theirs to choose, and the chooser is what makes that
     * an explicit act rather than a silent upload.
     */
    private fun shareJson() {
        val json = report?.let { CameraDiagnostics.toJson(it) } ?: return
        try {
            val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "framecast-camera2-${android.os.Build.MODEL.replace(' ', '_')}.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "FrameCast Camera2 · ${android.os.Build.MODEL}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_export_json)))
        } catch (e: Exception) {
            Log.e(TAG, "share failed", e)
            AppToast.error(this, getString(R.string.diagnostics_export_failed))
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Small view builders, matching Settings' card styling
    // ──────────────────────────────────────────────────────────────────

    private fun card(build: LinearLayout.() -> Unit): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            build()
        }
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_secondary))
            addView(inner)
        }
    }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_tertiary))
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), dp(14), 0, dp(8))
    }

    /**
     * The device title with its brand monogram, matching the Settings card.
     *
     * [deviceLine] arrives as "manufacturer model"; the brand name replaces
     * the manufacturer so a Redmi says Redmi rather than Xiaomi.
     */
    private fun brandTitle(deviceLine: String): View {
        val badge = BrandBadge.resolve(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@DiagnosticsActivity).apply {
                    text = badge.letter
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        .apply { marginEnd = dp(8) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(
                            runCatching { android.graphics.Color.parseColor(badge.colorHex) }
                                .getOrDefault(getColor(R.color.text_tertiary)),
                        )
                    }
                },
            )
            addView(titleText("${badge.name} ${android.os.Build.MODEL}"))
        }
    }

    private fun titleText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_primary))
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(0, dp(2), 0, 0)
    }

    private fun keyValue(label: String, value: String, depth: Int = 0): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8 * depth), dp(3), 0, dp(3))
            addView(
                TextView(this@DiagnosticsActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.text_tertiary))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                TextView(this@DiagnosticsActivity).apply {
                    text = value
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 12f
                    gravity = Gravity.END
                    // Even weights. Giving the value column extra width made
                    // short values look fine and wrapped every two-word label
                    // ("Android Version" over two lines next to a bare "16"),
                    // and the values that genuinely need room — a fingerprint,
                    // a list of every JPEG size — wrap anyway.
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(8); bottomMargin = dp(4) }
        setBackgroundColor(getColor(R.color.separator))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "DiagnosticsActivity"
    }
}
