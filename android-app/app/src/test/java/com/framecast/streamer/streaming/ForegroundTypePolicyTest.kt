package com.framecast.streamer.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 14 rejects startForeground when a declared type has no matching
 * runtime permission — a SecurityException that kills the service at the exact
 * moment the user taps Start. Choosing the types from what is actually granted
 * is what avoids it, so that choice is worth testing rather than inlining.
 */
class ForegroundTypePolicyTest {

    @Test
    fun `camera only when audio is off`() {
        assertEquals(
            setOf(CaptureKind.CAMERA),
            ForegroundTypePolicy.typesFor(audioEnabled = false, cameraGranted = true, micGranted = true),
        )
    }

    @Test
    fun `camera and microphone when audio is on and granted`() {
        assertEquals(
            setOf(CaptureKind.CAMERA, CaptureKind.MICROPHONE),
            ForegroundTypePolicy.typesFor(audioEnabled = true, cameraGranted = true, micGranted = true),
        )
    }

    @Test
    fun `microphone is not declared without the permission, even when audio is on`() {
        // Declaring it anyway is the SecurityException this exists to prevent.
        assertEquals(
            setOf(CaptureKind.CAMERA),
            ForegroundTypePolicy.typesFor(audioEnabled = true, cameraGranted = true, micGranted = false),
        )
    }

    @Test
    fun `camera is not declared without the permission`() {
        assertEquals(
            setOf(CaptureKind.MICROPHONE),
            ForegroundTypePolicy.typesFor(audioEnabled = true, cameraGranted = false, micGranted = true),
        )
    }

    @Test
    fun `no permissions means no types, and the caller must not start a service`() {
        val types = ForegroundTypePolicy.typesFor(
            audioEnabled = true, cameraGranted = false, micGranted = false,
        )
        assertEquals(emptySet<CaptureKind>(), types)
        assertEquals(false, ForegroundTypePolicy.canStart(types))
    }

    @Test
    fun `any granted type is enough to start`() {
        assertEquals(true, ForegroundTypePolicy.canStart(setOf(CaptureKind.CAMERA)))
    }
}
