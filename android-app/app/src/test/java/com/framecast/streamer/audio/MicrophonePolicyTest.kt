package com.framecast.streamer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePolicyTest {

    private fun mic(
        kind: MicKind,
        rates: List<Int> = emptyList(),
        channels: List<Int> = emptyList(),
    ) = MicInput(
        id = 1,
        kind = kind,
        productName = kind.name,
        declaredSampleRates = rates,
        declaredChannelCounts = channels,
    )

    // ---- sample rate ----

    @Test
    fun `a supported rate is used as-is and warns about nothing`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BUILTIN, rates = listOf(48_000, 44_100)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 128_000,
        )
        assertEquals(48_000, plan.sampleRate)
        assertTrue(plan.warnings.isEmpty())
        assertFalse(plan.isDowngradedFromRequest)
    }

    @Test
    fun `an unsupported rate falls back to the highest one at or below it`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(44_100, 32_000, 16_000)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 128_000,
        )
        assertEquals(44_100, plan.sampleRate)
        assertTrue(AudioWarning.SAMPLE_RATE_UNSUPPORTED in plan.warnings)
    }

    @Test
    fun `a request below everything on offer takes the lowest available, not silence`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(96_000, 48_000)),
            requestedSampleRate = 8_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 64_000,
        )
        // Nothing at or below 8 kHz exists, so it takes the top of the list
        // rather than returning a rate the device cannot open.
        assertEquals(96_000, plan.sampleRate)
        assertTrue(AudioWarning.SAMPLE_RATE_UNSUPPORTED in plan.warnings)
    }

    @Test
    fun `a device that declares nothing is flagged rather than guessed at`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BUILTIN),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 128_000,
        )
        assertEquals(48_000, plan.sampleRate)
        assertTrue(AudioWarning.CAPABILITIES_UNKNOWN in plan.warnings)
    }

    @Test
    fun `a probe overrides what the device declared`() {
        // Declares 48k, but opening it only ever succeeded at 16k — the probe
        // is the evidence that survives a device reporting the mixer's rate.
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(48_000)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 128_000,
            probedSampleRates = listOf(16_000),
        )
        assertEquals(16_000, plan.sampleRate)
        assertTrue(AudioWarning.SAMPLE_RATE_UNSUPPORTED in plan.warnings)
    }

    // ---- bluetooth ----

    @Test
    fun `a bluetooth mic is capped at 16k however high it claims to go`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_SCO, rates = listOf(48_000, 44_100)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 192_000,
        )
        assertEquals(16_000, plan.sampleRate)
        assertTrue(AudioWarning.BLUETOOTH_VOICE_QUALITY in plan.warnings)
        assertTrue(AudioWarning.SAMPLE_RATE_UNSUPPORTED in plan.warnings)
    }

    @Test
    fun `bluetooth warns even when the requested rate happens to be reachable`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_SCO, rates = listOf(16_000, 8_000)),
            requestedSampleRate = 16_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 64_000,
        )
        assertEquals(16_000, plan.sampleRate)
        // Nothing was downgraded, but the user still needs to know why this
        // sounds like a phone call.
        assertFalse(AudioWarning.SAMPLE_RATE_UNSUPPORTED in plan.warnings)
        assertTrue(AudioWarning.BLUETOOTH_VOICE_QUALITY in plan.warnings)
    }

    @Test
    fun `LE Audio is not clamped like SCO is`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_LE, rates = listOf(48_000)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 128_000,
        )
        assertEquals(48_000, plan.sampleRate)
        assertFalse(AudioWarning.BLUETOOTH_VOICE_QUALITY in plan.warnings)
    }

    // ---- bitrate ----

    @Test
    fun `max bitrate follows AAC-LC's own ceiling at low rates`() {
        // 8 kHz mono * 6 bits/sample = 48 kbps, nowhere near the 192 kbps the
        // settings screen offers.
        assertEquals(48_000, MicrophonePolicy.maxBitrateBps(8_000, 1))
        assertEquals(96_000, MicrophonePolicy.maxBitrateBps(16_000, 1))
    }

    @Test
    fun `max bitrate stops at the practical ceiling, not the spec's`() {
        // 48 kHz stereo would allow 576 kbps by spec; no encoder honours that.
        assertEquals(
            MicrophonePolicy.PRACTICAL_MAX_BITRATE_BPS,
            MicrophonePolicy.maxBitrateBps(48_000, 2),
        )
    }

    @Test
    fun `a bitrate above the ceiling is clamped and reported`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_SCO, rates = listOf(16_000)),
            requestedSampleRate = 16_000,
            requestedChannelCount = 1,
            requestedBitrateBps = 192_000,
        )
        assertEquals(96_000, plan.bitrateBps)
        assertEquals(96_000, plan.maxBitrateBps)
        assertTrue(AudioWarning.BITRATE_ABOVE_MAX in plan.warnings)
    }

    @Test
    fun `a bitrate within the ceiling is left alone`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(48_000), channels = listOf(1, 2)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 192_000,
        )
        assertEquals(192_000, plan.bitrateBps)
        assertFalse(AudioWarning.BITRATE_ABOVE_MAX in plan.warnings)
    }

    // ---- channels ----

    @Test
    fun `stereo from a mono-only input falls back to mono and says so`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BUILTIN, rates = listOf(48_000), channels = listOf(1)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 128_000,
        )
        assertEquals(1, plan.channelCount)
        assertTrue(AudioWarning.CHANNELS_UNSUPPORTED in plan.warnings)
    }

    @Test
    fun `stereo from a stereo USB interface is kept`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(48_000), channels = listOf(1, 2)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 256_000,
        )
        assertEquals(2, plan.channelCount)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `an unknown channel count is not second-guessed`() {
        // Nothing declared and nothing probed: assume the request works rather
        // than downgrading on no evidence.
        val plan = MicrophonePolicy.plan(
            mic(MicKind.USB, rates = listOf(48_000)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 128_000,
        )
        assertEquals(2, plan.channelCount)
        assertFalse(AudioWarning.CHANNELS_UNSUPPORTED in plan.warnings)
    }

    // ---- warnings ----

    @Test
    fun `warnings are never repeated`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_SCO, rates = listOf(44_100, 48_000)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 320_000,
        )
        assertEquals(plan.warnings.size, plan.warnings.distinct().size)
    }

    @Test
    fun `a bluetooth mic asked for studio settings reports every reason at once`() {
        val plan = MicrophonePolicy.plan(
            mic(MicKind.BLUETOOTH_SCO, rates = listOf(16_000), channels = listOf(1)),
            requestedSampleRate = 48_000,
            requestedChannelCount = 2,
            requestedBitrateBps = 320_000,
        )
        assertEquals(16_000, plan.sampleRate)
        assertEquals(1, plan.channelCount)
        assertEquals(96_000, plan.bitrateBps)
        assertTrue(
            plan.warnings.containsAll(
                listOf(
                    AudioWarning.SAMPLE_RATE_UNSUPPORTED,
                    AudioWarning.BLUETOOTH_VOICE_QUALITY,
                    AudioWarning.CHANNELS_UNSUPPORTED,
                    AudioWarning.BITRATE_ABOVE_MAX,
                )
            )
        )
    }
}
