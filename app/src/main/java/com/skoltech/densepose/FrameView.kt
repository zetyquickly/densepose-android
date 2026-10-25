package com.skoltech.densepose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Shows the latest DensePose picture, fitted into the view, with the person boxes on top. */
class FrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var showBoxes = true
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var mirror = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    @Volatile
    private var bitmap: Bitmap? = null

    @Volatile
    private var boxes = FloatArray(0)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.YELLOW
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val dst = RectF()

    /** Any thread. The producer reuses `bitmap` a few frames later. */
    fun show(bitmap: Bitmap, info: FrameInfo) {
        this.bitmap = bitmap
        this.boxes = info.boxes
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * scale
        val h = bmp.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        if (showBoxes) {
            val b = boxes
            for (i in 0 until b.size / 5) {
                val o = i * 5
                canvas.drawRect(dst.left + b[o] * scale, dst.top + b[o + 1] * scale, dst.left + b[o + 2] * scale, dst.top + b[o + 3] * scale, boxPaint)
            }
        }
        canvas.restore()
        if (showBoxes) {  // scores stay readable when mirrored
            val b = boxes
            for (i in 0 until b.size / 5) {
                val o = i * 5
                val x = if (mirror) width - (dst.left + b[o + 2] * scale) else dst.left + b[o] * scale
                canvas.drawText("%.2f".format(b[o + 4]), x + 6f, dst.top + b[o + 1] * scale + textPaint.textSize + 4f, textPaint)
            }
        }
    }
}
