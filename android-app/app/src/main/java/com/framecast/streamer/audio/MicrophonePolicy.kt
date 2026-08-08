package com.framecast.streamer.audio

/**
 * Decides what a given microphone can actually do, and what to warn the user
 * about when their settings ask for more than it can deliver.
 *
 * Deliberately free of Android types — no AudioDeviceInfo, no AudioRecord — so
 * the whole policy is unit tested on the JVM, the same way ConnectionSupervisor
 * is. [AudioDeviceInventory] is the thin adapter that turns the platform's view
 * of a device into the [MicInput] this file reasons about.
 *
 * The reason this is policy at all, rather than "just use what the user picked":
 * the sample rate a microphone reports and the sample rate it will actually
 * open at are different questions, and for Bluetooth they are dramatically
 * different. A user who picks 48 kHz and a Bluetooth headset gets 16 kHz at
 * best and has no way to know why their audio sounds like a phone call unless
 * something tells them.
 */

/** Where a microphone physically is. Drives the warnings, not just the label. */
enum class MicKind {
    BUILTIN,
    WIRED_HEADSET,
    /** USB-C microphone, interface or capture dongle — anything on the USB bus. */
    USB,
    /** Classic Bluetooth voice link (SCO/HFP). Hard-capped at narrow/wide band. */
    BLUETOOTH_SCO,
    /** LE Audio (Android 13+). Better than SCO, still not full band on most hardware. */
    BLUETOOTH_LE,
    OTHER,
}

/**
 * One selectable input.
 *
 * [declaredSampleRates] and [declaredChannelCounts] come straight from the
 * platform and are frequently **empty**, which does not mean "supports
 * nothing" — it means the device did not say, and only opening it for real
 * answers the question. That distinction is why [MicrophonePolicy.plan] takes
 * probe results separately instead of merging the two upstream.
 */
data class MicInput(
    val id: Int,
    val kind: MicKind,
    val productName: String,
    val declaredSampleRates: List<Int> = emptyList(),
    val declaredChannelCounts: List<Int> = emptyList(),
)

/** Something the user should be told before they wonder why audio sounds wrong. */
enum class AudioWarning {
    /** The requested rate isn't available; the plan fell back to another one. */
    SAMPLE_RATE_UNSUPPORTED,
    /** A Bluetooth voice link is in use, which caps quality regardless of settings. */
    BLUETOOTH_VOICE_QUALITY,
    /** The requested bitrate is above what this rate/channel combination can carry. */
    BITRATE_ABOVE_MAX,
    /** Stereo was requested from a mono-only input. */
    CHANNELS_UNSUPPORTED,
    /** Neither the platform nor a probe could establish what this device supports. */
    CAPABILITIES_UNKNOWN,
}

/** What the capture pipeline should actually be configured with. */
data class AudioPlan(
    val sampleRate: Int,
    val channelCount: Int,
    val bitrateBps: Int,
    /** Ceiling for this rate/channel combination — what the UI shows as "max". */
    val maxBitrateBps: Int,
    val warnings: List<AudioWarning>,
) {
    val isDowngradedFromRequest: Boolean
        get() = warnings.isNotEmpty()
}

object MicrophonePolicy {

    /**
     * Rates worth trying when a device declares nothing. Ordered most- to
     * least-preferred; 48 kHz first because it is what the AAC encoder, OBS and
     * essentially every PC audio path already run at, so it is the one rate
     * that needs no resampling anywhere in the chain.
     */
    val CANDIDATE_SAMPLE_RATES = listOf(48_000, 44_100, 32_000, 16_000, 8_000)

    const val DEFAULT_SAMPLE_RATE = 48_000

    /**
     * Classic Bluetooth (SCO/HFP) carries voice, not audio: 8 kHz narrowband,
     * 16 kHz with wideband speech (mSBC), and nothing above. No setting can
     * raise it, which is exactly why picking a Bluetooth mic warns rather than
     * silently sounding worse.
     */
    const val BLUETOOTH_SCO_MAX_SAMPLE_RATE = 16_000

    /**
     * AAC-LC's own ceiling: 6144 bits per channel per 1024-sample frame, i.e.
     * 6 bits per sample per channel. Asking a codec for more than this is not a
     * quality choice, it is a misconfiguration — and at Bluetooth's 8 kHz mono
     * it puts the real ceiling at 48 kbps, far below the 192 kbps the settings
     * screen offers.
     */
    const val AAC_LC_BITS_PER_SAMPLE_PER_CHANNEL = 6

