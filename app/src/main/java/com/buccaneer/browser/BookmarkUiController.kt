package com.buccaneer.browser

import android.webkit.WebView
import android.net.Uri

/** Bookmark editing has no media/input state; prompts and panel arbitration are UI capabilities. */
interface BookmarkUiHost {
    val guard: PopupGuard
    val store: BookmarkStore
    val webView: WebView
    val startPage: StartPage
    val listPanel: ListPanel
    fun promptText(title: String, initial: String, onCancel: (() -> Unit)?, goLabel: String = "OK", onDone: (String) -> Unit)
    fun showMenu(title: String, items: List<MenuItem>, back: (() -> Unit)?)
    fun hideList()
    fun openUrl(url: String)
    fun toast(msg: String)
    fun exportBookmarks()
    fun importBookmarks()
    fun runFileOperation(work: () -> BookmarkJobs.Result)
    fun beginBookmarkList()
    fun prepareBookmarkList()
    fun syncFocusBlocking()
}
class BookmarkUiController(private val host: BookmarkUiHost) : BookmarkUiHost by host {
    var bookmarksFolder: String?=null; private set
    var movingBookmarkUrl: String?=null; private set
    fun stopMoving() { movingBookmarkUrl=null }
    fun resetSelection() { bookmarksFolder=null;movingBookmarkUrl=null }
    /** Menu > Add bookmark: choose where to save it. */
    fun startSaveBookmark() {
        if (BookmarkJobs.isBusy()) { toast("Wait for the bookmark transfer to finish"); return }
        val url = webView.url
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("Open a web page first")
            return
        }
        val name = BookmarkStore.suggestName(webView.title, url)
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Save (no folder)") { saveBookmark(name, url, "") })
        for (f in store.folderNames()) {
            items.add(MenuItem("Folder: $f") { saveBookmark(name, url, f) })
        }
        items.add(MenuItem("New folder...") {
            promptText("New folder name", "", null) { folderName ->
                saveBookmark(name, url, folderName)
            }
        })
        showMenu("Save \"$name\"", items, null)
    }

    fun editBookmarks(action: () -> Unit): Boolean {
        if (BookmarkJobs.isBusy()) { toast("Wait for the bookmark transfer to finish"); return false }
        action()
        return true
    }

    private fun saveBookmark(name: String, url: String, folder: String) {
        if (BookmarkJobs.isBusy()) { toast("Wait for the bookmark transfer to finish"); return }
        editBookmarks { store.add(name, url, folder) }
        // Auto-protect a bookmarked site: its off-site main-frame redirects (ad scripts)
        // are blocked, same as the built-in streaming sites. Never removes protection.
        try {
            val host = Uri.parse(url).host
            if (host != null) guard.protect(host)
        } catch (_: Exception) { }
        startPage.refresh()
        toast("Bookmarked: $name" + (if (folder.isEmpty()) "" else "  (folder $folder)"))
    }

    // ---- bookmark export / import
    // Legacy Fire OS: public Downloads. Android 10+: document picker where available, else MediaStore Downloads.
    /** Menu > Bookmarks: folders (when you use them) or all bookmarks, with an Options button per row. */
    fun showBookmarks(folder: String?, focusRow: Int) = showBookmarks(folder, focusRow, null)

    fun showBookmarks(folder: String?, focusRow: Int, movingUrl: String?) {
        beginBookmarkList()
        // A folder without bookmarks no longer exists.
        val view: String? = if (folder != null && store.count(folder) == 0) null else folder
        bookmarksFolder = view
        movingBookmarkUrl = movingUrl?.takeIf { store.find(it) != null }

        val rows = ArrayList<ListRow>()
        val title: String
        var empty = ""

        if (view == null && store.hasFolders()) {
            title = "Bookmark folders"
            val entries = store.entries(0)
            val offset = (focusRow.coerceAtLeast(0) / 50 * 50).coerceAtMost(((entries.size - 1).coerceAtLeast(0) / 50) * 50)
            for ((j, info) in entries.drop(offset).take(50).withIndex()) {
                val i = offset + j
                val label = if (info.name.isEmpty()) "Unsorted" else info.name
                val sub = if (info.count == 1) "1 bookmark" else "${info.count} bookmarks"
                var more: (() -> Unit)? = null
                if (info.name.isNotEmpty()) more = { showFolderOptions(info.name, i) }
                rows.add(ListRow(label, sub, { showBookmarks(info.name, 0) }, more))
            }
        } else {
            val list: List<Bookmark>
            if (view == null) {
                list = store.all()
                title = "Bookmarks"
            } else {
                list = store.inFolder(view)
                title = if (view.isEmpty()) "Unsorted" else view
            }
            empty = "No bookmarks yet. Open a website, press Menu and choose Add bookmark."
            val offset = (focusRow.coerceAtLeast(0) / 50 * 50).coerceAtMost(((list.size - 1).coerceAtLeast(0) / 50) * 50)
            for ((j, b) in list.drop(offset).take(50).withIndex()) {
                val i = offset + j
                rows.add(
                    ListRow(
                        b.title,
                        BookmarkStore.hostOf(b.url),
                        { if (movingBookmarkUrl != null) finishBookmarkMove() else openUrl(b.url) },
                        if (movingBookmarkUrl != null) null else ({ showBookmarkOptions(b, view, i) })
                    )
                )
            }
        }

        val totalRows = if (view == null && store.hasFolders()) store.entries(0).size else if (view == null) store.all().size else store.count(view)
        val offset = (focusRow.coerceAtLeast(0) / 50 * 50).coerceAtMost(((totalRows - 1).coerceAtLeast(0) / 50) * 50)
        val footer = ArrayList<MenuItem>()
        if (movingBookmarkUrl != null) {
            footer.add(MenuItem("Done moving") { finishBookmarkMove() })
        } else {
            if (offset > 0) footer.add(MenuItem("◀") { showBookmarks(view, offset - 50) })
            if (offset + 50 < totalRows) footer.add(MenuItem("▶") { showBookmarks(view, offset + 50) })
            if (view != null && store.hasFolders()) footer.add(MenuItem("All folders") { showBookmarks(null, 0) })
            footer.add(MenuItem("Export") { exportBookmarks() })
            footer.add(MenuItem("Import") { importBookmarks() })
            if (store.hasPreviousImport()) footer.add(MenuItem("Restore previous") {
                val bookmarks = store
                runFileOperation {
                    if (!bookmarks.undoImport()) throw java.io.IOException("No valid prior import")
                    BookmarkJobs.Result("Restored previous bookmark list", imported = true)
                }
            })
            footer.add(MenuItem("Close") { hideList() })
        }

        prepareBookmarkList()
        val moving = movingBookmarkUrl?.let { store.find(it) }
        val heading = if (moving != null) "Moving: ${moving.title}" else if (totalRows > 50) "$title • ${offset / 50 + 1}/${(totalRows + 49) / 50}" else title
        listPanel.show(heading, rows, empty, footer, focusRow - offset,
            if (moving != null) "Up / Down: move bookmark     Select / Back: done (saved automatically)" else null)
        listPanel.bringToFront()
        syncFocusBlocking()
    }

    fun showBookmarkOptions(b: Bookmark, folder: String?, index: Int) {
        if (BookmarkJobs.isBusy()) { toast("Wait for the bookmark transfer to finish"); return }
        hideList()
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Move to top") {
            editBookmarks { store.moveToTop(b.url) }
            showBookmarks(folder, 0)
        })
        items.add(MenuItem("Move up / down...") { showBookmarks(folder, index, b.url) })
        items.add(MenuItem("Rename") {
            promptText("Rename bookmark", b.title, { showBookmarks(folder, index) }) { newName ->
                editBookmarks { store.rename(b.url, newName) }
                showBookmarks(folder, index)
            }
        })
        items.add(MenuItem("Move to folder...") { showMoveToFolder(b, folder, index) })
        items.add(MenuItem("Delete") {
            if (editBookmarks { store.remove(b.url) }) toast("Deleted ${b.title}")
            showBookmarks(folder, Math.max(0, index - 1))
        })
        showMenu(b.title, items) { showBookmarks(folder, index) }
    }

    /** Ordering stays within the current folder, just like the stored bookmark order. */
    fun moveBookmarkStep(url: String, direction: Int) {
        val b = store.find(url) ?: run { finishBookmarkMove(); return }
        val list = store.inFolder(b.folder)
        val index = list.indexOfFirst { it.url == b.url }
        if (index < 0 || index + direction !in list.indices) return
        if (!editBookmarks { if (direction < 0) store.moveUp(url) else store.moveDown(url) }) return
        val updated = store.inFolder(b.folder).indexOfFirst { it.url == b.url }.coerceAtLeast(0)
        showBookmarks(bookmarksFolder, updated, url)
    }

    fun finishBookmarkMove() {
        val b = movingBookmarkUrl?.let { store.find(it) }
        val index = if (b == null) 0 else store.inFolder(b.folder).indexOfFirst { it.url == b.url }.coerceAtLeast(0)
        showBookmarks(bookmarksFolder, index)
        startPage.refresh()
    }

    private fun showMoveToFolder(b: Bookmark, folder: String?, index: Int) {
        val items = ArrayList<MenuItem>()
        if (b.folder.isNotEmpty()) {
            items.add(MenuItem("No folder") {
                editBookmarks { store.move(b.url, "") }
                showBookmarks(folder, Math.max(0, index - 1))
            })
        }
        for (f in store.folderNames()) {
            if (f != b.folder) {
                items.add(MenuItem("Folder: $f") {
                    editBookmarks { store.move(b.url, f) }
                    showBookmarks(folder, Math.max(0, index - 1))
                })
            }
        }
        items.add(MenuItem("New folder...") {
            promptText("New folder name", "", { showBookmarks(folder, index) }) { name ->
                editBookmarks { store.move(b.url, name) }
                showBookmarks(folder, Math.max(0, index - 1))
            }
        })
        showMenu("Move to...", items) { showBookmarkOptions(b, folder, index) }
    }

    private fun showFolderOptions(name: String, index: Int) {
        if (BookmarkJobs.isBusy()) { toast("Wait for the bookmark transfer to finish"); return }
        hideList()
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Move to top") {
            editBookmarks { store.moveFolderToTop(name) }
            showBookmarks(null, 0)
        })
        items.add(MenuItem("Move up") {
            editBookmarks { store.moveFolderUp(name) }
            showBookmarks(null, Math.max(0, index - 1))
        })
        items.add(MenuItem("Rename") {
            promptText("Rename folder", name, { showBookmarks(null, index) }) { newName ->
                editBookmarks { store.renameFolder(name, newName) }
                showBookmarks(null, index)
            }
        })
        items.add(MenuItem("Ungroup (keep the bookmarks)") {
            editBookmarks { store.ungroup(name) }
            showBookmarks(null, 0)
        })
        showMenu("Folder: $name", items) { showBookmarks(null, index) }
    }

}
