package com.buccaneer.browser

import android.content.Context
import android.net.Uri

fun readAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * Native half of the "Popup Guard - Streaming Mode" Chrome extension, ported to Android.
 * The JS half (window.open override, _blank -> _self rewrite, overlay killing) lives in
 * assets/popup_guard.js; script-opened tabs/popups are closed via WebChromeClient.onCreateWindow.
 */
class PopupGuard(context: Context) {

    private val prefs = context.getSharedPreferences("popup_guard", Context.MODE_PRIVATE)

    @Volatile
    private var on: Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /** Read from the JavaScript bridge thread too, hence @Volatile. */
    val enabled: Boolean
        get() = on

    fun toggle(): Boolean {
        on = !on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        return on
    }

    private fun userDomains(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet())

    fun protectedDomains(): List<String> = DEFAULT_PROTECTED + userDomains().sorted()

    fun isProtected(host: String): Boolean {
        val h = cleanHost(host)
        return protectedDomains().any { hostMatches(h, it) }
    }

    /** Domains that ship with the app (7movies.ac, like rules.json) cannot be removed from the menu. */
    fun isBuiltIn(host: String): Boolean {
        val h = cleanHost(host)
        return DEFAULT_PROTECTED.any { hostMatches(h, it) }
    }

    fun toggleProtected(host: String) {
        val h = cleanHost(host)
        if (h.isEmpty() || isBuiltIn(h)) return
        val set = userDomains()
        val covering = set.filter { hostMatches(h, it) }
        if (covering.isNotEmpty()) set.removeAll(covering) else set.add(h)
        prefs.edit().putStringSet(KEY_PROTECTED, set).apply()
    }

    /**
     * Ensure a host is protected (never removes). Called when a site is bookmarked, so every
     * bookmarked site gets redirect protection without manual toggling.
     */
    fun protect(host: String) {
        val h = cleanHost(host)
        if (h.isEmpty() || isBuiltIn(h)) return
        val set = userDomains()
        if (set.none { hostMatches(h, it) }) {
            set.add(h)
            prefs.edit().putStringSet(KEY_PROTECTED, set).apply()
        }
    }

    /**
     * Port of rules.json: block main-frame navigations from a protected domain to a different
     * site (subdomains count as the same site; only http(s), like declarativeNetRequest).
     * Navigations typed or tapped in the browser UI never reach shouldOverrideUrlLoading, so
     * they are never blocked, as with the extension.
     */
    fun blocksNavigation(initiatorUrl: String?, target: Uri): Boolean {
        if (!on) return false
        val scheme = (target.scheme ?: return false).lowercase()
        if (scheme != "http" && scheme != "https") return false
        val targetHost = cleanHost(target.host ?: return false)
        val initiator = initiatorUrl ?: return false
        val initiatorHost = cleanHost(hostOf(initiator) ?: return false)
        val domain = protectedDomains().firstOrNull { hostMatches(initiatorHost, it) } ?: return false
        return !hostMatches(targetHost, domain)
    }

    private fun hostOf(url: String): String? =
        try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val KEY_ENABLED = "popupGuardEnabled"
        private const val KEY_PROTECTED = "protectedDomains"

        /**
         * Domains hard-coded in the extension's rules.json; add more here or via the menu item
         * "Block redirects from <site>". Streaming sites are protected by default: their ad
         * scripts redirect the MAIN FRAME to an off-site ad domain, and the popup guard blocks
         * popups but not main-frame redirects, so these sites need the redirect-block. Only
         * off-site main-frame navigations are blocked; same-site and sub-resource loads are
         * never affected.
         */
        val DEFAULT_PROTECTED: List<String> = listOf(
            "7movies.ac",
            "flixer.gd",
            "cinejoy.pk",
            "novahd.cc",
            "movy.sx",
            "toflix.co",
            "pantyflix.com"
        )

        fun hostMatches(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        fun cleanHost(host: String): String = host.lowercase().removePrefix("www.")
    }
}
