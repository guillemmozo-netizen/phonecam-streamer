package com.framecast.streamer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule these encode: never record one microphone while telling the user it
 * is another. Refusing loudly is better than a session that sounds fine and
 * came from the wrong device.
 */
class AudioCaptureGateTest {

    private fun mic(kind: MicKind, id: Int = 7) =
        MicInput(id = id, kind = kind, productName = kind.name)

    private val allGranted = AudioPermissions(
        modifyAudioSettings = true,
        bluetoothConnect = true,
        bluetoothLinkUp = true,
    )

    // ---- before starting capture ----

    @Test
    fun `a built-in mic needs no bluetooth permissions at all`() {
        val decision = AudioCaptureGate.beforeStart(
            mic(MicKind.BUILTIN),
            AudioPermissions(modifyAudioSettings = false, bluetoothConnect = false, bluetoothLinkUp = false),
        )
        assertEquals(CaptureDecision.Proceed, decision)
    }

    @Test
    fun `a USB mic needs no bluetooth permissions either`() {
        val decision = AudioCaptureGate.beforeStart(
            mic(MicKind.USB),
            AudioPermissions(modifyAudioSettings = false, bluetoothConnect = false, bluetoothLinkUp = false),
        )
        assertEquals(CaptureDecision.Proceed, decision)
    }

    @Test
    fun `a bluetooth mic without MODIFY_AUDIO_SETTINGS is refused, not silently rerouted`() {
        val decision = AudioCaptureGate.beforeStart(
            mic(MicKind.BLUETOOTH_SCO),
            allGranted.copy(modifyAudioSettings = false),
        )
        assertEquals(CaptureDecision.Refuse(CaptureFailure.MISSING_MODIFY_AUDIO_SETTINGS), decision)
    }

    @Test
    fun `a bluetooth mic without BLUETOOTH_CONNECT is refused`() {
        val decision = AudioCaptureGate.beforeStart(
            mic(MicKind.BLUETOOTH_SCO),
            allGranted.copy(bluetoothConnect = false),
        )
        assertEquals(CaptureDecision.Refuse(CaptureFailure.MISSING_BLUETOOTH_CONNECT), decision)
    }

    @Test
    fun `a bluetooth mic whose voice link never came up is refused`() {
        val decision = AudioCaptureGate.beforeStart(
            mic(MicKind.BLUETOOTH_SCO),
            allGranted.copy(bluetoothLinkUp = false),
        )
        assertEquals(CaptureDecision.Refuse(CaptureFailure.BLUETOOTH_LINK_NOT_UP), decision)
    }

    @Test
    fun `a bluetooth mic with everything in place proceeds`() {
        assertEquals(
            CaptureDecision.Proceed,
            AudioCaptureGate.beforeStart(mic(MicKind.BLUETOOTH_SCO), allGranted),
        )
    }

    @Test
    fun `LE Audio is held to the same permission bar as SCO`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureFailure.MISSING_BLUETOOTH_CONNECT),
            AudioCaptureGate.beforeStart(mic(MicKind.BLUETOOTH_LE), allGranted.copy(bluetoothConnect = false)),
        )
    }

    @Test
    fun `no microphone selected means the default, which needs nothing`() {
        assertEquals(CaptureDecision.Proceed, AudioCaptureGate.beforeStart(null, allGranted))
    }

    // ---- after starting capture ----

    @Test
    fun `capture routed to the requested device is accepted`() {
        assertEquals(
            CaptureDecision.Proceed,
            AudioCaptureGate.afterStart(mic(MicKind.BLUETOOTH_SCO, id = 7), routedDeviceId = 7),
        )
    }

    @Test
    fun `capture routed somewhere else is refused rather than streamed`() {
        // The exact failure this whole gate exists for: the framework accepted
        // everything and quietly gave us the built-in mic.
        assertEquals(
            CaptureDecision.Refuse(CaptureFailure.ROUTED_TO_DIFFERENT_DEVICE),
            AudioCaptureGate.afterStart(mic(MicKind.BLUETOOTH_SCO, id = 7), routedDeviceId = 1),
        )
    }

    @Test
    fun `unknown routing is not treated as wrong routing`() {
        // getRoutedDevice() returns null on some devices even when routing
        // worked. Refusing on no evidence would break working setups.
        assertEquals(
            CaptureDecision.Proceed,
            AudioCaptureGate.afterStart(mic(MicKind.BLUETOOTH_SCO, id = 7), routedDeviceId = null),
        )
    }

    @Test
    fun `a wired mic routed elsewhere is refused too`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureFailure.ROUTED_TO_DIFFERENT_DEVICE),
            AudioCaptureGate.afterStart(mic(MicKind.USB, id = 3), routedDeviceId = 1),
        )
    }

    @Test
    fun `no requested device means any routing is correct`() {
        assertEquals(CaptureDecision.Proceed, AudioCaptureGate.afterStart(null, routedDeviceId = 1))
    }
}
