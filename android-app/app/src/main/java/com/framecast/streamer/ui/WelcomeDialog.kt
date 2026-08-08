package com.framecast.streamer.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import com.framecast.streamer.databinding.DialogWelcomeBinding

/**
 * One-time "thanks for testing during Alpha/Beta" popup, shown exactly once
 * per install right after the very first launch — see MainActivity.onCreate,
 * which gates the rest of its own setup behind this callback so it's
 * genuinely the first thing a new user sees, before the camera-permission
 * prompt or anything else.
 */
object WelcomeDialog {
    private const val PREFS = "app_state"
    private const val KEY_SHOWN = "welcome_shown"

    /** Shows the dialog if it's never been shown on this install; otherwise
     * calls [onDismissed] immediately. Either way, [onDismissed] runs exactly
     * once so callers can chain the rest of their setup unconditionally. */
    fun showIfFirstLaunch(activity: Activity, onDismissed: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) {
            onDismissed()
            return
        }

        val binding = DialogWelcomeBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Must be explicitly acknowledged: this only ever gets one shot at
            // showing per install, so a stray back-press or outside-tap
            // shouldn't be able to dismiss it without marking it seen — the
            // button below is the only way out.
            setCancelable(false)
        }

        binding.welcomeContinueButton.setOnClickListener {
            prefs.edit().putBoolean(KEY_SHOWN, true).apply()
            dialog.dismiss()
            onDismissed()
        }

        dialog.show()
    }
}
