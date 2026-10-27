package com.buccaneer.browser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MenuItem(val label: String, val action: () -> Unit)

/** Left-hand drawer with a title and a column of remote-focusable buttons. */
class MenuPanel @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : FrameLayout(context) {

    private val titleView: TextView
    private val list: LinearLayout

    init {
        visibility = View.GONE
        setBackgroundColor(Colors.DIM)

        val drawer = LinearLayout(context)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(Colors.PANEL)
        drawer.setPadding(ui.u(16), ui.u(16), ui.u(16), ui.u(16))

        titleView = ui.text("", 22, Colors.WHITE, true)
        titleView.maxLines = 2
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.bottomMargin = ui.u(10)
        drawer.addView(titleView, tlp)

        val scroll = ScrollView(context)
        scroll.isFocusable = false
        list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        drawer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        addView(drawer, FrameLayout.LayoutParams(ui.u(380), MATCH, Gravity.START))
    }

    fun show(title: String, items: List<MenuItem>, focusIndex: Int = 0) {
        titleView.text = title
        list.removeAllViews()
        val buttons = ArrayList<View>()
        for (item in items) {
            val b: Button = ui.button(item.label, 17)
            b.minimumHeight = ui.u(44)
            b.setOnClickListener {
                hide()
                item.action()
            }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = ui.u(6)
            list.addView(b, lp)
            buttons.add(b)
        }
        visibility = View.VISIBLE
        if (buttons.isNotEmpty()) {
            val target = buttons[Math.max(0, Math.min(focusIndex, buttons.size - 1))]
            post { if (visibility == View.VISIBLE && isInside(target, this)) target.requestFocus() }
        }
    }

    fun hide() {
        visibility = View.GONE
    }
}

class ListRow(
    val title: String,
    val subtitle: String,
    val onOpen: () -> Unit,
    val onMore: (() -> Unit)?
)

/** Centered card listing bookmarks (or folders): one big button per row plus an "Options" button. */
class ListPanel @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : FrameLayout(context) {

    private val titleView: TextView
    private val list: LinearLayout
    private val footer: LinearLayout
    private val hintView: TextView

    init {
        visibility = View.GONE
        setBackgroundColor(Colors.DIM)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = ui.rounded(Colors.PANEL, 0, 0, 14)
        card.setPadding(ui.u(18), ui.u(16), ui.u(18), ui.u(16))

        titleView = ui.text("", 24, Colors.WHITE, true)
        titleView.maxLines = 1
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.bottomMargin = ui.u(8)
        card.addView(titleView, tlp)
        hintView = ui.text("", 13, Colors.SUBTLE)
        hintView.visibility = View.GONE
        card.addView(hintView, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(context)
        scroll.isFocusable = false
        list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        footer = LinearLayout(context)
        footer.orientation = LinearLayout.HORIZONTAL
        val flp = LinearLayout.LayoutParams(MATCH, WRAP)
        flp.topMargin = ui.u(10)
        card.addView(footer, flp)

        val width = Math.min(ui.screenW - ui.u(60), ui.u(700))
        val height = Math.min(ui.screenH - ui.u(50), ui.u(470))
        addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
    }

    fun show(title: String, rows: List<ListRow>, emptyText: String, footerItems: List<MenuItem>, focusRow: Int, hint: String? = null) {
        titleView.text = title
        hintView.text = hint ?: ""
        hintView.visibility = if (hint == null) View.GONE else View.VISIBLE
        list.removeAllViews()
        footer.removeAllViews()

        val mains = ArrayList<View>()
        for (row in rows) {
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL

            val main = ui.tile(row.title, row.subtitle, 17, 12, false)
            main.setOnClickListener { row.onOpen() }
            line.addView(main, LinearLayout.LayoutParams(0, WRAP, 1f))
            mains.add(main)

            val more = row.onMore
            if (more != null) {
                val b = ui.button("Options", 14)
                b.gravity = Gravity.CENTER
                b.setOnClickListener { more() }
                val blp = LinearLayout.LayoutParams(ui.u(100), MATCH)
                blp.leftMargin = ui.u(8)
                line.addView(b, blp)
            }

            val llp = LinearLayout.LayoutParams(MATCH, WRAP)
            llp.topMargin = ui.u(6)
            list.addView(line, llp)
        }

        if (rows.isEmpty() && emptyText.isNotEmpty()) {
            val t = ui.text(emptyText, 16, Colors.MUTED)
            t.setPadding(ui.u(6), ui.u(12), ui.u(6), ui.u(12))
            list.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val buttons = ArrayList<View>()
        for (item in footerItems) {
            val b = ui.button(item.label, 16)
            b.gravity = Gravity.CENTER
            b.setOnClickListener { item.action() }
            val lp = LinearLayout.LayoutParams(0, ui.u(44), 1f)
            if (buttons.isNotEmpty()) lp.leftMargin = ui.u(8)
            footer.addView(b, lp)
            buttons.add(b)
        }

        visibility = View.VISIBLE

        val target: View? = when {
            mains.isNotEmpty() -> mains[Math.max(0, Math.min(focusRow, mains.size - 1))]
            buttons.isNotEmpty() -> buttons[0]
            else -> null
        }
        if (target != null) post { if (visibility == View.VISIBLE && isInside(target, this)) target.requestFocus() }
    }

    fun hide() {
        visibility = View.GONE
    }
}

/** True when this view is [ancestor] or somewhere inside it. */
fun isInside(v: View, ancestor: View): Boolean {
    var p: Any? = v
    while (p is View) {
        if (p === ancestor) return true
        p = p.parent
    }
    return false
}

/** Small helper so call sites read well. */
fun View.shown(): Boolean = visibility == View.VISIBLE

/** A card with scrollable text (Settings > Diagnostics). Up/Down scroll it; see MainActivity.onTextPanelKey. */
class TextPanel @JvmOverloads constructor(context: Context, private val ui: Ui = Ui(context)) : FrameLayout(context) {

    private val titleView: TextView
    private val body: TextView
    private val scroll: ScrollView

    init {
        visibility = View.GONE
        setBackgroundColor(Colors.DIM)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = ui.rounded(Colors.PANEL, 0, 0, 14)
        card.setPadding(ui.u(20), ui.u(16), ui.u(20), ui.u(14))

        titleView = ui.text("", 24, Colors.WHITE, true)
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.bottomMargin = ui.u(8)
        card.addView(titleView, tlp)

        scroll = ScrollView(context)
        scroll.isFocusable = false
        body = ui.text("", 15, Colors.SUBTLE)
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val hint = ui.text("Up / Down: scroll        Select or Back: close", 13, Colors.MUTED)
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = ui.u(10)
        card.addView(hint, hlp)

        val width = Math.min(ui.screenW - ui.u(60), ui.u(780))
        val height = Math.min(ui.screenH - ui.u(40), ui.u(490))
        addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
    }

    fun show(title: String, text: String) {
        titleView.text = title
        body.text = text
        scroll.scrollTo(0, 0)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    /** direction: -1 = up, +1 = down */
    fun scrollStep(direction: Int) {
        scroll.smoothScrollBy(0, direction * ui.u(110))
    }
}
