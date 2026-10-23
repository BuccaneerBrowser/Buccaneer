package com.buccaneer.browser

import android.content.Context
import android.net.Uri

/** Reads a text file from app/src/main/assets. */
fun readAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * Native half of the "Popup Guard - Streaming Mode" Chrome extension, ported to Android.
 *
 *  Extension piece                         ->  Where it lives in this app
 *  ------------------------------------------------------------------------------------------
 *  storage key popupGuardEnabled (def. ON)  ->  `enabled` below (SharedPreferences, default ON)
 *  popup.html / popup.js toggle + badge     ->  Menu > "Popup Guard: ON/OFF"
 *  content.js window.open override          ->  assets/popup_guard.js
 *  content.js _blank -> _self click rewrite ->  assets/popup_guard.js
 *  content.js killOverlays (every 1500 ms)  ->  assets/popup_guard.js
 *  background.js tabs.onCreated / windows.onCreated (close script-opened tabs/popups)
 *                                           ->  WebChromeClient.onCreateWindow returns false
 *  rules.json declarativeNetRequest rule    ->  blocksNavigation() + WebViewClient.shouldOverrideUrlLoading
 */
class PopupGuard(context: Context) {

    private val prefs = context.getSharedPreferences("popup_guard", Context.MODE_PRIVATE)

    @Volatile
    private var on: Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /** Read from the JavaScript bridge thread too, hence @Volatile. */
    val enabled: Boolean
        get() = on

    /** Flip the switch (persisted). Returns the new state. */
    fun toggle(): Boolean {
        on = !on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        return on
    }

    // ---------------------------------------------------------------- protected sites

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

    /** Add the site to (or remove it from) the list of sites whose off-site redirects are blocked. */
    fun toggleProtected(host: String) {
        val h = cleanHost(host)
        if (h.isEmpty() || isBuiltIn(h)) return
        val set = userDomains()
        val covering = set.filter { hostMatches(h, it) }
        if (covering.isNotEmpty()) set.removeAll(covering) else set.add(h)
        prefs.edit().putStringSet(KEY_PROTECTED, set).apply()
    }

    /**
     * Ensure a host is protected: add it to the user list if it is not already covered.
     * Never removes. Called when a site is bookmarked, so every bookmarked site gets
     * redirect protection without the user having to toggle it by hand.
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

    // ---------------------------------------------------------------- the rules.json rule

    /**
     * Port of rules.json: block main-frame navigations initiated by a page on a protected domain
     * (initiatorDomains) when the destination is a different site (excludedRequestDomains).
     * Subdomains count as the same site, exactly like declarativeNetRequest domain matching.
     * Only http(s) is considered, like urlFilter "|http".
     *
     * Navigations typed or tapped in the browser UI (address bar, bookmarks, start page) never
     * reach shouldOverrideUrlLoading, so they are never blocked, as with the extension.
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
         * The domain hard-coded in the extension's rules.json (initiatorDomains / excludedRequestDomains)
         * and content.js. To protect more sites permanently, add them here, or use the menu item
         * "Block redirects from <site>" while visiting the site.
         *
         * Streaming sites are protected by default: their ad scripts redirect the MAIN FRAME to an
         * off-site ad domain (e.g. flixer.gd -> ravishwalnut.qpon -> strategiboost.com). The popup
         * guard blocks popup windows but not main-frame redirects, so these sites need the
         * redirect-block. Same-site navigations and sub-resource loads (video, iframes) are never
         * affected — only off-site main-frame navigations are blocked.
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
