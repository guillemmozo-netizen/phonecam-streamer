package com.phonecam.streamer.rewards

import android.app.Activity

/**
 * Abstraction over "show a rewarded ad and tell me if the user actually
 * finished it." [RewardManager] never talks to an ad SDK directly, so the
 * ad provider can be swapped (AdMob today, another network later) or faked
 * in tests without touching the credit economy.
 */
interface AdController {
    /** True once an ad is loaded and ready to show immediately. */
    fun isReady(): Boolean

    /** Pre-load the next rewarded ad so [showRewardedAd] doesn't stall on tap. */
    fun preload()

    /**
     * Shows the rewarded ad. [onRewardEarned] fires only if the user watched
     * it to completion (mirrors AdMob's OnUserEarnedRewardListener contract) —
     * closing early must not credit anything.
     */
    fun showRewardedAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onFailedToShow: (reason: String) -> Unit = {},
    )
}

/**
 * Placeholder used until the real AdMob rewarded-ad integration (ad unit ID,
 * consent/UMP flow, mediation) is wired up. Always reports "not ready" so
 * the UI correctly shows a disabled/loading state instead of silently
 * granting free credit.
 */
class StubAdController : AdController {
    override fun isReady(): Boolean = false

    override fun preload() {
        // TODO: RewardedAd.load(context, adUnitId, adRequest, callback)
    }

    override fun showRewardedAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onFailedToShow: (reason: String) -> Unit,
    ) {
        onFailedToShow("Ad SDK not yet integrated (alpha build)")
    }
}
