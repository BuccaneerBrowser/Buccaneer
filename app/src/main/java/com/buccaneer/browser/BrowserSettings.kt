package com.buccaneer.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale

interface SettingsHost {
    val context: Context
    val prefs: SharedPreferences
    val webView: WebView
    val startPage: StartPage
    val guard: PopupGuard
    val lastPageUrl: String
    val blockedCount: Int
    val handler: Handler
    fun toast(msg: String)
    fun reloadPage()
    fun recreate()
    fun applyUserAgent()
    fun showMenuAt(title: String, items: List<MenuItem>, focusIndex: Int, back: (() -> Unit)?)
    fun showDiagnosticMenu()
    fun showMainMenu()
    fun ipv6ShieldState(): Boolean
    fun toggleIpv6Shield()
}
/** Settings are written here only; consumers receive read-only properties. */
class BrowserSettings(private val host: SettingsHost): SettingsHost by host {
    @Volatile var darkMode=true; private set
    var desktopSite=true; private set
    var autoplay=false; private set
    @Volatile var lowQuality=true; private set
    @Volatile var exclusiveVideo=true; private set
    var softwareRendering=false; private set
    fun loadGeneral() {
        darkMode=prefs.getBoolean("darkMode",true)
        // Default: desktop-layout UA (shipped behavior since 2.4.x). The mobile-UA
        // default broke movy.sx playback: its SPA rewrites the viewport meta to a
        // 1280-wide layout on a mobile identity, clipping the player off-screen on
        // the 960px window. Device-native UA remains available behind the toggle.
        desktopSite=prefs.getBoolean("desktopSite",true)
        autoplay=prefs.getBoolean("autoplay",true)
        lowQuality=prefs.getBoolean("lowQuality",true)
        exclusiveVideo=prefs.getBoolean("exclusiveVideo",true)
    }
    fun loadRendering() { softwareRendering=prefs.getBoolean("softwareRendering",false) }
    fun toggleGuard() {
        val on = guard.toggle()
        DiagnosticLog.record(context, "setting_popup_guard", JSONObject().put("enabled", on))
        webView.settings.javaScriptCanOpenWindowsAutomatically = !on
        toast("Popup Guard " + (if (on) "ON" else "OFF"))
    }

    /** Per-site redirect blocking: user-controlled for ANY site, remembered in prefs. */
    fun toggleProtectedSite(site: String) {
        guard.toggleProtected(site)
        DiagnosticLog.record(context, "setting_redirect_block_site", JSONObject().put("site", site).put("blocked", guard.isProtected(site)))
        toast("Redirects from $site: " + (if (guard.isProtected(site)) "blocked" else "allowed"))
    }

    fun toggleDesktop() {
        desktopSite = !desktopSite
        DiagnosticLog.record(context, "setting_desktop", JSONObject().put("enabled", desktopSite))
        prefs.edit().putBoolean("desktopSite", desktopSite).apply()
        applyUserAgent()
        if (!startPage.shown()) reloadPage()
        toast("Desktop site " + (if (desktopSite) "ON" else "OFF"))
    }

