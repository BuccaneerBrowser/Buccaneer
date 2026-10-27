package com.buccaneer.browser

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The page shown when the browser starts (and from Menu > Start page).
 *
 *  - Ten bookmarks/folders at a time (2 rows of 5), with paging arrows above the bottom actions.
 *  - Folder contents are paged independently; Back returns to the original folder-list page.
 *
 * It is a native screen, so the remote's D-pad moves between the buttons and Select opens one.
 * "Top 10" means the first 10 in your list; reorder them from Menu > Bookmarks > Options.
 */
class StartPage @JvmOverloads constructor(
    context: Context,
    private val ui: Ui = Ui(context),
    private val store: BookmarkStore = BookmarkStore.shared(context)
) : FrameLayout(context) {

    interface Listener {
        fun openUrl(url: String)
        fun search()
        fun manage(folder: String?)
    }

    var listener: Listener? = null

    /** The running version, read live from the package (always matches the installed build). */
    private val versionLabel: String by lazy {
        try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            p.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** null = top level; otherwise the folder being shown ("" = Unsorted). */
    private var folder: String? = null
    private var pageIndex = 0
    private var folderListPage = 0
    private var renderGeneration = 0
    private var firstFocusable: View? = null
    private val content = LinearLayout(context)

    init {
        visibility = View.GONE
        setBackgroundColor(Colors.BG)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(ui.u(40), ui.u(26), ui.u(40), ui.u(16))
        addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun show() {
        folder = null
        pageIndex = 0
        folderListPage = 0
        render()
        visibility = View.VISIBLE
        focusFirst()
    }

    fun hide() {
        visibility = View.GONE
    }

    /** Keep the current page when bookmarks are edited; clamp it if the list shrinks. */
    fun refresh() {
        if (visibility != View.VISIBLE) return
        val current = folder
        if (current != null && store.inFolder(current).isEmpty()) {
            folder = null
            pageIndex = folderListPage
        }
        render()
        focusFirst()
    }

    /** Back key: leave a folder. Returns true if it did something. */
    fun back(): Boolean {
        if (folder == null) return false
        folder = null
        pageIndex = folderListPage
        render()
        focusFirst()
        return true
    }

    fun focusFirst() {
        val f = firstFocusable
        val generation = renderGeneration
        if (f != null) post { if (visibility == View.VISIBLE && generation == renderGeneration && f.parent != null) f.requestFocus() }
    }

    // ------------------------------------------------------------------ drawing

    private fun render() {
        renderGeneration++
        content.removeAllViews()
        firstFocusable = null

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val tagLeft = ui.text("Where's me buccaneers?", 15, Colors.SUBTLE)
        tagLeft.gravity = Gravity.RIGHT
        val tllp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tllp.rightMargin = ui.u(10)
        header.addView(tagLeft, tllp)

        val logo = ImageView(context)
        logo.setImageResource(R.drawable.buccaneer_logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        val lslp = LinearLayout.LayoutParams(ui.u(36), ui.u(36))
        header.addView(logo, lslp)

        val tagRight = ui.text("Under me bucc'n hat!", 15, Colors.SUBTLE)
        tagRight.gravity = Gravity.LEFT
        val trlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        trlp.leftMargin = ui.u(10)
        header.addView(tagRight, trlp)

        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val f = folder
        val tiles = ArrayList<View>()
        val subtitle: String
        val total: Int
        if (f != null) {
            val entries = store.inFolder(f)
            total = entries.size
            pageIndex = pageIndex.coerceIn(0, lastPage(total))
            subtitle = if (f.isEmpty()) "Folder: Unsorted" else "Folder: $f"
            for (b in entries.subList(pageIndex * MAX_TILES, minOf((pageIndex + 1) * MAX_TILES, total))) tiles.add(bookmarkTile(b))
        } else if (store.hasFolders()) {
            val entries = store.entries(0)
            total = entries.size
            pageIndex = pageIndex.coerceIn(0, lastPage(total))
            subtitle = "Your bookmark folders"
            for (info in entries.subList(pageIndex * MAX_TILES, minOf((pageIndex + 1) * MAX_TILES, total))) tiles.add(folderTile(info))
        } else {
            val entries = store.all()
            total = entries.size
            pageIndex = pageIndex.coerceIn(0, lastPage(total))
            subtitle = if (entries.isEmpty()) "No bookmarks yet" else "Your bookmarks"
            for (b in entries.subList(pageIndex * MAX_TILES, minOf((pageIndex + 1) * MAX_TILES, total))) tiles.add(bookmarkTile(b))
        }

        val sub = ui.text(subtitle, 17, Colors.MUTED)
        sub.gravity = Gravity.CENTER_HORIZONTAL
        sub.maxLines = 1
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ui.u(2)
        content.addView(sub, slp)

        if (tiles.isEmpty()) {
            val msg = ui.text(
                "Open a website, press Menu, then choose \"Add bookmark\".\n" +
                    "Your bookmarks and folders will show up here.",
                17, Colors.SUBTLE
            )
            msg.gravity = Gravity.CENTER_HORIZONTAL
            val mlp = LinearLayout.LayoutParams(WRAP, WRAP)
            mlp.topMargin = ui.u(44)
            mlp.bottomMargin = ui.u(30)
            content.addView(msg, mlp)
        } else {
            addGrid(tiles, total > MAX_TILES)
        }

        if (total > MAX_TILES) {
            val pager = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun arrow(label: String, description: String, enabled: Boolean, delta: Int) {
                val button = ui.smallButton(label)
                button.contentDescription = description
                button.isEnabled = enabled
                button.isFocusable = enabled
                button.alpha = if (enabled) 1f else 0.3f
                button.setOnClickListener {
                    pageIndex = (pageIndex + delta).coerceIn(0, lastPage(total))
                    render()
                    focusFirst()
                }
                pager.addView(button, LinearLayout.LayoutParams(ui.u(64), ui.u(34)))
            }
            arrow("◀", "Previous bookmark page", pageIndex > 0, -1)
            val counter = ui.text("${pageIndex + 1} / ${lastPage(total) + 1}", 15, Colors.SUBTLE)
            counter.gravity = Gravity.CENTER
            pager.addView(counter, LinearLayout.LayoutParams(ui.u(120), ui.u(34)))
            arrow("▶", "Next bookmark page", pageIndex < lastPage(total), 1)
            val pagerLp = LinearLayout.LayoutParams(WRAP, WRAP)
            pagerLp.topMargin = ui.u(10)
            content.addView(pager, pagerLp)
        }

        // Buttons under the grid
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER

        fun addButton(label: String, action: () -> Unit) {
            val b = ui.button(label, 16)
            b.gravity = Gravity.CENTER
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(ui.u(230), ui.u(46))
            if (bar.childCount > 0) lp.leftMargin = ui.u(12)
            bar.addView(b, lp)
            if (firstFocusable == null) firstFocusable = b
        }

        addButton("Search / address") { listener?.search() }
        if (f != null) {
            addButton("All folders") { back() }
            addButton("Manage folder") { listener?.manage(f) }
        } else {
            addButton("Manage bookmarks") { listener?.manage(null) }
        }

        val blp = LinearLayout.LayoutParams(WRAP, WRAP)
        blp.topMargin = ui.u(if (total > MAX_TILES) 10 else 22)
        content.addView(bar, blp)

        val hint = ui.text(
            "Menu: options    Select: open    Back: " + (if (f != null) "up one level" else "exit"),
            13, Colors.MUTED
        )
        hint.gravity = Gravity.CENTER_HORIZONTAL
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = ui.u(16)
        content.addView(hint, hlp)

        val version = ui.text("Buccaneer $versionLabel", 13, Colors.MUTED)
        version.gravity = Gravity.CENTER_HORIZONTAL
        val vlp = LinearLayout.LayoutParams(WRAP, WRAP)
        vlp.topMargin = ui.u(6)
        content.addView(version, vlp)
    }

    private fun addGrid(tiles: List<View>, reserveTwoRows: Boolean) {
        val cols = 5
        val gap = ui.u(12)
        val tileW = Math.min(ui.u(190), (ui.screenW - ui.u(80) - gap * (cols - 1)) / cols)
        val tileH = ui.u(104)
        val rows = if (reserveTwoRows) 2 else (tiles.size + cols - 1) / cols
        for (rowIndex in 0 until rows) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            val from = rowIndex * cols
            val count = if (reserveTwoRows) cols else minOf(cols, tiles.size - from)
            for (column in 0 until count) {
                val index = from + column
                val tile = if (index < tiles.size) tiles[index] else View(context).apply {
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val lp = LinearLayout.LayoutParams(tileW, tileH)
                if (column > 0) lp.leftMargin = gap
                row.addView(tile, lp)
            }
            val rlp = LinearLayout.LayoutParams(WRAP, tileH)
            rlp.topMargin = gap
            content.addView(row, rlp)
        }
        if (firstFocusable == null && tiles.isNotEmpty()) firstFocusable = tiles[0]
    }

    private fun bookmarkTile(b: Bookmark): View {
        val t = ui.tile(b.title, BookmarkStore.hostOf(b.url), 30, 15)
        t.setOnClickListener { listener?.openUrl(b.url) }
        return t
    }

    private fun folderTile(info: FolderInfo): View {
        val label = if (info.name.isEmpty()) "Unsorted" else info.name
        val count = if (info.count == 1) "1 bookmark" else "${info.count} bookmarks"
        val t = ui.tile(label, count, 30, 15)
        t.setOnClickListener {
            folderListPage = pageIndex
            folder = info.name
            pageIndex = 0
            render()
            focusFirst()
        }
        return t
    }

    companion object {
        private const val MAX_TILES = 10
        private fun lastPage(total: Int): Int = if (total <= 0) 0 else (total - 1) / MAX_TILES
    }
}
