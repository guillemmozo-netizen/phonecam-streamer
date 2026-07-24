package com.phonecam.streamer.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phonecam.streamer.R

/**
 * The app's one and only "toast" — a floating bottom card in our own style,
 * replacing every use of the system's Toast.makeText() and AlertDialog, which
 * render with the device's default theme/font and look like they belong to a
 * different app. Every short message the app shows (status, errors, the Pro
 * upsell, the ad-reward flow) goes through this same component.
 */
object AppToast {

    private enum class Style(val icon: String, val iconBgRes: Int, val darkIconText: Boolean = false) {
        INFO("ⓘ", R.drawable.toast_icon_bg_info),
        SUCCESS("✓", R.drawable.toast_icon_bg_success),
        WARNING("⚠", R.drawable.toast_icon_bg_warning, darkIconText = true),
        ERROR("✕", R.drawable.toast_icon_bg_error),
        PRO("⟐", R.drawable.pro_toast_icon_bg),
    }

    private var current: View? = null

    // ── Generic status messages — the direct Toast.makeText() replacements ──

    fun info(activity: Activity, message: String) = showCard(activity, Style.INFO, null, message, null, null)
    fun success(activity: Activity, message: String) = showCard(activity, Style.SUCCESS, null, message, null, null)
    fun warning(activity: Activity, message: String) = showCard(activity, Style.WARNING, null, message, null, null)
    fun error(activity: Activity, message: String) = showCard(activity, Style.ERROR, null, message, null, null)

    // ── Pro / ad-reward moments — richer cards with a title and sometimes a button ──

    /** Shown when a free-tier user taps a Pro-gated setting. */
    fun show(activity: Activity, description: String, title: String? = null, buttonText: String? = null, onButtonClick: (() -> Unit)? = null) {
        showCard(activity, Style.PRO, title, description, buttonText, onButtonClick)
    }

    /** Shown right after a completed ad batch extends Pro time. */
    fun showCelebration(activity: Activity, title: String, description: String) {
        showCard(activity, Style.PRO, title, description, null, null)
    }

    /** Mid-batch progress ("2/3 watched") — no button, just informational. */
    fun showProgress(activity: Activity, title: String, description: String) {
        showCard(activity, Style.PRO, title, description, null, null)
    }

    /** The "watch 3 ads for 1h" explainer, shown once per app session before the first ad. */
    fun showAdIntro(activity: Activity, title: String, description: String, buttonText: String, onWatchAd: () -> Unit) {
        showCard(activity, Style.PRO, title, description, buttonText, onWatchAd)
    }

    private fun showCard(
        activity: Activity,
        style: Style,
        title: String?,
        description: String,
        buttonText: String?,
        onButtonClick: (() -> Unit)?,
    ) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        current?.let { root.removeView(it) }

        val dp = { v: Int -> (v * root.resources.displayMetrics.density).toInt() }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.pro_toast_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(12).toFloat()
        }

        val iconView = TextView(activity).apply {
            text = style.icon
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(
                ContextCompat.getColor(activity, if (style.darkIconText) R.color.surface_dark else R.color.text_primary),
            )
            background = ContextCompat.getDrawable(activity, style.iconBgRes)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }

        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12); marginEnd = dp(12)
            }
        }
        if (title != null) {
            textCol.addView(
                TextView(activity).apply {
                    text = title
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                },
            )
        }
        textCol.addView(
            TextView(activity).apply {
                text = description
                textSize = if (title != null) 11f else 13f
                setTextColor(
                    ContextCompat.getColor(activity, if (title != null) R.color.text_secondary else R.color.text_primary),
                )
                if (title != null) setPadding(0, dp(1), 0, 0)
            },
        )

        card.addView(iconView)
        card.addView(textCol)

        if (buttonText != null) {
            val btn = TextView(activity).apply {
                text = buttonText
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                background = ContextCompat.getDrawable(activity, R.drawable.pro_toast_button_bg)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismiss(root)
                    onButtonClick?.invoke()
                }
            }
            card.addView(btn)
        }

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(28)
        }
        root.addView(card, params)
        current = card

        card.translationY = dp(40).toFloat()
        card.alpha = 0f
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()

        card.postDelayed({ dismiss(root) }, 3500)
    }

    private fun dismiss(root: FrameLayout) {
        val card = current ?: return
        current = null
        card.animate()
            .translationY((card.height + 40).toFloat())
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { root.removeView(card) }
            .start()
    }
}