    /** True when this device's WebView can darken web pages at all. */
    @Suppress("DEPRECATION")
    fun darkModeSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ||
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
        } catch (e: Exception) {
            false
        }

    /** Dark mode for web pages: the app theme is dark, so dark-styled sites use it; a WebView that can darken pages does so for the rest. */
    @Suppress("DEPRECATION")
    fun applyDarkMode() {
        val s = webView.settings
        try {
            // VERIFIED WORKING: FORCE_DARK_ON + strategy 2 (PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
            // is the only configuration where the Flixer loads without crashing and video-site
            // buttons work; omitting setForceDarkStrategy breaks the Flixer. The SPA portal stays
            // light (VitePress color-scheme) — do NOT inject CSS to fix it; every attempt crashed the PowerVR GPU.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(s, if (darkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(s, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
                }
                DiagnosticLog.record(context, "dark_mode_applied", JSONObject().apply {
                    put("method", "force_dark_strategy_2")
                    put("enabled", darkMode)
                })
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, darkMode)
                DiagnosticLog.record(context, "dark_mode_applied", JSONObject().apply {
                    put("method", "algorithmic_darkening")
                    put("enabled", darkMode)
                })
            } else {
                DiagnosticLog.record(context, "dark_mode_applied", JSONObject().apply {
                    put("method", "none_supported")
                    put("enabled", darkMode)
                })
            }
        } catch (e: Exception) {
            // dark mode is cosmetic; never let it stop the browser from starting
        }
    }

    private fun toggleDark() {
        if (!darkModeSupported()) {
            toast("This device's WebView cannot darken web pages")
            return
        }
        darkMode = !darkMode
        prefs.edit().putBoolean("darkMode", darkMode).apply()
        applyDarkMode()
        if (!startPage.shown()) reloadPage()
        toast("Dark mode " + (if (darkMode) "ON" else "OFF"))
    }

    private fun toggleAutoplay() {
        autoplay = !autoplay
        DiagnosticLog.record(context, "setting_autoplay", JSONObject().put("enabled", autoplay))
        prefs.edit().putBoolean("autoplay", autoplay).apply()
        webView.settings.mediaPlaybackRequiresUserGesture = !autoplay
        toast("Autoplay without a click " + (if (autoplay) "ON" else "OFF"))
    }

    private fun toggleLowQuality() {
        lowQuality = !lowQuality
        DiagnosticLog.record(context, "setting_low_quality", JSONObject().put("enabled", lowQuality))
        prefs.edit().putBoolean("lowQuality", lowQuality).apply()
        toast("Prefer low quality streams " + (if (lowQuality) "ON" else "OFF"))
    }

    private fun toggleExclusive() {
        exclusiveVideo = !exclusiveVideo
        DiagnosticLog.record(context, "setting_exclusive_video", JSONObject().put("enabled", exclusiveVideo))
        prefs.edit().putBoolean("exclusiveVideo", exclusiveVideo).apply()
        toast("One video at a time " + (if (exclusiveVideo) "ON" else "OFF"))
    }

    private fun toggleSoftwareRendering() {
        softwareRendering = !softwareRendering
        DiagnosticLog.record(context, "setting_software_rendering", JSONObject().put("enabled", softwareRendering))
        prefs.edit().putBoolean("softwareRendering", softwareRendering).apply()
        // Save the current URL so onCreate can restore it after the WebView is recreated.
        val url = lastPageUrl
        if (!softwareRendering && (try { val h = Uri.parse(url).host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: ""; PopupGuard.isBrandHost(h, "flixer") } catch (_: Exception) { false })) {
            toast("Turning OFF on flixer will crash - navigate away first")
            // Don't recreate; let the user navigate away first.
            softwareRendering = true  // revert
            prefs.edit().putBoolean("softwareRendering", true).apply()
            return
        }
        if (url.startsWith("http")) prefs.edit().putString("restoreAfterRecreateUrl", url).apply()
        toast("Software rendering " + (if (softwareRendering) "ON" else "OFF") + " - reloading")
        handler.post { recreate() }
    }


    /** Menu > Settings. Toggles reopen the menu on the same row so several can be changed in a row. */
    fun showSettings(focus: Int) {
        val items = ArrayList<MenuItem>()
        val darkLabel = if (!darkModeSupported()) "not supported here" else if (darkMode) "ON" else "OFF"
        items.add(MenuItem("Dark mode: $darkLabel") {
            toggleDark()
            showSettings(0)
        })
        items.add(MenuItem("Desktop site: " + (if (desktopSite) "ON" else "OFF")) {
            toggleDesktop()
            showSettings(1)
        })
        items.add(MenuItem("Autoplay without a click: " + (if (autoplay) "ON" else "OFF")) {
            toggleAutoplay()
            showSettings(2)
        })
        items.add(MenuItem("Prefer low quality streams: " + (if (lowQuality) "ON" else "OFF")) {
            toggleLowQuality()
            showSettings(3)
        })
        items.add(MenuItem("One video at a time: " + (if (exclusiveVideo) "ON" else "OFF")) {
            toggleExclusive()
            showSettings(4)
        })
        items.add(MenuItem("Software rendering: " + (if (softwareRendering) "ON" else "OFF")) {
            toggleSoftwareRendering()
            showSettings(5)
        })
        items.add(MenuItem("Popup Guard: " + (if (guard.enabled) "ON" else "OFF") +
            (if (blockedCount > 0) "  ($blockedCount blocked)" else "")) {
            toggleGuard()
            showSettings(6)
        })
        items.add(MenuItem("IPv6 preference: " + (if (ipv6ShieldState()) "ON" else "OFF")) {
            toggleIpv6Shield()
            showSettings(7)
        })
        items.add(MenuItem("Diagnostics...") { showDiagnosticMenu() })
        showMenuAt("Settings", items, focus) { showMainMenu() }
    }

}
