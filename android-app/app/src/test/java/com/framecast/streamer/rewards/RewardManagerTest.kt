package com.framecast.streamer.rewards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors reward_engine/tests/test_reward_manager.py case-for-case. That
 * Python suite is the one actually executed in CI/sandbox (no JDK available
 * there); this JVM copy exists so the same guarantees hold once this project
 * is opened in Android Studio with a real JDK.
 */
class RewardManagerTest {

    @Test
    fun `default state is free tier`() {
        val rm = RewardManager()
        val profile = rm.currentProfile(nowMs = 1_000_000.0)
        assertFalse(profile.premiumActive)
        assertTrue(profile.watermark)
        assertTrue(profile.adsEnabled)
        assertEquals("1080p60", profile.quality)
        assertEquals(0.0, profile.balanceSeconds, 0.0)
    }

    @Test
    fun `single ad does not grant premium`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 3))
        val completed = rm.creditAdWatch(nowMs = 1_000_000.0)
        assertFalse(completed)
        assertEquals(1, rm.adsWatchedInBatch)
        assertFalse(rm.isPremiumActive(nowMs = 1_000_000.0))
    }

    @Test
    fun `third ad completes batch and grants configured seconds`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 3, secondsPerReward = 3600.0))
        assertFalse(rm.creditAdWatch(nowMs = 1_000_000.0))
        assertFalse(rm.creditAdWatch(nowMs = 1_000_000.0))
        assertTrue(rm.creditAdWatch(nowMs = 1_000_000.0))
        assertEquals(0, rm.adsWatchedInBatch)
        assertEquals(3600.0, rm.balanceSeconds(nowMs = 1_000_000.0), 0.0)
        assertTrue(rm.isPremiumActive(nowMs = 1_000_000.0))
    }

    @Test
    fun `batch counter resets after completing a reward`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 3))
        repeat(3) { rm.creditAdWatch(nowMs = 1_000_000.0) }
        assertEquals(0, rm.adsWatchedInBatch)
        assertFalse(rm.creditAdWatch(nowMs = 1_000_000.0))
        assertEquals(1, rm.adsWatchedInBatch)
    }

    @Test
    fun `second batch stacks on top of still active premium`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 3, secondsPerReward = 3600.0))
        val now = 1_000_000.0
        repeat(3) { rm.creditAdWatch(nowMs = now) }
        assertEquals(3600_000.0 / 1000.0, rm.balanceSeconds(nowMs = now), 0.0)

        val later = now + 600_000.0 // 10 minutes later, in ms
        repeat(3) { rm.creditAdWatch(nowMs = later) }

        // extended from the still-active expiry, not from "later" — the second
        // hour stacks fully on top of the remaining 50 minutes
        assertEquals(3600.0 + 3000.0, rm.balanceSeconds(nowMs = later), 0.5)
    }

    @Test
    fun `second batch after premium expired starts from now`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 3, secondsPerReward = 3600.0))
        val now = 1_000_000.0
        repeat(3) { rm.creditAdWatch(nowMs = now) }

        val muchLater = now + 10_000_000.0
        repeat(3) { rm.creditAdWatch(nowMs = muchLater) }

        assertEquals(3600.0, rm.balanceSeconds(nowMs = muchLater), 0.0)
    }

    @Test
    fun `balance caps at max`() {
        val rm = RewardManager(
            RewardConfig(adsPerReward = 1, secondsPerReward = 3600.0, maxBalanceSeconds = 7200.0),
        )
        repeat(5) { rm.creditAdWatch(nowMs = 1_000_000.0) }
        assertEquals(7200.0, rm.balanceSeconds(nowMs = 1_000_000.0), 0.0)
    }

    @Test
    fun `balance counts down in real time without ticking`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 1, secondsPerReward = 3600.0))
        val now = 1_000_000.0
        rm.creditAdWatch(nowMs = now)
        assertEquals(3500.0, rm.balanceSeconds(nowMs = now + 100_000.0), 0.0)
        assertEquals(0.0, rm.balanceSeconds(nowMs = now + 3_600_000.0), 0.0)
        assertFalse(rm.isPremiumActive(nowMs = now + 3_600_000.0))
    }

    @Test
    fun `balance floors at zero and reverts to free profile`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 1, secondsPerReward = 10.0))
        val now = 1_000_000.0
        rm.creditAdWatch(nowMs = now)
        val profile = rm.currentProfile(nowMs = now + 100_000.0)
        assertFalse(profile.premiumActive)
        assertEquals("1080p60", profile.quality)
        assertEquals(0.0, profile.balanceSeconds, 0.0)
    }

    @Test
    fun `serialization round trip preserves state and config`() {
        val rm = RewardManager(RewardConfig(adsPerReward = 2, secondsPerReward = 1800.0))
        rm.creditAdWatch(nowMs = 1_000_000.0)
        val json = rm.toJson()

        val restored = RewardManager.fromJson(json)
        assertEquals(rm.premiumExpiresAt, restored.premiumExpiresAt, 0.0)
        assertEquals(rm.adsWatchedInBatch, restored.adsWatchedInBatch)
        assertEquals(rm.config, restored.config)
    }
}
