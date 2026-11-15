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

    /**
     * Sites the user has chosen to protect: the menu toggle "Block redirects from <site>"
     * or bookmarking a site. Remembered per site in prefs. There is no hardcoded list —
     * different users protect different sites, and any site can be toggled either way.
     */
    fun protectedDomains(): List<String> = userDomains().sorted()

    fun isProtected(host: String): Boolean {
        val h = cleanHost(host)
        return userDomains().any { hostMatches(h, it) }
    }

    /** User-controlled per-site toggle: any site, either direction. */
    fun toggleProtected(host: String) {
        val h = cleanHost(host)
        if (h.isEmpty()) return
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
        if (h.isEmpty()) return
        val set = userDomains()
        if (set.none { hostMatches(h, it) }) {
            set.add(h)
            prefs.edit().putStringSet(KEY_PROTECTED, set).apply()
        }
    }

    /**
     * Per-site, user-controlled main-frame policy. A site the user has NOT protected
     * navigates freely — a browser must let people click links and get places. A site the
     * user HAS protected is anchor-scoped fail-closed: only HTTP(S) destinations on the
     * anchor host or its subdomains, plus the single directed HTTPS-only migration
     * nippleflix.com -> ghostflix.st. The master Popup Guard toggle remains the global
     * opt-out. Scheme safety (non-HTTP(S) main-frame targets) and the missing-initiator
     * fail-closed case apply regardless of protection.
     */
    fun blocksNavigation(initiatorUrl: String?, target: Uri): Boolean {
        if (!on) return false
        val scheme = target.scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme != "http" && scheme != "https") return true
        val targetHost = cleanHost(target.host ?: return true)
        val source = initiatorUrl ?: return true
        val initiatorHost = cleanHost(hostOf(source) ?: return true)
        if (initiatorHost.isEmpty() || targetHost.isEmpty()) return true
        if (hostMatches(targetHost, initiatorHost)) return false
        if (!isProtected(initiatorHost)) return false
        return !(isBrandHost(initiatorHost, "nippleflix") && isBrandHost(targetHost, "ghostflix") && scheme == "https")
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

        fun hostMatches(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        /**
         * Brand host: the brand's apex with ANY TLD, plus any subdomain of it
         * (flixer.gd, flixer.su, sub.flixer.xyz). Site-specific fixes follow the
         * brand, so a mirror on a new TLD keeps the same behaviour.
         */
        fun isBrandHost(host: String, brand: String): Boolean {
            val h = cleanHost(host)
            if (h.isEmpty()) return false
            return "^(?:.*\\.)?$brand\\.[a-z0-9-]+$".toRegex().matches(h)
        }

        fun cleanHost(host: String): String =
            host.lowercase(java.util.Locale.ROOT).removeSuffix(".").removePrefix("www.")
    }
}
