package com.phonecam.streamer.rewards

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private const val TAG = "AdMobAdController"

/**
 * Real rewarded-ads integration. Ad unit ID defaults to GOOGLE'S PUBLIC TEST
 * UNIT (ca-app-pub-3940256099942544/5224354917) so the whole "watch ad ->
 * get premium" loop is demonstrable before you have your own AdMob account.
 * Test ads always fill, always show a "Test Ad" banner, and are always safe
 * to click/watch — production ad units are NOT.
 *
 * Before shipping a real build:
 *  1. Create an AdMob account and app at https://admob.google.com (you do
 *     this yourself — an app account is not something this codebase can
 *     create for you).
 *  2. Replace both the AdMob App ID in AndroidManifest.xml and
 *     [rewardedAdUnitId] below with your real IDs.
 *  3. Never click/watch your own production ads while testing — AdMob bans
 *     accounts for invalid traffic. Use test ad unit IDs (or your app's ID
 *     added as a test device) for all local development.
 * See docs/ADS_SETUP.md for the full walkthrough.
 */
class AdMobAdController(
    private val context: Context,
    private val rewardedAdUnitId: String = "ca-app-pub-3940256099942544/5224354917",
) : AdController {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun initialize(onInitialized: () -> Unit = {}) {
        MobileAds.initialize(context) {
            Log.i(TAG, "AdMob SDK initialized")
            onInitialized()
        }
    }

    override fun isReady(): Boolean = rewardedAd != null

    override fun preload() {
        if (isLoading || rewardedAd != null) return
        isLoading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            context,
            rewardedAdUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                    Log.i(TAG, "rewarded ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    Log.w(TAG, "rewarded ad failed to load: ${error.message}")
                }
            },
        )
    }

    override fun showRewardedAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onFailedToShow: (reason: String) -> Unit,
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onFailedToShow("No ad loaded yet — try again in a moment")
            preload()
            return
        }

        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                preload() // start loading the next one immediately
                if (!rewardEarned) onDismissedWithoutReward()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                preload()
                onFailedToShow(error.message)
            }
        }

        ad.show(activity) { rewardItem ->
            // This callback firing IS the "watched to completion" signal —
            // closing the ad early never reaches here, so RewardManager only
            // ever gets credited for a genuinely completed view.
            rewardEarned = true
            Log.i(TAG, "reward earned: ${rewardItem.amount} ${rewardItem.type}")
            onRewardEarned()
        }
    }
}
