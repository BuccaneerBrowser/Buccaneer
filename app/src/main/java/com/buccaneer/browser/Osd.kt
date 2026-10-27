package com.buccaneer.browser

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Small on-screen display for video: play/pause state, position, volume. Auto-hides. */
class Osd @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : LinearLayout(context) {

    private val statusView: TextView
    private val seekBar: BarView
    private val timeView: TextView
    private val volView: TextView
    private val volBar: BarView
    private val hintView: TextView
    private val hideRunnable = Runnable { visibility = View.GONE }

    init {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = ui.rounded(Color.argb(225, 8, 22, 46), 0, 0, 14)
        setPadding(ui.u(20), ui.u(12), ui.u(20), ui.u(14))
        isFocusable = false

        statusView = ui.text("", 24, Colors.WHITE, true)
        addView(statusView, LinearLayout.LayoutParams(MATCH, WRAP))

        val track = Color.argb(90, 255, 255, 255)

        seekBar = BarView(context, track, Colors.ACCENT)
        val slp = LinearLayout.LayoutParams(MATCH, ui.u(7))
        slp.topMargin = ui.u(8)
        addView(seekBar, slp)

        timeView = ui.text("", 15, Colors.SUBTLE)
        addView(timeView, LinearLayout.LayoutParams(MATCH, WRAP))

        volView = ui.text("", 16, Colors.WHITE)
        val vlp = LinearLayout.LayoutParams(MATCH, WRAP)
        vlp.topMargin = ui.u(8)
        addView(volView, vlp)

        volBar = BarView(context, track, Colors.GREEN)
        val blp = LinearLayout.LayoutParams(MATCH, ui.u(7))
        blp.topMargin = ui.u(4)
        addView(volBar, blp)

        hintView = ui.text("", 13, Colors.MUTED)
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = ui.u(8)
        addView(hintView, hlp)
    }

    /** [seekFraction] is 0..1, or null to hide the position bar (no video found). */
    fun show(
        status: String,
        seekFraction: Float?,
        timeText: String,
        volText: String,
        volFraction: Float,
        hint: String
    ) {
        statusView.text = status
        if (seekFraction == null) {
            seekBar.visibility = View.GONE
            timeView.visibility = View.GONE
        } else {
            seekBar.visibility = View.VISIBLE
            timeView.visibility = View.VISIBLE
            seekBar.fraction = seekFraction
            timeView.text = timeText
        }
        volView.text = volText
        volBar.fraction = volFraction
        hintView.text = hint
        hintView.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, 3500)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    /** Coordinates are in the Activity root, just like its remote pointer. */
    fun seekFractionAt(rootX: Float, rootY: Float): Float? {
        if (visibility != View.VISIBLE || seekBar.visibility != View.VISIBLE || seekBar.width <= 0 ||
            !rootX.isFinite() || !rootY.isFinite()) return null
        val bar = IntArray(2); val origin = IntArray(2)
        seekBar.getLocationOnScreen(bar)
        rootView.getLocationOnScreen(origin)
        val x = rootX - (bar[0] - origin[0])
        val y = rootY - (bar[1] - origin[1])
        if (x < 0 || x > seekBar.width || y < -ui.u(5) || y > seekBar.height + ui.u(5)) return null
        return (x / seekBar.width).coerceIn(0f, 1f)
    }

    fun hideNow() {
        removeCallbacks(hideRunnable)
        visibility = View.GONE
    }
}
