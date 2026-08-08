package com.framecast.streamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log

private const val TAG = "AudioDeviceInventory"

/**
 * The platform's view of which microphones exist, translated into the
 * Android-free [MicInput] that [MicrophonePolicy] reasons about.
 *
 * Split out from the policy so the interesting decisions — what to fall back
 * to, what to warn about — stay unit tested on the JVM, and this file stays
 * thin enough to be obviously correct by inspection.
 */
object AudioDeviceInventory {

    /**
     * Every input the user could pick, most-likely-wanted first.
     *
     * External microphones sort above the built-in one because someone who has
     * plugged in a USB interface or paired a lavalier almost certainly means to
     * use it.
     */
    fun inputs(context: Context): List<MicInput> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()

        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSink.not() }
            .map { it.toMicInput() }
            // Distinct by id: some devices enumerate the same physical mic once
            // per channel mask, which would show the user duplicate rows.
            .distinctBy { it.id }
            .sortedBy { it.kind.pickerOrder() }
    }

    /** The input the platform would use if nothing were selected. */
    fun defaultInput(context: Context): MicInput? =
        inputs(context).firstOrNull { it.kind == MicKind.BUILTIN } ?: inputs(context).firstOrNull()

    private fun MicKind.pickerOrder(): Int = when (this) {
        MicKind.USB -> 0
        MicKind.WIRED_HEADSET -> 1
        MicKind.BLUETOOTH_LE -> 2
        MicKind.BLUETOOTH_SCO -> 3
        MicKind.BUILTIN -> 4
        MicKind.OTHER -> 5
    }

    private fun AudioDeviceInfo.toMicInput(): MicInput = MicInput(
        id = id,
        kind = kindOf(type),
        productName = productName?.toString()?.trim().orEmpty().ifEmpty { kindOf(type).name },
        // Both of these are routinely empty. That is the platform saying "I
        // don't know", not "nothing is supported" — MicrophonePolicy treats the
        // two differently, so they are passed through as-is rather than being
        // defaulted here.
        declaredSampleRates = sampleRates.toList(),
        declaredChannelCounts = channelCounts.toList(),
    )

    private fun kindOf(type: Int): MicKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.BUILTIN
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicKind.BLUETOOTH_SCO
        else -> {
            // TYPE_BLE_HEADSET is API 31+; referencing the constant directly
            // would not compile against a lower minSdk and would crash on
            // older devices if it did.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) MicKind.BLUETOOTH_LE else MicKind.OTHER
        }
    }

    /**
     * Which of [MicrophonePolicy.CANDIDATE_SAMPLE_RATES] the audio system will
     * actually accept, established by asking it rather than by trusting
     * [MicInput.declaredSampleRates].
     *
     * Uses `getMinBufferSize`, which reports ERROR_BAD_VALUE for a combination
     * the framework cannot service and, unlike constructing an AudioRecord,
     * needs no RECORD_AUDIO permission — so the Settings screen can show real
     * numbers before the user has granted anything.
     *
     * Honest limitation: this probes the *framework's* capability for the
     * given format, not the specific device's, because a rate can only be
     * bound to a device by opening a real AudioRecord and routing it. It
     * therefore rules rates out reliably and rules them in optimistically;
     * [AudioCapture] reports what it actually got once recording starts, which
     * is the number the UI ultimately trusts.
     */
    fun probeSampleRates(channelCount: Int): List<Int> {
        val channelMask =
            if (channelCount >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        return MicrophonePolicy.CANDIDATE_SAMPLE_RATES.filter { rate ->
            val size = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            (size > 0).also {
                if (!it) Log.d(TAG, "sample rate $rate unavailable at $channelCount ch (min buffer $size)")
            }
        }
    }

    /** Channel counts worth offering for [mic], probed the same way. */
    fun probeChannelCounts(): List<Int> = listOf(1, 2).filter { channels ->
        val mask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        AudioRecord.getMinBufferSize(
            MicrophonePolicy.DEFAULT_SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT
        ) > 0
    }

    /**
     * The full picture for one input: what it says, what the framework accepts,
     * and what that means for the user's current settings.
     */
    fun planFor(
        mic: MicInput,
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        requestedBitrateBps: Int,
    ): AudioPlan = MicrophonePolicy.plan(
        mic = mic,
        requestedSampleRate = requestedSampleRate,
        requestedChannelCount = requestedChannelCount,
        requestedBitrateBps = requestedBitrateBps,
        probedSampleRates = probeSampleRates(requestedChannelCount),
        probedChannelCounts = probeChannelCounts(),
    )
}
