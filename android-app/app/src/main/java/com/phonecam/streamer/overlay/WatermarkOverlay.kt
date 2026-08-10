package com.phonecam.streamer.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Burns a small "free tier" watermark into the bottom-right corner of a
 * frame before encoding. Mirrors pc_receiver/demo_sender.py:apply_watermark
 * so the free-tier look is identical whether the frame came from the real
 * phone camera or the PC demo sender used to validate the pipeline.
 */
object WatermarkOverlay {

    fun apply(source: Bitmap, text: String = "FrameCast - FREE"): Bitmap {
        // Bitmap.getConfig() is nullable (hardware bitmaps can report null) —
        // ARGB_8888 is a safe, universally-supported fallback for a fresh copy.
        val out = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = out.width / 32f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        val margin = out.width * 0.015f
        val textWidth = paint.measureText(text)
        val x = out.width - textWidth - margin
        val y = out.height - margin

        canvas.drawText(text, x, y, paint)
        return out
    }

    /**
     * Same watermark, but on its own transparent [width]x[height] bitmap
     * instead of burned into a copy of the source frame — for
     * [com.phonecam.streamer.streaming.gl.EncoderSurfaceRenderer], which
     * composites this as an alpha-blended texture on the GPU rather than
     * re-drawing text on every frame's Bitmap on the CPU.
     */
    fun renderOverlay(width: Int, height: Int, text: String = "FrameCast - FREE"): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width / 32f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        val margin = width * 0.015f
        val textWidth = paint.measureText(text)
        val x = width - textWidth - margin
        val y = height - margin

        canvas.drawText(text, x, y, paint)
        return out
    }
}
