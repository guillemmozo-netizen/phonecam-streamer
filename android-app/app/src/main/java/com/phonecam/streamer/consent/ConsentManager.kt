package com.phonecam.streamer.consent

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

private const val TAG = "ConsentManager"

/**
 * Gates ad requests behind Google's User Messaging Platform (UMP) consent
 * flow, required for EEA/UK users under GDPR before any ad — including a
 * rewarded one — can be requested. This must run, and reach a decision,
 * before [com.phonecam.streamer.rewards.AdMobAdController.initialize] is
 * called; requesting ads first and asking forgiveness later is not
 * compliant.
 *
 * Users outside the EEA/UK: `requestConsentInfoUpdate` determines that
 * quickly and `canRequestAds()` returns true without ever showing a form, so
 * this adds no visible friction for them.
 */
class ConsentManager(private val activity: Activity) {

    /**
     * Requests the current consent status and, if the user is in a region
     * where a form is legally required and hasn't been shown yet, displays
     * it. [onReady] fires exactly once, after the form (if any) has been
     * resolved one way or another — check [canRequestAds] inside it before
     * initializing the ad SDK.
     *
     * Deliberately calls [onReady] even on failure: a transient network
     * error fetching consent status must not permanently block the whole
     * app from ever showing its core "watch ad" feature. [canRequestAds]
     * will correctly report false in that case, so ads simply stay off
     * until a later app launch resolves it.
     */
    fun requestConsentAndThen(onReady: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "consent form error (${formError.errorCode}): ${formError.message}")
                    }
                    onReady()
                }
            },
            { requestConsentError ->
                Log.w(TAG, "consent info update failed: ${requestConsentError.message}")
                onReady()
            },
        )
    }

    /** Must be true before [com.phonecam.streamer.rewards.AdController.preload] is ever called. */
    fun canRequestAds(): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
}
