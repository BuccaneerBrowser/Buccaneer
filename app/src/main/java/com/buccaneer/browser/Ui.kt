package com.buccaneer.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
    // Brass-and-navy theme. PANEL keeps its original opacity on purpose: menus and the keypad
    // are drawn over the video surface, so their composition must not change.
    val BG: Int = Color.parseColor("#FF0B1837")
    val BG_EDGE: Int = Color.parseColor("#FF060D22")
    val PANEL: Int = Color.parseColor("#F20B1D3A")
    val DIM: Int = Color.parseColor("#99000000")
    val BUTTON: Int = Color.parseColor("#FF1E3864")
    val SELECTED: Int = Color.parseColor("#FF2B4D88")
    val ACCENT: Int = Color.parseColor("#FF2F81F7")
    val MUTED: Int = Color.parseColor("#FF8FA3C7")
    val SUBTLE: Int = Color.parseColor("#FFA9BAD8")
    val SUBTLE_HI: Int = Color.parseColor("#FFD4DEF2")
    val GREEN: Int = Color.parseColor("#FF3FB950")
    val RED: Int = Color.parseColor("#FFF85149")
    val GOLD: Int = Color.parseColor("#FFD9A441")
    val BRASS_HI: Int = Color.parseColor("#FFF3D68A")
    val CREAM: Int = Color.parseColor("#FFF3E9D2")
    val CREAM_HI: Int = Color.parseColor("#FFFFF4D8")
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

    /** One unit of the approved brass-and-navy mockup (it was drawn 1.5x smaller than a screen unit). */
    fun mu(v: Float): Float = v * 1.5f * unit

    fun rounded(fill: Int, strokeColor: Int = 0, strokeWidth: Int = 0, radius: Int = 10): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.RECTANGLE)
        g.setCornerRadius(u(radius).toFloat())
        g.setColor(fill)
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor)
        return g
    }

    /** Deep-navy radial backdrop for a full screen (the start page). */
    fun screenBackground(): GradientDrawable {
        val g = GradientDrawable()
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        g.setColors(intArrayOf(Colors.BG, Colors.BG_EDGE))
        g.setGradientCenter(0.5f, 0.35f)
        g.setGradientRadius(screenW * 0.9f)
        return g
    }

    /** Sizes of one glass slab. [lifted] = start-page sizing with room to grow and glow when selected. */
    private class Slab(val pad: Float, val lift: Float, val radius: Float, val lip: Float)

    private fun slab(kind: Int, lifted: Boolean): Slab {
        if (!lifted) return Slab(mu(1.5f), 0f, mu(6.5f), mu(2.5f))
        val tile = kind == GlassDrawable.KIND_TILE
        val small = kind == GlassDrawable.KIND_SMALL
        val pad = if (tile) mu(4.5f) else if (small) mu(2.5f) else mu(3.5f)
        val radius = if (tile) mu(10f) else if (small) mu(9f) else mu(11f)
        val lip = if (tile) mu(3f) else if (small) mu(3f) else mu(3.5f)
        return Slab(pad, pad * 0.55f, radius, lip)
    }

    /** Room (px) a lifted glass slab keeps free around itself inside its view bounds. */
    fun padFor(kind: Int): Int = Math.round(slab(kind, true).pad)

    private fun glass(kind: Int, focused: Boolean, s: Slab): GlassDrawable =
        GlassDrawable(kind, focused, s.pad, s.lift, s.radius, s.lip, mu(1f))

    /** Brass-and-navy glass slab: gold-rimmed when focused or pressed. */
    fun glassBg(kind: Int, lifted: Boolean): StateListDrawable {
        val s = slab(kind, lifted)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), glass(kind, true, s))
        sl.addState(intArrayOf(android.R.attr.state_pressed), glass(kind, true, s))
        sl.addState(intArrayOf(), glass(kind, false, s))
        return sl
    }

    /** Background for remote-focusable things: glass slab, gold rim when focused/pressed. */
    fun focusBg(): StateListDrawable = glassBg(GlassDrawable.KIND_BUTTON, false)

    /** Background with a custom unfocused fill color (for tinted tiles). */
    fun focusBg(unfocused: Int, lifted: Boolean = false): StateListDrawable {
        val s = slab(GlassDrawable.KIND_TILE, lifted)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), glass(GlassDrawable.KIND_TILE, true, s))
        sl.addState(intArrayOf(android.R.attr.state_pressed), glass(GlassDrawable.KIND_TILE, true, s))
        val flat = Math.round(s.pad)
        sl.addState(intArrayOf(), InsetDrawable(rounded(unfocused), flat, flat, flat, flat))
        return sl
    }

    /** Background for keypad keys: they are never focused, the keypad marks one as "selected". */
    fun selectedBg(): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_selected), rounded(Colors.SELECTED, Colors.GOLD, u(3), 8))
        sl.addState(intArrayOf(), rounded(Colors.BUTTON, 0, 0, 8))
        return sl
    }

    /** Text color that brightens when the view (or the parent it mirrors) is focused or pressed. */
    private fun focusText(normal: Int, bright: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        ),
        intArrayOf(bright, bright, normal)
    )

    /** Thin brass rule under a tile's name: short and dim normally, full-width and bright when selected. */
    private fun brassRule(): StateListDrawable {
        fun bar(color: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setShape(GradientDrawable.RECTANGLE)
            g.setCornerRadius(mu(0.7f))
            g.setColor(color)
            return g
        }
        val side = Math.round(mu(4f))
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), bar(Colors.BRASS_HI))
        sl.addState(intArrayOf(android.R.attr.state_pressed), bar(Colors.BRASS_HI))
        sl.addState(intArrayOf(), InsetDrawable(bar(Color.parseColor("#8CD9A441")), side, 0, side, 0))
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

    fun button(
        label: String,
        sizeU: Int = 16,
        lifted: Boolean = false,
        kind: Int = GlassDrawable.KIND_BUTTON
    ): Button {
        val s = slab(kind, lifted)
        val pad = Math.round(s.pad)
        val lip = Math.round(s.lip)
        val b = Button(context)
        b.text = label
        b.setAllCaps(false)
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeU).toFloat())
        b.setTextColor(focusText(Colors.CREAM, Colors.CREAM_HI))
        if (lifted) b.setTypeface(Typeface.DEFAULT_BOLD)
        b.background = glassBg(kind, lifted)
        b.stateListAnimator = null
        b.isFocusable = true
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        // The glass face sits inside the view bounds (pad all round, lip below), so the text
        // padding includes them and the label stays centred on the face.
        b.setPadding(u(14) + pad, u(6) + pad, u(14) + pad, u(6) + pad + lip)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        return b
    }

    /**
     * Legacy flat button face (pre-glass 2.4.15 style): solid fill, gold-ringed accent on
     * focus, static muted label. Used by the side menu only — the glass slab face reads
     * poorly in a dense button column (owner preference, 2.5.0). Every other surface keeps
     * the glass UI.
     */
    fun flatButton(label: String, sizeU: Int = 16): Button {
        val b = Button(context)
        b.text = label
        b.setAllCaps(false)
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizeU).toFloat())
        b.setTextColor(Colors.SUBTLE)
        b.background = flatFace(Colors.BUTTON)
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

    /** Pre-glass StateListDrawable: solid fill, gold-ringed accent when focused/pressed. */
    private fun flatFace(unfocused: Int): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), rounded(Colors.ACCENT, Colors.GOLD, u(3)))
        sl.addState(intArrayOf(android.R.attr.state_pressed), rounded(Colors.ACCENT, Colors.GOLD, u(3)))
        sl.addState(intArrayOf(), rounded(unfocused))
        return sl
    }

    fun smallButton(label: String, lifted: Boolean = false): Button {
        val s = slab(GlassDrawable.KIND_SMALL, lifted)
        val pad = Math.round(s.pad)
        val lip = Math.round(s.lip)
        val b = button(label, 13, lifted, GlassDrawable.KIND_SMALL)
        b.gravity = Gravity.CENTER
        b.setPadding(u(4) + pad, u(4) + pad, u(4) + pad, u(4) + pad + lip)
        return b
    }

    /** A focusable two-line tile (name on top, small grey line underneath).
     *  With [boxW]/[boxH] set (the cell the tile will occupy), the title is measured with the
     *  exact layout engine and shrunk only if the content would not fit the fixed cell height. */
    fun tile(
        primary: String,
        secondary: String,
        primarySize: Int = 18,
        secondarySize: Int = 12,
        center: Boolean = true,
        tint: Int = 0,
        lifted: Boolean = false,
        boxW: Int = 0,
        boxH: Int = 0
    ): LinearLayout {
        val s = slab(GlassDrawable.KIND_TILE, lifted)
        val pad = Math.round(s.pad)
        val lip = Math.round(s.lip)
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = if (center) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
        val sideBase = if (lifted) u(6) else u(10)
        val edgeBase = if (lifted) u(4) else u(6)
        box.setPadding(sideBase + pad, edgeBase + pad, sideBase + pad, edgeBase + pad + lip)
        box.background = if (tint != 0) focusBg(tint, lifted) else glassBg(GlassDrawable.KIND_TILE, lifted)
        box.isFocusable = true
        box.isClickable = true

        val textGravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START

        val p = text(primary, primarySize, Colors.CREAM, true)
        p.setTextColor(focusText(Colors.CREAM, Colors.CREAM_HI))
        p.isDuplicateParentStateEnabled = true
        p.gravity = textGravity
        p.maxLines = 2
        p.ellipsize = TextUtils.TruncateAt.END
        box.addView(p, LinearLayout.LayoutParams(MATCH, WRAP))

        val ruleH = if (center && secondary.isNotEmpty()) {
            Math.max(2, Math.round(mu(1.4f))) + Math.round(mu(1.2f)) + Math.round(mu(0.8f))
        } else 0
        var t: TextView? = null
        if (secondary.isNotEmpty()) {
            if (center) {
                val rule = View(context)
                rule.background = brassRule()
                rule.isDuplicateParentStateEnabled = true
                val rlp = LinearLayout.LayoutParams(Math.round(mu(28f)), Math.max(2, Math.round(mu(1.4f))))
                rlp.topMargin = Math.round(mu(1.2f))
                rlp.bottomMargin = Math.round(mu(0.8f))
                box.addView(rule, rlp)
            }
            t = text(secondary, secondarySize, Colors.SUBTLE)
            t!!.setTextColor(focusText(Colors.SUBTLE, Colors.SUBTLE_HI))
            t!!.isDuplicateParentStateEnabled = true
            t!!.gravity = textGravity
            t!!.maxLines = 1
            t!!.ellipsize = TextUtils.TruncateAt.END
            box.addView(t!!, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fitTitleToBox(box, p, t, ruleH, boxW, boxH)
        return box
    }

    /**
     * Guarantees title + rule + subtitle fit the fixed-height [box] BEFORE the layout pass.
     * Heights are measured with the same StaticLayout.Builder TextView's makeSingleLayout uses
     * (spacing 0/1.0, includePad, breakStrategy SIMPLE, hyphenation NONE, ellipsize END at the
     * available width), so they are exactly the heights the TextViews will report. Only the
     * title is ever shrunk; the subtitle keeps its designed size. No-op when the content
     * already fits (with [TILE_FIT_MARGIN] px of headroom) or when no box was supplied.
     */
    private fun fitTitleToBox(box: LinearLayout, p: TextView, t: TextView?, ruleH: Int, boxW: Int, boxH: Int) {
        if (boxW <= 0 || boxH <= 0) return
        if (Build.VERSION.SDK_INT < 23) return // StaticLayout.Builder requires API 23
        val availW = boxW - box.paddingLeft - box.paddingRight
        val availH = boxH - box.paddingTop - box.paddingBottom
        if (availW <= 0 || availH <= 0) return

        fun measuredHeight(view: TextView, maxLines: Int): Int {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.typeface = view.typeface
            paint.textSize = view.textSize
            val txt = view.text.toString()
            val l = StaticLayout.Builder.obtain(txt, 0, txt.length, paint, availW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(availW)
                .setMaxLines(maxLines)
                .build()
            return if (l.lineCount <= maxLines) l.height else l.getLineTop(maxLines)
        }

        val subH = if (t != null) measuredHeight(t, 1) else 0
        fun titleH(sizePx: Float): Int {
            p.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            return measuredHeight(p, 2)
        }

        val nominal = p.textSize
        var size = nominal
        var h = titleH(nominal)
        if (h + ruleH + subH > availH - TILE_FIT_MARGIN) {
            val scale = tileFitScale(h, ruleH, subH, availH, TILE_FIT_MARGIN)
            if (scale > 0f) {
                size = Math.max(1f, nominal * scale)
                h = titleH(size)
                var guard = 0
                while (h + ruleH + subH > availH - TILE_FIT_MARGIN && guard < 10 && size > 1f) {
                    size -= 1f
                    h = titleH(size)
                    guard++
                }
            }
        }
        if (h + ruleH + subH <= availH - TILE_FIT_MARGIN && size != nominal) {
            p.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
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

/**
 * One brass-and-navy glass slab: a dark base (the "lip") under a gradient face with a curved
 * sheen, light pooling at the bottom, an inner shadow and a metal rim. Selected (focused or
 * pressed) = gold rim, a brighter face that grows by [lift], a halo ring and a soft glow.
 *
 * Everything is drawn INSIDE the drawable bounds ([pad] is the room kept free around the face)
 * with plain gradients and translucent rounded rects: no blur, no layers, no off-screen
 * buffers, nothing drawn outside the view. That keeps it cheap on the TV's GPU and means no
 * parent has to disable clipping.
 */
class GlassDrawable(
    private val kind: Int,
    private val focused: Boolean,
    private val pad: Float,
    private val lift: Float,
    private val radius: Float,
    private val lip: Float,
    private val mu: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val built = Rect()
    private var alphaValue = 255

    private var lipShader: Shader? = null
    private var faceShader: Shader? = null
    private var glowShader: Shader? = null
    private var glossShader: Shader? = null
    private var innerShader: Shader? = null
    private var rimShader: Shader? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = if (focused) pad - lift else pad
        val left = b.left + inset
        val top = b.top + inset
        val right = b.right - inset
        val slabBottom = b.bottom - inset
        val faceBottom = slabBottom - lip
        val w = right - left
        val h = faceBottom - top
        if (w <= 0f || h <= 0f) return
        val rad = Math.min(radius, Math.min(w, h) / 2f)
        if (built != b) {
            built.set(b)
            buildShaders(top, faceBottom, slabBottom)
        }

        // Soft shadow under the base (three stacked translucent shapes instead of a blur).
        val room = b.bottom - slabBottom
        if (room > 1f) {
            for (i in 0 until 3) {
                val grow = room * (0.9f - 0.3f * i)
                rect.set(left - grow * 0.6f, top + lip, right + grow * 0.6f, slabBottom + grow)
                solid(canvas, SHADOW[i], rad + grow * 0.5f)
            }
        }

        // Selected: gold glow and a thin halo ring, in the room the lifted face leaves free.
        if (focused && inset > 1f) {
            for (i in 0 until 3) {
                val grow = inset * (0.95f - 0.3f * i)
                rect.set(left - grow, top - grow, right + grow, slabBottom + grow)
                solid(canvas, GLOW[i], rad + grow)
            }
            val ring = inset * 0.55f
            rect.set(left - ring, top - ring, right + ring, slabBottom + ring)
            outline(canvas, null, HALO, rad + ring, Math.max(1f, mu * 0.8f))
        }

        // Base, then the face on top of it.
        rect.set(left, top + lip, right, slabBottom)
        shaded(canvas, lipShader, rad)
        rect.set(left, top, right, faceBottom)
        shaded(canvas, faceShader, rad)

        // Light pooling along the bottom of the glass.
        val g = mu * 1.5f
        rect.set(left + g, top + h * 0.58f, right - g, faceBottom - g)
        if (rect.width() > 0f && rect.height() > 0f) shaded(canvas, glowShader, Math.max(rad - g, 0f))

        // Curved sheen across the top.
        val gi = mu * 2f
        val a = left + gi
        val z = right - gi
        val gt = top + gi
        val q = Math.max(rad - gi, mu * 3f)
        val mid = top + h * 0.42f
        path.reset()
        path.moveTo(a, gt + q)
        path.quadTo(a, gt, a + q, gt)
        path.lineTo(z - q, gt)
        path.quadTo(z, gt, z, gt + q)
        path.lineTo(z, mid)
        path.quadTo((left + right) / 2f, top + h * 0.6f, a, mid)
        path.close()
        paint.style = Paint.Style.FILL
        paint.setShader(glossShader)
        paint.color = Color.BLACK
        paint.alpha = alphaValue
        canvas.drawPath(path, paint)

        // Inner shadow (gives the face thickness) and the metal rim.
        rect.set(left + g, top + g, right - g, faceBottom - g)
        outline(canvas, innerShader, 0, Math.max(rad - g, 0f), mu * 2f)
        val ri = mu * 0.6f
        rect.set(left + ri, top + ri, right - ri, faceBottom - ri)
        outline(canvas, rimShader, 0, Math.max(rad - ri, 0f), rimWidth())

        // Thin specular line along the top edge.
        val x0 = left + rad + gi
        val x1 = right - rad - gi
        if (x1 > x0) {
            paint.setShader(null)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Math.max(1f, mu * 0.8f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = withAlpha(if (focused) 0x80FFFFFF.toInt() else 0x4DFFFFFF)
            canvas.drawLine(x0, top + mu * 1.8f, x1, top + mu * 1.8f, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
    }

    private fun buildShaders(top: Float, faceBottom: Float, slabBottom: Float) {
        val h = faceBottom - top
        val g = mu * 1.5f
        lipShader = vertical(top + lip, slabBottom, LIP, null)
        faceShader = vertical(top, faceBottom, faceColors(), null)
        glowShader = vertical(top + h * 0.58f, faceBottom - g, GLOW_FADE, null)
        glossShader = vertical(top, top + h * 0.6f, if (kind == KIND_TILE) GLOSS_TILE else GLOSS_BUTTON, null)
        innerShader = vertical(top + g, faceBottom - g, INNER, null)
        rimShader = vertical(top, faceBottom, rimColors(), if (!focused && kind == KIND_TILE) RIM_TILE_STOPS else null)
    }

    private fun vertical(y0: Float, y1: Float, colors: IntArray, stops: FloatArray?): Shader =
        LinearGradient(0f, y0, 0f, y1, colors, stops, Shader.TileMode.CLAMP)

    private fun faceColors(): IntArray = when {
        kind == KIND_TILE && focused -> TILE_FOCUS
        kind == KIND_TILE -> TILE
        focused -> BUTTON_FOCUS
        else -> BUTTON
    }

    private fun rimColors(): IntArray = when {
        focused -> RIM_FOCUS
        kind == KIND_TILE -> RIM_TILE
        else -> RIM_BUTTON
    }

    private fun rimWidth(): Float = when {
        focused -> mu * 2.8f
        kind == KIND_TILE -> mu * 1.1f
        kind == KIND_SMALL -> mu
        else -> mu * 1.3f
    }

    private fun withAlpha(color: Int): Int =
        Color.argb(Color.alpha(color) * alphaValue / 255, Color.red(color), Color.green(color), Color.blue(color))

    private fun solid(canvas: Canvas, color: Int, r: Float) {
        paint.setShader(null)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    private fun shaded(canvas: Canvas, shader: Shader?, r: Float) {
        paint.style = Paint.Style.FILL
        paint.setShader(shader)
        paint.color = Color.BLACK
        paint.alpha = alphaValue
        canvas.drawRoundRect(rect, r, r, paint)
    }

    /** Stroke [rect] with a gradient, or with a flat [color] when [shader] is null. */
    private fun outline(canvas: Canvas, shader: Shader?, color: Int, r: Float, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.setShader(shader)
        if (shader == null) {
            paint.color = withAlpha(color)
        } else {
            paint.color = Color.BLACK
            paint.alpha = alphaValue
        }
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {
        if (alpha != alphaValue) {
            alphaValue = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.setColorFilter(colorFilter)
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val KIND_TILE = 0
        const val KIND_BUTTON = 1
        const val KIND_SMALL = 2

        private val LIP = intArrayOf(0xFF07112A.toInt(), 0xFF02060E.toInt())
        private val TILE = intArrayOf(0xFF1B355B.toInt(), 0xFF0E1C34.toInt())
        private val TILE_FOCUS = intArrayOf(0xFF2D5290.toInt(), 0xFF19345F.toInt())
        private val BUTTON = intArrayOf(0xFF315089.toInt(), 0xFF1E3864.toInt(), 0xFF122242.toInt())
        // Lifted slightly (2026-11-15) so the selected state registers clearly against BUTTON.
        private val BUTTON_FOCUS = intArrayOf(0xFF547EC5.toInt(), 0xFF355998.toInt(), 0xFF253F6E.toInt())
        private val GLOW_FADE = intArrayOf(0x003F69AB, 0x473F69AB)
        private val GLOSS_TILE = intArrayOf(0x29FFFFFF, 0x05FFFFFF)
        private val GLOSS_BUTTON = intArrayOf(0x47FFFFFF, 0x0AFFFFFF)
        private val INNER = intArrayOf(0x00040A18, 0x80040A18.toInt())
        private val RIM_TILE = intArrayOf(0xA6F6E4B4.toInt(), 0x595C77A8, 0xB3233862.toInt())
        private val RIM_TILE_STOPS = floatArrayOf(0f, 0.35f, 1f)
        private val RIM_BUTTON = intArrayOf(0xFFF7E6B0.toInt(), 0xFFC99A3E.toInt(), 0xFF7A5514.toInt())
        private val RIM_FOCUS = intArrayOf(0xFFFFEDB0.toInt(), 0xFFE0AE4A.toInt(), 0xFFB07F22.toInt())
        private val SHADOW = intArrayOf(0x38030A18, 0x24030A18, 0x14030A18)
        private val GLOW = intArrayOf(0x1AE0AE4A, 0x24E0AE4A, 0x33E0AE4A)
        private val HALO = 0x73F3D68A
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

/** Headroom (px) the tile fit keeps free below the content budget, absorbing +/-1 px rounding. */
const val TILE_FIT_MARGIN = 2

/**
 * Scale factor for the tile title text so that title + rule + subtitle fit within [availH]
 * while keeping [margin] px of headroom. Returns 1.0f when the content already fits, or 0f
 * when the title budget is degenerate (nothing can be shown). Pure on purpose: no Context,
 * no android.* imports, so it is unit-testable on the JVM.
 */
fun tileFitScale(titleH: Int, ruleH: Int, subH: Int, availH: Int, margin: Int = TILE_FIT_MARGIN): Float {
    if (titleH + ruleH + subH <= availH - margin) return 1f
    val budget = availH - margin - ruleH - subH
    if (budget <= 0 || titleH <= 0) return 0f
    return budget.toFloat() / titleH.toFloat()
}