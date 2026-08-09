package com.framecast.streamer.streaming

/**
 * Which foreground-service types the streaming service may declare.
 *
 * From Android 14, `startForeground` throws `SecurityException` when a declared
 * type has no matching runtime permission — so declaring `microphone` on a
 * device where the user denied RECORD_AUDIO kills the service at the exact
 * moment they tap Start. Declaring only what is actually granted is the fix,
 * and it is a decision rather than a lookup, which is why it lives here with
 * tests rather than inline in the service.
 *
 * Android-free so it can be unit tested; [StreamingService] maps these onto
 * `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`.
 */
enum class CaptureKind { CAMERA, MICROPHONE }

object ForegroundTypePolicy {

    fun typesFor(
        audioEnabled: Boolean,
        cameraGranted: Boolean,
        micGranted: Boolean,
    ): Set<CaptureKind> = buildSet {
        if (cameraGranted) add(CaptureKind.CAMERA)
        // Audio being off is as good a reason to omit the type as the
        // permission being denied: declaring a capability the session will
        // never use invites the same rejection for no benefit.
        if (audioEnabled && micGranted) add(CaptureKind.MICROPHONE)
    }

    /**
     * With no types at all there is nothing to run in the foreground, and
     * calling startForeground with type 0 for a capture use is itself a
     * violation. The caller should not start the service.
     */
    fun canStart(types: Set<CaptureKind>): Boolean = types.isNotEmpty()
}
