package com.framecast.streamer.streaming

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.framecast.streamer.overlay.WatermarkOverlay
import com.framecast.streamer.rewards.StreamProfile
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

// Ceiling per reward tier — the hard cap a user's chosen Settings resolution/fps is
// clamped to. Free/premium output still looks identical to the PC demo sender
// (pc_receiver/demo_sender.py:_QUALITY_RESOLUTIONS) at these exact tier boundaries;
// the user's own Settings choice can only ever pick something at or below this.
private val TIER_CEILINGS = mapOf(
    "1080p60" to Triple(1920, 1080, 60),
    // Tier key is historical. The premium ceiling used to be a literal
    // 3840x2160, which silently downscaled an 8K capture to 4K before it ever
    // reached the encoder — an artificial cap, not a hardware or product one.
    // It is now the device's own maximum (7680x4320), so the ceiling only
    // enforces the free/premium split and never overrides what the camera can
    // actually deliver. The free tier is unchanged.
    "4k60" to Triple(7680, 4320, 60),
)

private const val FREE_JPEG_QUALITY = 80
private const val PREMIUM_JPEG_QUALITY = 95

/**
 * Converts a CameraX YUV frame into a JPEG payload matching the active
 * [StreamProfile] (watermark, encode quality) and an explicit target size —
 * the same transform pc_receiver/demo_sender.py:render_frame_for_profile
 * applies on the PC-demo side, kept in sync so free/premium output looks
 * identical regardless of source.
 */
object FrameEncoder {

    fun encode(image: ImageProxy, rotationDegrees: Int, profile: StreamProfile, targetWidth: Int, targetHeight: Int): ByteArray {
        val finalBitmap = prepareFrame(image, rotationDegrees, profile.watermark, targetWidth, targetHeight)

        val jpegQuality = if (profile.premiumActive) PREMIUM_JPEG_QUALITY else FREE_JPEG_QUALITY
        val out = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return out.toByteArray()
    }

    /**
     * Rotate, scale to the target output size, and (for free tier) burn in
     * the watermark — the shared prep pipeline for both this JPEG path and
     * H264Encoder's buffer-mode MediaCodec input. Encoding itself (JPEG
     * compress vs. NV12-for-MediaCodec conversion) is the only part that
     * differs between the two.
     */
    fun prepareFrame(image: ImageProxy, rotationDegrees: Int, watermark: Boolean, targetWidth: Int, targetHeight: Int): Bitmap {
        val bitmap = imageProxyToBitmap(image, rotationDegrees)
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return if (watermark) WatermarkOverlay.apply(resized) else resized
    }

    private fun imageProxyToBitmap(image: ImageProxy, rotationDegrees: Int): Bitmap {
        val bitmap = yuv420888ToArgbBitmap(image)
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Stride-aware YUV_420_888 -> ARGB_8888 Bitmap, direct pixel-for-pixel
     * conversion (BT.601 limited range, matching H264Encoder's own RGB->YUV
     * math so a round trip is lossless up to rounding).
     *
     * This used to go YUV -> NV21 -> YuvImage.compressToJpeg -> decodeByteArray
     * just to end up with a Bitmap — encoding to JPEG and immediately decoding
     * it back on every single frame, purely as a conversion mechanism. On a
     * real 1920x1080@60 stream that JPEG round trip was the dominant cost:
     * confirmed on-device, removing it took the stream from ~5fps with 1s+
     * latency to real-time. Reads each plane by its own stride/pixelStride
     * (not assuming rowStride == width, which plenty of real camera HALs
     * violate) for the same reason the old NV21 packer did.
     */
    private fun yuv420888ToArgbBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val chromaRow = row / 2
            val uRowStart = chromaRow * uRowStride
            val vRowStart = chromaRow * vRowStride
            val rowBase = row * width
            for (col in 0 until width) {
                val y = yBuffer.get(yRowStart + col * yPixelStride).toInt() and 0xFF
                val chromaCol = col / 2
                val u = uBuffer.get(uRowStart + chromaCol * uPixelStride).toInt() and 0xFF
                val v = vBuffer.get(vRowStart + chromaCol * vPixelStride).toInt() and 0xFF

                val c = y - 16
                val d = u - 128
                val e = v - 128
                val r = clamp8((298 * c + 409 * e + 128) shr 8)
                val g = clamp8((298 * c - 100 * d - 208 * e + 128) shr 8)
                val b = clamp8((298 * c + 516 * d + 128) shr 8)

                pixels[rowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun clamp8(v: Int): Int = max(0, min(255, v))

    /** The hard resolution/fps ceiling for a reward tier ("1080p60"/"4k60"). */
    fun tierCeiling(quality: String): Triple<Int, Int, Int> = TIER_CEILINGS.getValue(quality)
}