    /**
     * Above this, AAC-LC stops buying audible quality and encoders start
     * refusing the configuration outright. Separate from the spec ceiling
     * because at 48 kHz stereo the spec would allow 576 kbps, which no encoder
     * will usefully honour.
     */
    const val PRACTICAL_MAX_BITRATE_BPS = 320_000

    /** Highest bitrate worth configuring AAC-LC with at this rate and channel count. */
    fun maxBitrateBps(sampleRate: Int, channelCount: Int): Int =
        minOf(
            sampleRate * channelCount * AAC_LC_BITS_PER_SAMPLE_PER_CHANNEL,
            PRACTICAL_MAX_BITRATE_BPS,
        )

    /**
     * Rates this input can be assumed to support.
     *
     * [probedSampleRates] wins over what the device declared: it is the result
     * of actually opening the input, so it is the only evidence that survives
     * a device lying or saying nothing. Bluetooth is clamped on top of either,
     * because an SCO link that claims 44.1 kHz is reporting the mixer's rate,
     * not the radio's.
     */
    fun supportedSampleRates(mic: MicInput, probedSampleRates: List<Int> = emptyList()): List<Int> {
        val known = when {
            probedSampleRates.isNotEmpty() -> probedSampleRates
            mic.declaredSampleRates.isNotEmpty() -> mic.declaredSampleRates
            else -> emptyList()
        }.distinct().sortedDescending()

        if (mic.kind != MicKind.BLUETOOTH_SCO) return known
        val capped = known.filter { it <= BLUETOOTH_SCO_MAX_SAMPLE_RATE }
        // A device that only reported rates above the SCO ceiling has told us
        // nothing usable — fall back to the ceiling itself rather than to an
        // empty list, which would read as "unknown" further down.
        return capped.ifEmpty { listOf(BLUETOOTH_SCO_MAX_SAMPLE_RATE) }
    }

    /**
     * Turns a request into something the pipeline can actually be configured
     * with, plus everything the user should be warned about.
     *
     * Falls back downwards, never upwards: a device that can't do 48 kHz gets
     * the highest rate it can do, because resampling up to a rate the hardware
     * never captured adds no information and costs CPU.
     */
    fun plan(
        mic: MicInput,
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        requestedBitrateBps: Int,
        probedSampleRates: List<Int> = emptyList(),
        probedChannelCounts: List<Int> = emptyList(),
    ): AudioPlan {
        val warnings = mutableListOf<AudioWarning>()

        val supportedRates = supportedSampleRates(mic, probedSampleRates)
        val sampleRate = when {
            supportedRates.isEmpty() -> {
                warnings += AudioWarning.CAPABILITIES_UNKNOWN
                requestedSampleRate
            }
            requestedSampleRate in supportedRates -> requestedSampleRate
            else -> {
                warnings += AudioWarning.SAMPLE_RATE_UNSUPPORTED
                // Highest available at or below the request, else the highest
                // there is (the request was below everything on offer).
                supportedRates.firstOrNull { it <= requestedSampleRate } ?: supportedRates.first()
            }
        }

        if (mic.kind == MicKind.BLUETOOTH_SCO) warnings += AudioWarning.BLUETOOTH_VOICE_QUALITY

        val channels = when {
            requestedChannelCount <= 1 -> requestedChannelCount.coerceAtLeast(1)
            else -> {
                val supportedChannels = probedChannelCounts.ifEmpty { mic.declaredChannelCounts }
                if (supportedChannels.isEmpty() || requestedChannelCount in supportedChannels) {
                    requestedChannelCount
                } else {
                    warnings += AudioWarning.CHANNELS_UNSUPPORTED
                    supportedChannels.filter { it <= requestedChannelCount }.maxOrNull() ?: 1
                }
            }
        }

        val maxBitrate = maxBitrateBps(sampleRate, channels)
        val bitrate = if (requestedBitrateBps > maxBitrate) {
            warnings += AudioWarning.BITRATE_ABOVE_MAX
            maxBitrate
        } else {
            requestedBitrateBps
        }

        return AudioPlan(
            sampleRate = sampleRate,
            channelCount = channels,
            bitrateBps = bitrate,
            maxBitrateBps = maxBitrate,
            // distinct() because a Bluetooth input can reach the bitrate clamp
            // through its own rate cap, and reporting the same warning twice
            // would show the user a duplicated line.
            warnings = warnings.distinct(),
        )
    }
}
