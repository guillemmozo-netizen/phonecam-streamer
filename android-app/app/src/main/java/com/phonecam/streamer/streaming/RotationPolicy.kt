package com.phonecam.streamer.streaming

import android.view.Surface

/**
 * Orientation maths for the capture pipeline, kept pure so it can be tested.
 *
 * This lived inline in MainActivity with no tests, which is how three separate
 * rotation bugs shipped together: a ViewPort expressing its aspect ratio in the
 * wrong orientation (a 1080p session delivered 1080x608), a Preview
 * targetRotation that PreviewView ignores, and a VideoCapture reference that
 * left the stream rotated in OBS while the viewfinder looked correct.
 */
object RotationPolicy {

    /**
     * targetRotation for a landscape output.
     *
     * targetRotation is expressed relative to the device's *natural*
     * orientation, which on a phone is portrait. Used directly, CameraX reports
     * rotationDegrees=90 - "stand this upright in portrait" - and the GL
     * renderer turns the landscape frame on its side. Shifting the reference by
     * one quarter turn makes "phone held upright" mean "landscape output, no
     * rotation", while keeping physical-rotation tracking intact: turn the
     * phone and the offset turns with it.
     */
    fun landscapeTargetRotation(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_0 -> Surface.ROTATION_90
        Surface.ROTATION_90 -> Surface.ROTATION_180
        Surface.ROTATION_180 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }

    /** Degrees for a Surface.ROTATION_* constant. */
    fun degreesFor(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Clockwise rotation the renderer must apply for a back-facing sensor,
     * mirroring CameraX's own relative-rotation maths. The Camera2 backend has
     * no TransformationInfo to read this from, so it computes it here.
     */
    fun sensorRotationDegrees(sensorOrientation: Int, surfaceRotation: Int): Int =
        ((sensorOrientation - degreesFor(surfaceRotation)) % 360 + 360) % 360

    /**
     * Which orientation bucket a raw accelerometer reading falls into.
     *
     * Extracted from the OrientationEventListener so the boundaries are
     * testable: they are off-by-one prone, and a wrong bucket silently rotates
     * the recording.
     */
    fun bucketFor(orientationDegrees: Int): Int = when (orientationDegrees) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}

/**
 * Decides when a raw accelerometer reading has held still long enough to act on.
 *
 * Raw OrientationEventListener readings are noisy right around each
 * 45/135/225/315 boundary - accelerometer jitter, even from a steady hand,
 * flips the raw angle back and forth across it. Applying every reading pushed a
 * fresh TransformationInfo through CameraX on each flip and visibly glitched
 * the streamed orientation, so a candidate has to hold for [debounceMs] first:
 * a deliberate quarter turn easily does, a boundary blip doesn't.
 *
 * This lived inline in MainActivity's listener, where it compared the new
 * *device bucket* against VideoCapture.targetRotation. Those stopped being the
 * same quantity when targetRotation gained its quarter-turn landscape offset
 * (see [RotationPolicy.landscapeTargetRotation]), and the mismatch produced two
 * bugs at once: turning the phone one bucket anticlockwise was silently
 * dropped, because the new bucket happened to equal the offset already stored
 * in targetRotation, and a phone lying perfectly still re-applied its rotation
 * every [debounceMs] forever, because the guard could never match otherwise.
 * Tracking the last applied bucket here keeps the comparison inside one space.
 *
 * Not thread-safe: the listener delivers on the main thread only.
 */
class RotationDebouncer(private val debounceMs: Long) {

    private var appliedBucket: Int? = null
    private var pendingBucket: Int? = null
    private var pendingSinceMs = 0L

    /**
     * Feeds one reading. Returns the Surface.ROTATION_* bucket that should now
     * be applied, or null when nothing has changed yet - which is the common
     * case, since readings arrive many times a second.
     */
    fun onOrientationChanged(orientationDegrees: Int, nowMs: Long): Int? {
        // OrientationEventListener.ORIENTATION_UNKNOWN (-1), reported when the
        // phone is flat. bucketFor would call that portrait and turn the stream.
        if (orientationDegrees < 0) return null

        val bucket = RotationPolicy.bucketFor(orientationDegrees)
        if (bucket == appliedBucket) {
            pendingBucket = null
            return null
        }
        if (bucket != pendingBucket) {
            pendingBucket = bucket
            pendingSinceMs = nowMs
            return null
        }
        if (nowMs - pendingSinceMs < debounceMs) return null

        pendingBucket = null
        appliedBucket = bucket
        return bucket
    }

    /**
     * Forgets what was applied, so the next stable reading is pushed even if it
     * matches. Needed whenever something else takes ownership of the rotation -
     * a camera rebind starts from the use case's own default, not from here.
     */
    fun reset() {
        appliedBucket = null
        pendingBucket = null
    }
}
