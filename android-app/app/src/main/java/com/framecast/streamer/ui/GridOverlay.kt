package com.framecast.streamer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // Two vertical lines at 1/3 and 2/3
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, paint)
        // Two horizontal lines at 1/3 and 2/3
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, paint)
    }
}
