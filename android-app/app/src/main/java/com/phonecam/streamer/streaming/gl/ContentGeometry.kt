package com.phonecam.streamer.streaming.gl

/**
 * The pure geometry behind [EncoderSurfaceRenderer]'s draw: which vertex
 * scale keeps the camera content undistorted, and which texture coordinates
 * sample exactly the declared crop.
 *
 * Extracted to plain Kotlin so the arithmetic that used to be implicit (and
 * wrong) in GL state is unit-testable on the JVM. The defect this closes,
 * measured by simulating the old shader math: a full-surface quad rotated by
 * 90/270 with no compensating scale stretched every non-square composition —
 * 3.16x at 16:9, 1.78x at 4:3, 0.32x at 9:16 — while 180-degree holds and
 * matching aspects looked fine, which is exactly why the bug read as
 * "depends on orientation".
 */
object ContentGeometry {

    /**
     * Scale factors (sx, sy) for the full-clip-space quad so that content of
     * [contentWidth]x[contentHeight], drawn rotated by [rotationDegrees] into
     * a [targetWidth]x[targetHeight] surface, keeps its aspect ratio.
     *
     * Cover semantics: the shorter-fitting axis stays at 1 and the other
     * grows, so the surface is always fully painted and the excess content is
     * clipped symmetrically — the same FILL_CENTER contract the ViewPort
     * applies on the capture side, and the only choice that can never show
     * black bars inside the encoded frame.
     *
     * When aspects already match (every case that renders correctly today)
     * both factors are exactly 1 and the draw is bit-identical to the old
     * path — this function cannot regress a working configuration.
     */
    fun coverScale(
        contentWidth: Int,
        contentHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        if (contentWidth <= 0 || contentHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return floatArrayOf(1f, 1f)
        }
        // A 90/270 rotation lays the content's width along the target's
        // vertical axis, so the aspect that must survive is the transposed one.
        val transposed = rotationDegrees % 180 != 0
        val effectiveWidth = if (transposed) contentHeight else contentWidth
        val effectiveHeight = if (transposed) contentWidth else contentHeight

        val contentAspect = effectiveWidth.toFloat() / effectiveHeight
        val targetAspect = targetWidth.toFloat() / targetHeight
        return if (contentAspect > targetAspect) {
            // Content wider than the surface: widen the quad past the clip
            // edges so the sides are cropped instead of the image squeezing.
            floatArrayOf(contentAspect / targetAspect, 1f)
        } else {
            floatArrayOf(1f, targetAspect / contentAspect)
        }
    }

    /**
     * The rotation the VERTEX matrix must apply, given the total rotation the
     * content needs ([contentRotationDegrees]) and the rotation the source
     * texture already carries in its SurfaceTexture transform matrix
     * ([textureRotationDegrees]).
     *
     * Measured on hardware (S23 Ultra, hardware-validation rounds 3-4): the
     * direct Camera2 path delivers buffers whose texture matrix already folds
     * in the sensor mounting rotation (the HAL's buffer transform hint), so a
     * naive draw shows the content upright at a vertical hold with NO vertex
     * rotation at all — and applying the full content rotation on top turned
     * an already-straight image sideways ("el giro de 90, quítalo: ya estaba
     * recto"). CameraX's processed stream consumes that hint internally and
     * arrives with none. The vertex matrix therefore applies only the
     * difference; [coverScale] keeps using the TOTAL rotation, because the
     * transposition of buffer axes on screen is decided by texture and vertex
     * rotation combined.
     */
    fun vertexRotation(contentRotationDegrees: Int, textureRotationDegrees: Int): Int =
        ((contentRotationDegrees - textureRotationDegrees) % 360 + 360) % 360

    /**
     * Homogeneous texture coordinates (x, y, 0, 1 per vertex, matching the
     * shader's vec4 aTexCoord and the quad's vertex order) that sample only
     * the [left, top, right, bottom] pixel rect of a [bufferWidth]x
     * [bufferHeight] buffer.
     *
     * This is what actually consumes CameraX's TransformationInfo.cropRect,
     * which the app used to discard entirely: when CameraX hands the full
     * sensor buffer plus a crop rect (it only pre-crops when its internal
     * processing node is in the pipeline), ignoring the rect meant streaming
     * the whole frame with the composition silently not applied. For a
     * full-buffer rect this degenerates to the old constant 0..1 quad.
     *
     * Coordinates are normalized in the SurfaceTexture's content space; the
     * OES transform matrix (which handles the platform's y-flip) multiplies
     * them in the shader afterwards, same as before. ViewPort crops are
     * centered, so the rect stays correct under that flip.
     */
    fun cropTexCoords(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bufferWidth: Int,
        bufferHeight: Int,
    ): FloatArray {
        val l: Float
        val t: Float
        val r: Float
        val b: Float
        if (bufferWidth <= 0 || bufferHeight <= 0 || right <= left || bottom <= top) {
            l = 0f; t = 0f; r = 1f; b = 1f
        } else {
            l = left.toFloat() / bufferWidth
            t = top.toFloat() / bufferHeight
            r = right.toFloat() / bufferWidth
            b = bottom.toFloat() / bufferHeight
        }
        // Same vertex order as positionBuffer: (-1,-1) (1,-1) (-1,1) (1,1).
        return floatArrayOf(
            l, t, 0f, 1f,
            r, t, 0f, 1f,
            l, b, 0f, 1f,
            r, b, 0f, 1f,
        )
    }
}
