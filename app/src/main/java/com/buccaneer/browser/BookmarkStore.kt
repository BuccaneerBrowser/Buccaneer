package com.buccaneer.browser

import android.annotation.SuppressLint

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** folder == "" means "not in a folder" (shown as "Unsorted" once folders exist). */
data class Bookmark(val title: String, val url: String, val folder: String)

data class FolderInfo(val name: String, val count: Int)

/**
 * Bookmarks live in one ordered list. "Top 10" on the start page simply means the first 10 entries
 * of that list (or the first 8 folders), and the Bookmarks screen has Move up / Move to top
 * so you decide what is on top.
 *
 * Folders have their own order. A folder exists only while it contains at least one bookmark.
 */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val items = ArrayList<Bookmark>()
    private val folderOrder = ArrayList<String>()
    private val folderCounts = HashMap<String, Int>()
    private data class Snapshot(val items: List<Bookmark>, val folders: List<String>, val counts: Map<String,Int>)
    @Volatile private var published = Snapshot(emptyList(),emptyList(),emptyMap())
    private fun publish() { published=Snapshot(ArrayList(items),ArrayList(folderOrder),HashMap(folderCounts)) }

    init {
        load()
        publish()
    }

    // ------------------------------------------------------------------ reading

    fun all(): List<Bookmark> = ArrayList(published.items)

    fun folderNames(): List<String> = ArrayList(published.folders)

    fun hasFolders(): Boolean = published.folders.isNotEmpty()

    fun inFolder(folder: String): List<Bookmark> = published.items.filter { it.folder == folder }

    fun count(folder: String): Int = published.counts[folder] ?: 0

    /**
     * Folders for the start page / folder list: real folders in order, then "Unsorted" (name "")
     * for bookmarks that are not in any folder. max <= 0 means no limit.
     */
    fun entries(max: Int): List<FolderInfo> {
        val out = ArrayList<FolderInfo>()
        val view = published
        for (f in view.folders) out.add(FolderInfo(f, view.counts[f] ?: 0))
        val loose = view.counts[""] ?: 0
        if (out.isNotEmpty() && loose > 0) out.add(FolderInfo("", loose))
        if (max > 0 && out.size > max) return ArrayList(out.subList(0, max))
        return out
    }

    fun find(url: String): Bookmark? = published.items.firstOrNull { sameUrl(it.url, url) }

    fun contains(url: String): Boolean = find(url) != null

    // ------------------------------------------------------------------ bookmarks

    @Synchronized
    fun add(title: String, url: String, folder: String) {
        val b = Bookmark(title, url, folder.trim())
        val idx = indexOf(url)
        if (idx >= 0) items[idx] = b else items.add(b)
        commit()
    }

    @Synchronized
    fun remove(url: String) {
        items.removeAll { sameUrl(it.url, url) }
        commit()
    }

    @Synchronized
    fun rename(url: String, title: String) {
        val i = indexOf(url)
        val t = title.trim()
        if (i < 0 || t.isEmpty()) return
        items[i] = items[i].copy(title = t)
        commit()
    }

    @Synchronized
    fun move(url: String, folder: String) {
        val i = indexOf(url)
        if (i < 0) return
        items[i] = items[i].copy(folder = folder.trim())
        commit()
    }

    /** Swap with the previous bookmark in the same folder. */
    @Synchronized
    fun moveUp(url: String) {
        val i = indexOf(url)
        if (i <= 0) return
        val f = items[i].folder
        var j = i - 1
        while (j >= 0 && items[j].folder != f) j--
        if (j < 0) return
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
        commit()
    }

    /** Swap with the next bookmark in the same folder. */
    @Synchronized
    fun moveDown(url: String) {
        val i = indexOf(url)
        if (i < 0) return
        val f = items[i].folder
        var j = i + 1
        while (j < items.size && items[j].folder != f) j++
        if (j >= items.size) return
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
        commit()
    }

    @Synchronized
    fun moveToTop(url: String) {
        val i = indexOf(url)
        if (i <= 0) return
        val b = items.removeAt(i)
        items.add(0, b)
        commit()
    }

    // ------------------------------------------------------------------ folders

    @Synchronized
    fun renameFolder(oldName: String, newName: String) {
        val n = newName.trim()
        if (oldName.isEmpty() || n.isEmpty()) return
        for (i in items.indices) {
            if (items[i].folder == oldName) items[i] = items[i].copy(folder = n)
        }
        val k = folderOrder.indexOf(oldName)
        if (k >= 0) folderOrder[k] = n
        commit()
    }

    /** Remove the folder but keep its bookmarks (they become Unsorted). */
    @Synchronized
    fun ungroup(folder: String) {
        for (i in items.indices) {
            if (items[i].folder == folder) items[i] = items[i].copy(folder = "")
        }
        commit()
    }

    @Synchronized
    fun moveFolderUp(folder: String) {
        val i = folderOrder.indexOf(folder)
        if (i <= 0) return
        folderOrder.removeAt(i)
        folderOrder.add(i - 1, folder)
        commit()
    }

    @Synchronized
    fun moveFolderToTop(folder: String) {
        val i = folderOrder.indexOf(folder)
        if (i <= 0) return
        folderOrder.removeAt(i)
        folderOrder.add(0, folder)
        commit()
    }

    // ------------------------------------------------------------------ persistence

    private fun indexOf(url: String): Int = items.indexOfFirst { sameUrl(it.url, url) }

    private fun commit() {
        tidy()
        save()
        publish()
    }

    /** Drop empty folders, drop duplicates, and add folders that are used but missing from the order. */
    private fun tidy() {
        folderCounts.clear()
        for (b in items) folderCounts[b.folder] = (folderCounts[b.folder] ?: 0) + 1
        val used = LinkedHashSet<String>()
        for (b in items) if (b.folder.isNotEmpty()) used.add(b.folder)
        val newOrder = LinkedHashSet<String>()
        for (f in folderOrder) if (used.contains(f)) newOrder.add(f)
        newOrder.addAll(used)
        folderOrder.clear()
        folderOrder.addAll(newOrder)
    }

    /** The whole store as the same JSON the app saves internally (for export). */
    @Synchronized
    fun exportJson(): String {
        val arr = JSONArray()
        for (b in items) {
            val o = JSONObject()
            o.put("t", b.title)
            o.put("u", b.url)
            o.put("f", b.folder)
            arr.put(o)
        }
        val fo = JSONArray()
        for (f in folderOrder) fo.put(f)
        val root = JSONObject()
        root.put("folders", fo)
        root.put("items", arr)
        return root.toString()
    }

    /** Never publish a backup that this version would reject on restore. */
    @Synchronized
    fun exportPortableJson(): String {
        if (items.size > 20000 || folderOrder.size > 20000) throw java.io.IOException("Backup exceeds 20,000 records")
        for (b in items) {
            val uri = Uri.parse(b.url)
            if (b.title.length > 2048 || b.folder.length > 256 || b.url.length > 8192 ||
                uri.scheme?.lowercase(java.util.Locale.ROOT) !in listOf("http", "https") || uri.host.isNullOrEmpty())
                throw java.io.IOException("Backup contains unsupported or oversized fields; shorten the affected bookmark first")
        }
        val raw = exportJson()
        if (raw.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw java.io.IOException("Normalized backup exceeds 1 MiB")
        return raw
    }

    /** Replace the store with an exported JSON. Returns false if the text is not a bookmarks export. */
    // Worker transaction: commit result is needed for durable rollback; apply cannot report failure.
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun importJson(raw: String): Boolean {
        val previousData = prefs.getString("data", null)
        val previousUndo = prefs.getString("previousImport", null)
        var persistenceAttempted = false
        val previousItems = ArrayList(items)
        val previousFolders = ArrayList(folderOrder)
        try {
            if (raw.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return false
            if (!JsonInputBudget.allows(raw)) return false
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return false
            if (arr.length() > 20000) return false
            val newItems = ArrayList<Bookmark>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = (o.opt("u") as? String)?.trim() ?: return false
                val parsed = Uri.parse(u)
                if (u.isEmpty() || u.length > 8192 || parsed.scheme?.lowercase(java.util.Locale.ROOT) !in listOf("http", "https") || parsed.host.isNullOrEmpty()) return false
                if (o.has("t") && o.opt("t") !is String) return false
                if (o.has("f") && o.opt("f") !is String) return false
                if (o.optString("t", u).length > 2048 || o.optString("f", "").length > 256) return false
                if (seen.add(urlKey(u))) newItems.add(Bookmark(o.optString("t", u), u, o.optString("f", "").trim()))
            }
            val newFolders = ArrayList<String>()
            if (root.has("folders") && root.opt("folders") !is JSONArray) return false
            val fo = root.optJSONArray("folders")
            if (fo != null) {
                if (fo.length() > 20000) return false
                for (i in 0 until fo.length()) {
                    val folder = fo.opt(i) as? String ?: return false
                    if (folder.length > 256) return false
                    newFolders.add(folder.trim())
                }
            }
            items.clear()
            items.addAll(newItems)
            folderOrder.clear()
            folderOrder.addAll(newFolders)
            tidy()
            val previous = prefs.getString("data", "{\"items\":[]}")
            val encoded = exportPortableJson()
            persistenceAttempted = true
            if (!prefs.edit().putString("previousImport", previous).putString("data", encoded).commit()) throw java.io.IOException("Cannot persist import")
            publish()
            return true
        } catch (e: Exception) {
            if (persistenceAttempted) try { prefs.edit().putString("data", previousData).putString("previousImport", previousUndo).commit() } catch (_: Exception) {}
            items.clear(); items.addAll(previousItems)
            folderOrder.clear(); folderOrder.addAll(previousFolders)
            tidy()
            return false
        }
    }

    private fun save() {
        prefs.edit().putString("data", exportJson()).apply()
    }

    private fun load() {
        items.clear()
        folderOrder.clear()
        val raw = prefs.getString("data", null) ?: return
        if (!JsonInputBudget.allows(raw)) return // Preserve malformed stored bytes; never recurse into them.
        try {
            val root = JSONObject(raw)
            val fo = root.optJSONArray("folders")
            if (fo != null) {
                for (i in 0 until fo.length()) folderOrder.add(fo.getString(i))
            }
            val arr = root.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.optString("u", "")
                    if (u.isNotEmpty()) items.add(Bookmark(o.optString("t", u), u, o.optString("f", "")))
                }
            }
        } catch (e: Exception) {
            items.clear()
            folderOrder.clear()
        }
        tidy()
    }

    // ------------------------------------------------------------------ helpers

    fun hasPreviousImport(): Boolean = prefs.contains("previousImport")
    @Synchronized fun undoImport(): Boolean {
        val value = prefs.getString("previousImport", null) ?: return false
        return importJson(value)
    }

    companion object {
        fun shared(context: Context): BookmarkStore =
            (context.applicationContext as BuccaneerApplication).bookmarks

        private val SEPARATORS = listOf(
            " | ", " - ", " \u2013 ", " \u2014 ", " \u00B7 ", " \u2022 ", " :: ", " \u00BB "
        )

        fun hostOf(url: String): String {
            val h: String? = try {
                Uri.parse(url).host
            } catch (e: Exception) {
                null
            }
            return (h ?: url).removePrefix("www.")
        }

        fun sameUrl(a: String, b: String): Boolean = urlKey(a) == urlKey(b)

        private fun urlKey(raw: String): String {
            val trimmed = raw.trim()
            return try {
                val uri = Uri.parse(trimmed)
                val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
                val host = uri.host?.lowercase(java.util.Locale.ROOT)
                if (scheme !in listOf("http", "https") || host.isNullOrEmpty()) trimmed
                else {
                    val port = uri.port
                    val suffix = if (port < 0 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port"
                    val authority = uri.encodedAuthority ?: ""
                    val user = if (authority.contains('@')) authority.substringBeforeLast('@') + "@" else ""
                    val path = uri.encodedPath?.ifEmpty { "/" } ?: "/"
                    "$scheme://$user$host$suffix$path" +
                        (uri.encodedQuery?.let { "?$it" } ?: "") + (uri.encodedFragment?.let { "#$it" } ?: "")
                }
            } catch (_: Exception) { trimmed }
        }

        /**
         * Short "website name" for a new bookmark. Page titles like "Home | Netflix" or
         * "Movie (2023) - Watch Online - 7movies" are cut down to the part that matches the
         * site address ("Netflix", "7movies"); otherwise a short title or the host name is used.
         * The name can be changed later with Rename.
         */
        fun suggestName(title: String?, url: String): String {
            val host = hostOf(url)
            val t = (title ?: "").replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty() || t.startsWith("http://") || t.startsWith("https://") ||
                t.equals("about:blank", ignoreCase = true)
            ) {
                return hostName(host)
            }

            val hostKey = host.lowercase().filter { it.isLetterOrDigit() }
            var parts: List<String> = listOf(t)
            for (sep in SEPARATORS) parts = parts.flatMap { it.split(sep) }

            var best = ""
            for (p in parts) {
                val part = p.trim()
                val key = part.lowercase().filter { it.isLetterOrDigit() }
                if (key.length >= 3 && hostKey.contains(key) && part.length > best.length) best = part
            }
            if (best.isNotEmpty()) return best
            if (t.length <= 24) return t
            return hostName(host)
        }

        /** "7movies.ac" -> "7movies", "www.bbc.co.uk" -> "bbc". */
        private fun hostName(host: String): String {
            val labels = host.split('.').filter { it.isNotEmpty() }
            if (labels.size <= 1) return host
            var drop = 1
            val last = labels[labels.size - 1]
            val second = labels[labels.size - 2]
            val secondLevel = listOf("co", "com", "org", "net", "gov", "edu", "ac")
            if (labels.size >= 3 && last.length == 2 && secondLevel.contains(second)) drop = 2
            val kept = labels.subList(0, labels.size - drop).joinToString(".")
            if (kept.isEmpty()) return host
            return kept.replaceFirstChar { it.uppercase() }
        }
    }
}
