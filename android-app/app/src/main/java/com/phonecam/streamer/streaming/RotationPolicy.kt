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
