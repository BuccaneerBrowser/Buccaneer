package com.buccaneer.browser

import android.webkit.WebSettings

/**
 * Owns the UA identity decision, deliberately outside the Activity hub: the UA path must
 * evolve independently of UI/keyboard flows (Cloudflare work, 2026-11-14). Holds the
 * device-native UA and the desktop-layout variant; apply() writes whichever the setting
 * selects. Behavior-preserving extraction of former MainActivity state (defaultUa/desktopUa,
 * buildDesktopUa) — same strings, same ordering, same triggers.
 */
class UserAgentPolicy(private val webSettings: WebSettings) {
    var defaultUa: String = ""; private set
    var desktopUa: String = ""; private set

    fun initialize(baseUa: String) {
        defaultUa = baseUa
        desktopUa = buildDesktopUa(baseUa)
    }

    fun apply(desktopSite: Boolean) {
        webSettings.userAgentString = if (desktopSite) desktopUa else defaultUa
    }

    /** Chrome version from the device-native UA, for the diagnostics screen. */
    fun chromeVersion(): String =
        Regex("Chrome/([0-9.]+)").find(defaultUa)?.groupValues?.get(1) ?: "unknown"

    companion object {
        fun buildDesktopUa(ua: String): String {
            val match = Regex("Chrome/([0-9.]+)").find(ua)
            val version = match?.groupValues?.get(1) ?: "120.0.0.0"
            // Desktop-layout UA for the Desktop site setting: changes the reported identity only;
            // it does not turn Android WebView into desktop Chrome.
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }
    }
}
