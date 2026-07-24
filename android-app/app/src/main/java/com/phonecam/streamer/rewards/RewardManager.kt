package com.phonecam.streamer.rewards

import org.json.JSONObject

/**
 * Rewarded-ads credit engine.
 *
 * This is a 1:1 port of the reference implementation and its test suite at
 * reward_engine/reward_manager.py (see that file's docstring for the design
 * rationale: watching a full batch of [RewardConfig.adsPerReward] ads adds
 * one reward's worth of real time on top of whatever premium expiry is
 * already in effect — not a balance that only drains while streaming, and
 * not one hour per single ad). Any behavioral change here should be made in
 * the Python reference first, where it can be tested without a device/emulator,
 * then mirrored here.
 */
data class RewardConfig(
    // Alpha/Beta rate: 3 ads = 12h of Pro (was 1h) — see AppPhase's doc for
    // why there's no paid tier at all right now, making the ad reward the
    // only way in and worth making generous.
    val secondsPerReward: Double = 12 * 3600.0,
    val adsPerReward: Int = 3,
    // Kept at 8x a single reward, same ratio as before the 1h->12h bump
    // (was 8x 1h = 8h), so "how many batches can stack" is unchanged even
    // though each batch is now worth much more.
    val maxBalanceSeconds: Double = 8 * (12 * 3600.0),
    val freeQuality: String = "1080p60",
    val premiumQuality: String = "4k60",
) {
    init {
        require(secondsPerReward > 0) { "secondsPerReward must be positive" }
        require(adsPerReward > 0) { "adsPerReward must be positive" }
        require(maxBalanceSeconds >= secondsPerReward) { "maxBalanceSeconds must be >= secondsPerReward" }
    }
}

data class StreamProfile(
    val quality: String,
    val watermark: Boolean,
    val adsEnabled: Boolean,
    val premiumActive: Boolean,
    val balanceSeconds: Double,
)

class RewardManager(
    val config: RewardConfig = RewardConfig(),
    premiumExpiresAt: Double = 0.0,
    adsWatchedInBatch: Int = 0,
) {
    init {
        require(premiumExpiresAt >= 0) { "premiumExpiresAt cannot be negative" }
        require(adsWatchedInBatch in 0 until config.adsPerReward) { "adsWatchedInBatch must be in [0, adsPerReward)" }
    }

    /** Epoch millis (as a Double, matching the Python reference's epoch seconds*1000) when premium runs out. 0 = never earned. */
    var premiumExpiresAt: Double = premiumExpiresAt
        private set

    var adsWatchedInBatch: Int = adsWatchedInBatch
        private set

    fun balanceSeconds(nowMs: Double = System.currentTimeMillis().toDouble()): Double =
        maxOf(0.0, (premiumExpiresAt - nowMs) / 1000.0)

    /**
     * Record one completed rewarded ad. Returns true iff this ad completed a
     * batch of [RewardConfig.adsPerReward] and extended the premium expiry.
     *
     * The extension stacks on whichever is later — "now" or the current
     * expiry — so watching another batch while premium is still active adds
     * a full extra hour on top instead of overwriting the remaining time.
     */
    fun creditAdWatch(nowMs: Double = System.currentTimeMillis().toDouble()): Boolean {
        adsWatchedInBatch += 1
        if (adsWatchedInBatch < config.adsPerReward) return false

        adsWatchedInBatch = 0
        val base = maxOf(nowMs, premiumExpiresAt)
        val extended = base + config.secondsPerReward * 1000.0
        val cap = nowMs + config.maxBalanceSeconds * 1000.0
        premiumExpiresAt = minOf(extended, cap)
        return true
    }

    fun isPremiumActive(nowMs: Double = System.currentTimeMillis().toDouble()): Boolean =
        premiumExpiresAt > nowMs

    fun currentProfile(nowMs: Double = System.currentTimeMillis().toDouble()): StreamProfile {
        val premium = isPremiumActive(nowMs)
        return StreamProfile(
            quality = if (premium) config.premiumQuality else config.freeQuality,
            watermark = !premium,
            adsEnabled = !premium,
            premiumActive = premium,
            balanceSeconds = balanceSeconds(nowMs),
        )
    }

    /** Serialize for persistence (SharedPreferences). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("premium_expires_at", premiumExpiresAt)
        put("ads_watched_in_batch", adsWatchedInBatch)
        put(
            "config",
            JSONObject().apply {
                put("seconds_per_reward", config.secondsPerReward)
                put("ads_per_reward", config.adsPerReward)
                put("max_balance_seconds", config.maxBalanceSeconds)
                put("free_quality", config.freeQuality)
                put("premium_quality", config.premiumQuality)
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): RewardManager {
            val cfgJson = json.optJSONObject("config")
            val defaults = RewardConfig()
            val config = RewardConfig(
                secondsPerReward = cfgJson?.optDouble("seconds_per_reward", defaults.secondsPerReward) ?: defaults.secondsPerReward,
                adsPerReward = cfgJson?.optInt("ads_per_reward", defaults.adsPerReward) ?: defaults.adsPerReward,
                maxBalanceSeconds = cfgJson?.optDouble("max_balance_seconds", defaults.maxBalanceSeconds) ?: defaults.maxBalanceSeconds,
                freeQuality = cfgJson?.optString("free_quality", defaults.freeQuality) ?: defaults.freeQuality,
                premiumQuality = cfgJson?.optString("premium_quality", defaults.premiumQuality) ?: defaults.premiumQuality,
            )
            // A corrupted/edited save file shouldn't crash the app on load.
            val adsWatched = json.optInt("ads_watched_in_batch", 0).let {
                if (it in 0 until config.adsPerReward) it else 0
            }
            return RewardManager(config, json.optDouble("premium_expires_at", 0.0), adsWatched)
        }
    }
}
