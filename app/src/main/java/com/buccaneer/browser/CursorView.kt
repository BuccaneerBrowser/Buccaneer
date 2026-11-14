package com.buccaneer.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The on-screen mouse pointer. Its hot-spot is the centre of the circle.
 * While a page is loading, a thin bar under the dot shows it (no ring around the pointer).
 */
class CursorView @JvmOverloads constructor(context: Context, private val sizePx: Int = Ui(context).u(24)) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    var loading: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        fill.color = Color.argb(230, 255, 255, 255)
        fill.style = Paint.Style.FILL
        ring.color = Color.BLACK
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = Math.max(2f, sizePx / 9f)
        bar.color = Colors.ACCENT
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val c = sizePx / 2f
        val r = c - ring.strokeWidth
        canvas.drawCircle(c, c, r, fill)
        canvas.drawCircle(c, c, r, ring)
        if (loading) {
            val h = Math.max(3f, sizePx / 12f)
            barRect.set(0f, sizePx - h, sizePx.toFloat(), sizePx.toFloat())
            canvas.drawRoundRect(barRect, h / 2f, h / 2f, bar)
        }
    }

    /** Put the hot-spot at screen position (x, y). */
    fun moveTo(x: Float, y: Float) {
        translationX = x - sizePx / 2f
        translationY = y - sizePx / 2f
    }
}
