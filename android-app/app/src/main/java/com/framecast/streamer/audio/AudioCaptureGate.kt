package com.framecast.streamer.audio

/**
 * Decides whether capture may start, and whether what started is what was asked
 * for.
 *
 * One rule: never record one microphone while the user believes it is another.
 * Every SCO call in [AudioCapture] is wrapped in a catch — it has to be, since
 * they throw on devices without the permission and on devices without the
 * hardware — and that catch previously turned "I cannot use your Bluetooth mic"
 * into "recording from the phone, saying nothing". Refusing loudly is the right
 * outcome: a session that sounds fine and came from the wrong device is worse
 * than one that did not start.
 *
 * Android-free so the decisions are unit tested rather than reasoned about;
 * [AudioCapture] supplies the facts.
 */

/** What the app is actually allowed and able to do right now. */
data class AudioPermissions(
    /** MODIFY_AUDIO_SETTINGS — required by every SCO/communication-device call. */
    val modifyAudioSettings: Boolean,
    /** BLUETOOTH_CONNECT — required from Android 12 to route to Bluetooth audio. */
    val bluetoothConnect: Boolean,
    /** A Bluetooth voice link is up and its microphone is enumerable. */
    val bluetoothLinkUp: Boolean,
)

enum class CaptureFailure {
    MISSING_MODIFY_AUDIO_SETTINGS,
    MISSING_BLUETOOTH_CONNECT,
    BLUETOOTH_LINK_NOT_UP,
    /** Capture started, but on a different device than the one requested. */
    ROUTED_TO_DIFFERENT_DEVICE,
    /** The microphone could not be opened at all: no permission, or in use. */
    MIC_UNAVAILABLE,
}

sealed class CaptureDecision {
    object Proceed : CaptureDecision()
    data class Refuse(val failure: CaptureFailure) : CaptureDecision()
}

object AudioCaptureGate {

    private val BLUETOOTH_KINDS = setOf(MicKind.BLUETOOTH_SCO, MicKind.BLUETOOTH_LE)

    /**
     * Checked before opening AudioRecord. Only Bluetooth has prerequisites —
     * built-in, wired and USB inputs need nothing beyond RECORD_AUDIO, and
     * demanding Bluetooth permissions for them would be a permission prompt
     * with no purpose.
     */
    fun beforeStart(mic: MicInput?, permissions: AudioPermissions): CaptureDecision {
        if (mic == null || mic.kind !in BLUETOOTH_KINDS) return CaptureDecision.Proceed

        if (!permissions.modifyAudioSettings) {
            return CaptureDecision.Refuse(CaptureFailure.MISSING_MODIFY_AUDIO_SETTINGS)
        }
        if (!permissions.bluetoothConnect) {
            return CaptureDecision.Refuse(CaptureFailure.MISSING_BLUETOOTH_CONNECT)
        }
        if (!permissions.bluetoothLinkUp) {
            // The microphone side of a Bluetooth headset does not exist until
            // the voice link is connected. Opening AudioRecord now gets the
            // built-in mic and keeps it for the whole session.
            return CaptureDecision.Refuse(CaptureFailure.BLUETOOTH_LINK_NOT_UP)
        }
        return CaptureDecision.Proceed
    }

    /**
     * Checked once recording has started and routing has resolved.
     *
     * A null [routedDeviceId] is not evidence of misrouting: getRoutedDevice()
     * returns null on plenty of devices even when routing worked, and refusing
     * on no evidence would break working setups to guard against a guess.
     */
    fun afterStart(mic: MicInput?, routedDeviceId: Int?): CaptureDecision {
        if (mic == null || routedDeviceId == null) return CaptureDecision.Proceed
        if (routedDeviceId != mic.id) {
            return CaptureDecision.Refuse(CaptureFailure.ROUTED_TO_DIFFERENT_DEVICE)
        }
        return CaptureDecision.Proceed
    }
}
