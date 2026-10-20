package com.buccaneer.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

object Colors {
    val BG: Int = Color.parseColor("#FF08162E")
    val PANEL: Int = Color.parseColor("#F20B1D3A")
    val DIM: Int = Color.parseColor("#99000000")
    val BUTTON: Int = Color.parseColor("#FF1C3358")
    val ACCENT: Int = Color.parseColor("#FF2F81F7")
    val MUTED: Int = Color.parseColor("#FF9AA7B5")
    val SUBTLE: Int = Color.parseColor("#FFD0D7DE")
    val GREEN: Int = Color.parseColor("#FF3FB950")
    val RED: Int = Color.parseColor("#FFF85149")
    val GOLD: Int = Color.parseColor("#FFDFB24E")
    val WHITE: Int = Color.WHITE
}

/**
 * Tiny UI helper. One "unit" is 1/540 of the screen height, so every size below scales
 * with the TV resolution/density instead of being hard-coded in pixels.
 */
class Ui(private val context: Context) {

    val screenW: Int get() = context.resources.displayMetrics.widthPixels
    val screenH: Int get() = context.resources.displayMetrics.heightPixels
    private val unit: Float get() = Math.min(screenW, screenH) / 540f

    fun u(v: Int): Int = Math.round(v * unit)

    fun rounded(fill: Int, strokeColor: Int = 0, strokeWidth: Int = 0, radius: Int = 10): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.RECTANGLE)
        g.setCornerRadius(u(radius).toFloat())
        g.setColor(fill)
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor)
        return g
    }

    /** Background for remote-focusable things: blue + white outline when focused/pressed. */
    fun focusBg(): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), rounded(Colors.ACCENT, Colors.GOLD, u(3)))
        sl.addState(intArrayOf(android.R.attr.state_pressed), rounded(Colors.ACCENT, Colors.GOLD, u(3)))
        sl.addState(intArrayOf(), rounded(Colors.BUTTON))
        return sl
    }

    /** Background for keypad keys: they are never focused, the keypad marks one as "selected". */
    fun selectedBg(): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_selected), rounded(Colors.ACCENT, Colors.GOLD, u(3), 8))
        sl.addState(intArrayOf(), rounded(Colors.BUTTON, 0, 0, 8))
        return sl
    }

    fun text(s: String, sizeU: Int, color: Int = Colors.WHITE, bold: Boolean = false): TextView {
        val t = TextView(context)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeU).toFloat())
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD)
        return t
    }

    fun button(label: String, sizeU: Int = 16): Button {
        val b = Button(context)
        b.text = label
        b.setAllCaps(false)
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeU).toFloat())
        b.setTextColor(Colors.WHITE)
        b.background = focusBg()
        b.stateListAnimator = null
        b.isFocusable = true
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        b.setPadding(u(14), u(6), u(14), u(6))
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        return b
    }

    fun smallButton(label: String): Button {
        val b = button(label, 13)
        b.gravity = Gravity.CENTER
        b.setPadding(u(4), u(4), u(4), u(4))
        return b
    }

    /** A focusable two-line tile (name on top, small grey line underneath). */
    fun tile(
        primary: String,
        secondary: String,
        primarySize: Int = 18,
        secondarySize: Int = 12,
        center: Boolean = true
    ): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = if (center) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
        box.setPadding(u(10), u(6), u(10), u(6))
        box.background = focusBg()
        box.isFocusable = true
        box.isClickable = true

        val textGravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START

        val p = text(primary, primarySize, Colors.WHITE, true)
        p.gravity = textGravity
        p.maxLines = 2
        p.ellipsize = TextUtils.TruncateAt.END
        box.addView(p, LinearLayout.LayoutParams(MATCH, WRAP))

        if (secondary.isNotEmpty()) {
            val s = text(secondary, secondarySize, Colors.SUBTLE)
            s.gravity = textGravity
            s.maxLines = 1
            s.ellipsize = TextUtils.TruncateAt.END
            box.addView(s, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return box
    }

    /** A key on the on-screen keypad. */
    fun key(label: String): TextView {
        val t = text(label, 17, Colors.WHITE, true)
        t.gravity = Gravity.CENTER
        t.background = selectedBg()
        t.isFocusable = false
        return t
    }
}

/** Thin rounded progress bar (page loading, video position, volume). */
class BarView @JvmOverloads constructor(context: Context, private val trackColor: Int = Colors.MUTED, private val fillColor: Int = Colors.ACCENT) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var fraction: Float = 0f
        set(value) {
            field = if (value.isNaN()) 0f else Math.max(0f, Math.min(1f, value))
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            paint.color = fillColor
            rect.set(0f, 0f, Math.max(h, w * fraction), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
