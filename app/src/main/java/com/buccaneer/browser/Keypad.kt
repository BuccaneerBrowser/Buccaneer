package com.buccaneer.browser

import android.content.Context
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * On-screen keyboard (letters, digits, symbols) operated with the Fire TV remote.
 *
 * The selected key is tracked here, not with Android focus, so the web page keeps focus and
 * keeps its text cursor while you type. MainActivity routes remote keys to [handleKey].
 *
 *   D-pad            move the highlighted key (wraps around)
 *   Select           type the highlighted key
 *   Rewind (<<)      delete          (hold to repeat)
 *   Fast-forward (>>) space
 *   Play/Pause       GO / ENTER
 *   Menu             switch letters <-> symbols
 *   Back             close
 */
class Keypad @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : LinearLayout(context) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        fun onClear()
        fun onGo()
        fun onClose()
    }

    private enum class Kind { CHAR, TEXT, SHIFT, SYM, ABC, SPACE, DEL, CLEAR, GO }

    private class KeyDef(val label: String, val kind: Kind, val weight: Int = 1) {
        val value: String get() = label
    }

    var listener: Listener? = null

    private val titleView: TextView
    private val previewView: TextView
    private val keysBox: LinearLayout

    private var layer = 0          // 0 = letters, 1 = symbols
    private var shift = false
    private var selRow = 1
    private var selCol = 0
    private var goLabel = "GO"
    private var rows: List<List<KeyDef>> = emptyList()
    private val keyViews = ArrayList<ArrayList<TextView>>()

    init {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = ui.rounded(Colors.PANEL, Color_OUTLINE, ui.u(2), 14)
        setPadding(ui.u(12), ui.u(10), ui.u(12), ui.u(10))
        isFocusable = false

        titleView = ui.text("", 15, Colors.MUTED)
        addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))

        previewView = ui.text("", 24, Colors.WHITE, true)
        previewView.setSingleLine(true)
        previewView.ellipsize = TextUtils.TruncateAt.START
        previewView.setPadding(ui.u(10), ui.u(4), ui.u(10), ui.u(4))
        previewView.background = ui.rounded(Colors.BG, 0, 0, 8)
        val plp = LinearLayout.LayoutParams(MATCH, WRAP)
        plp.topMargin = ui.u(4)
        plp.bottomMargin = ui.u(6)
        addView(previewView, plp)

        keysBox = LinearLayout(context)
        keysBox.orientation = LinearLayout.VERTICAL
        addView(keysBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val hint = ui.text(
            "Select: type    Rew: delete    FF: space    Play/Pause: go    Menu: symbols    Back: close",
            12, Colors.MUTED
        )
        hint.setSingleLine(true)
        hint.ellipsize = TextUtils.TruncateAt.END
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = ui.u(6)
        addView(hint, hlp)
    }

    // ------------------------------------------------------------------ public API

    /** Show the keypad. [goLabel] is the text on the big action key ("GO", "ENTER", "OK"). */
    fun open(title: String, goLabel: String) {
        this.goLabel = goLabel
        titleView.text = title
        layer = 0
        shift = false
        selRow = 1
        selCol = 0
        build()
        setPreview("")
        visibility = View.VISIBLE
    }

    fun close() {
        visibility = View.GONE
    }

    /** Text shown in the preview box (a caret is appended). */
    fun setPreview(text: String) {
        previewView.text = context.getString(R.string.keyboard_preview, text)
    }

    /** Called by MainActivity for every remote key press while the keypad is open. */
    fun handleKey(event: KeyEvent): Boolean {
        val rep = event.repeatCount
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveHorizontal(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveHorizontal(1)
            KeyEvent.KEYCODE_DPAD_UP -> moveVertical(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveVertical(1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> if (rep == 0) {
                // If the cursor is on the GO key, Enter acts as GO (matches the label).
                val k = if (rows.isNotEmpty() && selRow < rows.size && selCol < rows[selRow].size) rows[selRow][selCol] else null
                if (k != null && k.kind == Kind.GO) {
                    DiagnosticLog.record(context, "keypad_go", JSONObject().put("via", "enter"))
                    listener?.onGo()
                } else press()
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DEL -> listener?.onBackspace()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (rep == 0) listener?.onText(" ")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (rep == 0) listener?.onGo()
            KeyEvent.KEYCODE_MENU -> if (rep == 0) toggleLayer()
            KeyEvent.KEYCODE_BACK -> if (rep == 0) listener?.onClose()
            else -> {
                // A USB / Bluetooth keyboard can type directly.
                val uc = event.unicodeChar
                if (uc >= 32 && !event.isCtrlPressed && !event.isAltPressed) {
                    listener?.onText(String(Character.toChars(uc)))
                    return true
                }
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ layout

    private fun chars(s: String): List<KeyDef> = s.map { KeyDef(it.toString(), Kind.CHAR) }

    private fun letterRows(): List<List<KeyDef>> = listOf(
        chars("1234567890"),
        chars("qwertyuiop"),
        chars("asdfghjkl-"),
        chars("zxcvbnm./:"),
        listOf(
            KeyDef("SHIFT", Kind.SHIFT),
            KeyDef("SYM", Kind.SYM),
            KeyDef(".com", Kind.TEXT),
            KeyDef("SPACE", Kind.SPACE, 3),
            KeyDef("DEL", Kind.DEL),
            KeyDef("CLEAR", Kind.CLEAR),
            KeyDef("GO", Kind.GO, 2)
        )
    )

    private fun symbolRows(): List<List<KeyDef>> = listOf(
        chars("!@#\$%^&*()"),
        chars("-_=+[]{}\\|"),
        chars(";:'\",.<>/?"),
        listOf(
            KeyDef("`", Kind.CHAR),
            KeyDef("~", Kind.CHAR),
            KeyDef("www.", Kind.TEXT, 2),
            KeyDef("https://", Kind.TEXT, 3),
            KeyDef(".net", Kind.TEXT),
            KeyDef(".org", Kind.TEXT),
            KeyDef(".io", Kind.TEXT)
        ),
        listOf(
            KeyDef("ABC", Kind.ABC),
            KeyDef(".com", Kind.TEXT),
            KeyDef("@", Kind.CHAR),
            KeyDef("SPACE", Kind.SPACE, 3),
            KeyDef("DEL", Kind.DEL),
            KeyDef("CLEAR", Kind.CLEAR),
            KeyDef("GO", Kind.GO, 2)
        )
    )

    private fun labelFor(k: KeyDef): String = when (k.kind) {
        Kind.CHAR -> if (shift && k.label.length == 1 && k.label[0].isLetter()) k.label.uppercase() else k.label
        Kind.GO -> goLabel
        else -> k.label
    }

    private fun build() {
        keysBox.removeAllViews()
        keyViews.clear()
        rows = if (layer == 0) letterRows() else symbolRows()
        val gap = ui.u(3)
        for (row in rows) {
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            line.weightSum = 10f
            val views = ArrayList<TextView>()
            for (k in row) {
                val v = ui.key(labelFor(k))
                if (k.kind == Kind.SHIFT && shift) v.setTextColor(Colors.GREEN)
                val lp = LinearLayout.LayoutParams(0, MATCH, k.weight.toFloat())
                lp.setMargins(gap, 0, gap, 0)
                line.addView(v, lp)
                views.add(v)
            }
            val llp = LinearLayout.LayoutParams(MATCH, ui.u(40))
            llp.topMargin = gap
            keysBox.addView(line, llp)
            keyViews.add(views)
        }
        select(selRow, selCol)
    }

    /** Re-label letters (SHIFT) without rebuilding. */
    private fun refreshLabels() {
        for (r in rows.indices) {
            for (c in rows[r].indices) {
                val k = rows[r][c]
                val v = keyViews[r][c]
                v.text = labelFor(k)
                if (k.kind == Kind.SHIFT) v.setTextColor(if (shift) Colors.GREEN else Colors.WHITE)
            }
        }
    }

    // ------------------------------------------------------------------ selection

    private fun select(r: Int, c: Int) {
        if (keyViews.isEmpty()) return
        val rr = Math.max(0, Math.min(r, keyViews.size - 1))
        val cc = Math.max(0, Math.min(c, keyViews[rr].size - 1))
        if (selRow < keyViews.size && selCol < keyViews[selRow].size) {
            keyViews[selRow][selCol].isSelected = false
        }
        selRow = rr
        selCol = cc
        keyViews[rr][cc].isSelected = true
    }

    private fun centerOf(row: List<KeyDef>, col: Int): Float {
        var start = 0
        for (i in 0 until col) start += row[i].weight
        return start + row[col].weight / 2f
    }

    private fun nearestCol(row: List<KeyDef>, center: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in row.indices) {
            val d = Math.abs(centerOf(row, i) - center)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun moveVertical(delta: Int) {
        if (rows.isEmpty()) return
        val center = centerOf(rows[selRow], selCol)
        val nr = (selRow + delta + rows.size) % rows.size
        select(nr, nearestCol(rows[nr], center))
    }

    private fun moveHorizontal(delta: Int) {
        if (rows.isEmpty()) return
        val n = rows[selRow].size
        select(selRow, (selCol + delta + n) % n)
    }

    private fun toggleLayer() {
        if (layer == 0) {
            layer = 1
            selRow = 4
            selCol = 0
        } else {
            layer = 0
            selRow = 4
            selCol = 1
        }
        build()
    }

    // ------------------------------------------------------------------ typing

    private fun press() {
        if (rows.isEmpty()) return
        val k = rows[selRow][selCol]
        when (k.kind) {
            Kind.CHAR -> {
                var s = k.value
                if (shift && s.length == 1 && s[0].isLetter()) {
                    s = s.uppercase()
                    shift = false
                    refreshLabels()
                }
                listener?.onText(s)
            }
            Kind.TEXT -> listener?.onText(k.value)
            Kind.SPACE -> listener?.onText(" ")
            Kind.DEL -> listener?.onBackspace()
            Kind.CLEAR -> listener?.onClear()
            Kind.GO -> listener?.onGo()
            Kind.SHIFT -> {
                shift = !shift
                refreshLabels()
            }
            Kind.SYM, Kind.ABC -> toggleLayer()
        }
    }

    companion object {
        private val Color_OUTLINE: Int = android.graphics.Color.parseColor("#FF2F4C7A")
    }
}
