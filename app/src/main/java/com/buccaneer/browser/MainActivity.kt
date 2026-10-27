package com.buccaneer.browser

import android.annotation.SuppressLint

import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// Reuse the same tiny transparent image for blocked TMDB requests. A new stream is
// created per response, but the PNG bytes are decoded only once.
private val TRANSPARENT_PIXEL_PNG_1X1: ByteArray by lazy {
    android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=",
        android.util.Base64.DEFAULT
    )
}

/**
 * Synchronous image fetch for the hero-downscale intercept. Called from
 * shouldInterceptRequest (Chromium IO thread, not the main thread), so a bounded
 * blocking GET is acceptable. Returns (bytes, mimeType) or null on any failure —
 * the caller then falls back to letting the original request proceed.
 */
private fun fetchImageBytes(url: String, timeoutMs: Long): Pair<ByteArray, String>? {
    return try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = conn.responseCode
        if (code != 200) { conn.disconnect(); return null }
        val ct = (conn.contentType ?: "image/jpeg").lowercase(Locale.US)
        if (!ct.startsWith("image/")) { conn.disconnect(); return null }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (bytes.size < 1024) return null // too small to be a real image
        bytes to ct
    } catch (e: Throwable) {
        null
    }
}

/** evaluateJavascript() returns JSON text; turn a JSON string result into the plain string (null for null). */
fun jsString(raw: String?): String? {
    if (raw == null || raw == "null" || raw.length > 1024 * 1024 || !JsonInputBudget.allows(raw)) return null
    val decoded = try {
        JSONArray("[$raw]").getString(0)
    } catch (e: Exception) {
        raw
    }
    // Many helper results are JSON objects encoded inside a JSON string. Checking
    // only the outer transport would miss recursion in the subsequently parsed body.
    var start = 0
    while (start < decoded.length && (decoded[start].isWhitespace() || decoded[start] == '\ufeff')) start++
    val first = decoded.getOrNull(start)
    val next = decoded.getOrNull(start + 1)
    // Android's parser also accepts comments before an object/array. Those must
    // not bypass the decoded-body budget; ordinary scalar strings stay intact.
    val jsonLike = first == '{' || first == '[' || first == '#' || (first == '/' && (next == '/' || next == '*'))
    return if (jsonLike && !JsonInputBudget.allows(decoded)) null else decoded
}

fun formatTime(totalSeconds: Double): String {
    val s = Math.max(0L, totalSeconds.toLong())
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%d:%02d", m, sec)
    }
}

/**
 * Fire TV browser. Everything is operated with the remote:
 *
 *  - Start page: top 10 bookmarks (or top 10 folders) as buttons; D-pad moves, Select opens.
 *  - Browsing: D-pad moves an on-screen pointer, Select clicks, pointer at the screen edge scrolls.
 *  - Keypad: Menu > Address/search, or tap a text field, or hold Select.
 *  - Video: Play/Pause, Rew/FF and volume keys control the page's video; full screen has its own keys.
 *
 * All remote keys arrive in dispatchKeyEvent() and are routed by what is on screen.
 */
class MainActivity : Activity(), BookmarkJobs.Listener {

    private enum class TextMode { NONE, PROMPT, PAGE }

    private lateinit var ui: Ui
    private lateinit var guard: PopupGuard
    private lateinit var store: BookmarkStore
    private lateinit var audio: AudioManager

    private lateinit var root: FrameLayout
    private lateinit var webView: TvWebView
    private lateinit var progress: BarView
    private lateinit var cursor: CursorView
    private lateinit var startPage: StartPage
    private lateinit var menu: MenuPanel
    private lateinit var listPanel: ListPanel
    private lateinit var textPanel: TextPanel
    private lateinit var keypad: Keypad
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var osd: Osd

    private val handler = Handler(Looper.getMainLooper())
    private val navigationHandoff = NavigationHandoff(
        { task, delay -> handler.postDelayed(task, delay) }, { handler.removeCallbacks(it) }
    )
    private data class Heartbeat(val epoch: Long, val token: String)
    private data class BlockedBatch(val epoch: Long, val what: String, val count: Int = 1)
    private val heartbeatUi = UiCoalescer<Heartbeat>(
        post = { handler.post(it) }, remove = { handler.removeCallbacks(it) }
    ) { value ->
        if (value.epoch == pageEpoch && value.token == documentToken && pageAvailable() && foreground)
            playbackHeartbeatAt = SystemClock.elapsedRealtime()
    }
    private val blockedUi = UiCoalescer<BlockedBatch>(
        post = { handler.post(it) }, remove = { handler.removeCallbacks(it) },
        merge = { old, newer ->
            if (old.epoch != newer.epoch) newer
            else newer.copy(count = (old.count.toLong() + newer.count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    ) { value ->
        if (value.epoch == pageEpoch && pageAvailable()) {
            DiagnosticLog.record(this, "popup_blocked", JSONObject().put("what", value.what).put("count", value.count))
            noteBlocked(value.what, value.count)
        }
    }
    private lateinit var dimOverlay: View
    private val idlePolicy = IdleDimPolicy()
    private var rendererRecreatePosted = false
    @Volatile private var foreground = false
    private var lastUserInputAt = 0L
    private var dimmed = false
    private var wakeKey: Int? = null
    private var wakeTouch = false
    private var scrollMode = false
    @Volatile private var playbackHeartbeatAt = 0L
    @Volatile private var inputFrame = ""
    private var inputFieldState = ""
    private var inputFieldId = ""
    private var inputFocusConfirmed = true
    private data class PageEdit(val action: String, val text: String, val session: Long,
        val token: String, val frame: String, val field: String)
    private val pageEdits = java.util.ArrayDeque<PageEdit>()
    private var pageEditActive: PageEdit? = null
    private var nativeEditCancel: (() -> Unit)? = null
    private var keyboardDismissedUntil = 0L
    private var fmhyThemeJs = ""
    private var tvUiJs = ""
    private var seekClickJs = ""
    private var scrollXPending = 0
    private var scrollYPending = 0
    private var scrollPending = false
    private val seekRepeat = SeekRepeatPolicy()
    private var seekGeneration = 0L
    private var idleQueryPending = false
    private val idleCheck = object : Runnable {
        override fun run() {
            if (!foreground || activityDestroyed) return
            val now = SystemClock.elapsedRealtime()
            val playing = (playbackHeartbeatAt > 0L && now - playbackHeartbeatAt < 12_000L) ||
                (::audio.isInitialized && audio.isMusicActive)
            if (playing) updateIdle(true)
            else if (startPage.shown() || !pageAvailable()) updateIdle(false)
            else if (now - lastUserInputAt >= 60_000L && !idleQueryPending) {
                idleQueryPending = true
                eval("(function(){try{if(window.__tvUi&&__tvUi.playback())return 1;" +
                    "if(window.__fb){var s=JSON.parse(__fb.state());return s.f===1?(s.p===0?1:0):(s.fr===1?-1:0);}" +
                    "return -1;}catch(e){return -1;}})()") { raw ->
                    idleQueryPending = false
                    if (!foreground) return@eval
                    when (jsString(raw)) {
                        "0" -> updateIdle(false)
                        "1" -> updateIdle(true)
                        else -> updateIdle(true) // Unknown/opaque player: keep bright, never assume stopped.
                    }
                }
            }
            handler.postDelayed(this, 10_000L)
        }
    }

    private fun setDimmed(value: Boolean) {
        if (!::dimOverlay.isInitialized) return
        dimmed = value
        dimOverlay.visibility = if (value) View.VISIBLE else View.GONE
        if (value) dimOverlay.bringToFront()
    }

    private fun updateIdle(playing: Boolean) {
        if (!foreground || activityDestroyed) return
        val now = SystemClock.elapsedRealtime()
        setDimmed(idlePolicy.shouldDim(now, playing))
    }

    private fun noteUserInput() {
        cancelPendingFullscreen()
        interactionSerial++
        resetIdleActivity()
    }

    private fun resetIdleActivity() {
        lastUserInputAt = SystemClock.elapsedRealtime()
        idlePolicy.activity(lastUserInputAt)
        setDimmed(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wakeTouch = dimmed
            if (!wakeTouch) lastTapAt = SystemClock.uptimeMillis()
            noteUserInput()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && !wakeTouch) noteUserInput()
        if (wakeTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) wakeTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val wasDimmed = dimmed
        if (event.actionMasked == MotionEvent.ACTION_SCROLL || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) noteUserInput()
        return if (wasDimmed) true else super.dispatchGenericMotionEvent(event)
    }

    private fun focusFromPage(state: String?) {
        hideIme()
        if (!pageAvailable() || !foreground || SystemClock.elapsedRealtime() < keyboardDismissedUntil) return
        // Media/D-pad activity and activity resume are not a request to type. A
        // real recent click is required; delayed search panels still have two seconds.
        val tapAge = SystemClock.uptimeMillis() - lastTapAt
        if (lastTapAt <= 0L || tapAge !in 0L..2000L || state == null) return
        try { if (JSONObject(state).optInt("ok", 0) != 1) return } catch (_: Exception) { return }
        // Discovery notifications never overwrite a pinned editor's qualified state.
        if (textMode != TextMode.NONE || customView != null || cssFullscreenActive || menu.shown() || listPanel.shown() || textPanel.shown() || startPage.shown()) return
        if (inputFrame.isEmpty()) inputFieldState = state
        openPageKeypad()
    }
    private var activityDestroyed = false
    private var webViewDestroyed = false
    private var recoveringRenderer = false
    @Volatile private var pageEpoch = 0L
    @Volatile private var documentToken = java.util.UUID.randomUUID().toString()
    private var playerIntent = 0L
    private var editorSession = 0L
    private var interactionSerial = 0L
    private val activityInstance = java.util.UUID.randomUUID().toString()
    private var pendingFullscreen = false
    private var cssFullscreenActive = false
    private var seekInFlight = false
    private var queuedSeek = 0
    private val pendingEvaluations = HashMap<Long, Runnable>()
    private var evaluationId = 0L
    private var osdRefresh: Runnable? = null
    private var osdRequest = 0L
    private val popupViews = HashSet<WebView>()
    private val popupTimeouts = HashMap<WebView, Runnable>()


    private fun pageAvailable(): Boolean = ::webView.isInitialized &&
        !webViewDestroyed && !activityDestroyed && !recoveringRenderer

    private fun postForPage(delayMs: Long, action: () -> Unit) {
        val epoch = pageEpoch
        handler.postDelayed({ if (pageAvailable() && foreground && epoch == pageEpoch) action() }, delayMs)
    }

    private fun cancelOsdRefresh() {
        osdRefresh?.let { handler.removeCallbacks(it) }
        osdRefresh = null
        osdRequest++
    }

    private fun disposePopup(view: WebView) {
        popupTimeouts.remove(view)?.let { handler.removeCallbacks(it) }
        if (popupViews.remove(view)) {
            try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
            try { view.destroy() } catch (_: Throwable) {}
            DiagnosticLog.record(this, "popup_webview_destroyed")
        }
    }

    private fun destroyBrowser(rendererGone: Boolean) {
        if (!::webView.isInitialized || webViewDestroyed) return
        finishPendingTap(cancel = true, deliver = !rendererGone)
        webViewDestroyed = true
        pageEpoch++
        resetDocumentWork()
        leaveFullscreen(true)
        osd.hideNow()
        cancelOsdRefresh()
        try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Throwable) {}
        // No calls other than destroy() on a WebView whose renderer has died.
        if (!rendererGone) {
            try { webView.stopLoading() } catch (_: Throwable) {}
            try { webView.removeJavascriptInterface("Buccaneer") } catch (_: Throwable) {}
        }
        try { webView.destroy() } catch (_: Throwable) {}
    }

    // Persist before retrying a page that may immediately crash again.
    @SuppressLint("ApplySharedPref")
    private fun clearCrashRecovery() {
        crashReloadCount = 0
        prefs.edit().remove("lastCrashUrl").remove("crashWindowStartedAt")
            .putInt("crashReloadCount", 0).commit()
    }

    private fun showCrashRecovery(url: String) {
        showMenu("Page recovery", listOf(
            MenuItem("Retry the page") { clearCrashRecovery(); openUrl(url) },
            MenuItem("Return home") { clearCrashRecovery(); showStartPage(true) }
        )) { clearCrashRecovery(); showStartPage(true) }
    }

    // ---- saved settings (Menu > Settings)
    private lateinit var prefs: SharedPreferences
    @Volatile private var darkMode = true
    private var desktopSite = true
    private var autoplay = false

    // ---- low quality stream preference (the page scripts read this through the bridge, hence @Volatile)
    @Volatile private var lowQuality = true

    // ---- one video at a time (the page scripts read these through the bridge, hence @Volatile)
    @Volatile private var exclusiveVideo = true

    @Volatile
    private var mediaOwner = ""

    // ---- browser state
    private var lastPageUrl = ""
    private var mainProcessConnection: ServiceConnection? = null
    private var pageStartedAt = 0L
    private var defaultUa = ""
    private var desktopUa = ""
    private var pendingClearHistory = false
    private var deferredNavigation: (() -> Unit)? = null
    private var crashReloadCount = 0
    // ---- redirect guard (anchor-based ad-redirect detection) ----
    // The URL the user explicitly navigated to is the "anchor". A main-frame load to a
    // different domain, with no user interaction since the anchor and within the window,
    // is an ad redirect. Server-side 301/302 chains bypass shouldOverrideUrlLoading, so
    // they are caught here at page_started (see docs/review/blocker-redesign.md).
    private var anchorUrl: String? = null
    private var anchorAt: Long = 0L
    private var anchorInteraction: Long = 0L
    private var lastRedirectBlockAt: Long = 0L
    private var softwareRendering = false
    private var scriptsAtDocumentStart = false
    private var uiScriptsAtDocumentStart = false
    private var guardJs = ""
    private var helpersJs = ""
    private var flixerImageGuardJs = ""
    private var flixerPosterGuardJs = ""

    // TMDB image virtualization: allowlist of image URLs currently visible on a
    // guarded poster-grid host. When such a host is the active page,
    // image.tmdb.org requests NOT in this set get a 1x1 transparent pixel
    // instead of the real image. This keeps the GPU texture count low enough to
    // avoid the PowerVR KEGLReleaseRenderTarget crash.
    @Volatile private var tmdbAllowlist: MutableSet<String> = mutableSetOf()
    @Volatile private var onFlixerPage = false
    // True when the active page is a guarded poster-grid host (flixer or cinejoy)
    // — i.e. the TMDB image firewall (allowlist + 1x1 backstop) is active.
    @Volatile private var onImageGuardedPage = false

    // Guarded poster-grid hosts: the TMDB image firewall is active on these.
    // Flixer loads a ~180-poster intro burst; cinejoy lazy-loads a large grid.
    // Both produce enough GPU texture churn to tip the PowerVR driver over.
    private fun isImageGuardedHost(host: String): Boolean {
        return host == "flixer.gd" || host.endsWith(".flixer.gd") ||
            host == "flixer.su" || host.endsWith(".flixer.su") ||
            host == "cinejoy.pk" || host.endsWith(".cinejoy.pk")
        // nippleflix removed: its in-view posters didn't match the allowlist, so
        // the native 1x1 firewall blanked the home-page grid (see flixer_image_guard.js).
    }

    // GPU render-reset: flixer crashes (KEGLReleaseRenderTarget) when loaded
    // after a video site (coreflix/cinejoy) — residual video GPU state tips the
    // PowerVR driver over the edge. Track the last non-flixer host so we can
    // force a render reset when transitioning video-site -> flixer.
    @Volatile private var lastNonFlixerHost = ""

    // GPU render-reset (general): residual video GPU state accumulates across
    // video sites and tips the PowerVR driver over the edge — frame drops after a
    // long session, and black video (compositing fails, audio is fine). Set when a
    // page starts real playback; consumed by a render reset on the next navigation
    // away, so the state is cleared before the next site's video composites.
    @Volatile private var videoActive = false

    // ---- remote pointer
    private var cx = 0f
    private var cy = 0f
    private var lastTapAt = 0L
    private data class PendingTap(val target: View, val downAt: Long, val x: Float, val y: Float, val mouse: Boolean)
    private var pendingTap: PendingTap? = null
    private var tapUp: Runnable? = null
    private var selectPending = false
    private var selectLongFired = false
    private var backPending = false
    private var backLongFired = false
    private var hoverActive = false
    private var lastHoverAt = 0L
    private val hideCursorRunnable = object : Runnable {
        override fun run() {
            // While a page is loading, keep the pointer visible so its loading bar stays on screen.
            if (cursor.loading) {
                handler.postDelayed(this, 2000)
                return
            }
            cursor.visibility = View.GONE
            liftPointerIfPlaying()
            hoverActive = false
        }
    }

    // ---- full-screen video
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // ---- keypad
    private var textMode = TextMode.NONE
    private val buffer = StringBuilder()
    private var bufferPassword = false
    private var pendingJsPrompt: android.webkit.JsPromptResult? = null
    private var promptAllowsEmpty = false
    private var promptDone: ((String) -> Unit)? = null
    private var promptCancel: (() -> Unit)? = null
    private val syncRunnable = Runnable { syncField() }

    // ---- menus
    private var menuBack: (() -> Unit)? = null
    private var bookmarksFolder: String? = null
    private var movingBookmarkUrl: String? = null

    companion object {
        private const val REQ_DIAGNOSTIC_EXPORT = 1203
        private const val REQ_STORAGE_PERMISSION = 1204
        private const val REQ_BOOKMARK_EXPORT = 1205
        private const val REQ_BOOKMARK_IMPORT = 1206
    }

    // ---- misc
    private val swallowedUp = HashSet<Int>()
    private var lastBackAt = 0L
    private var blockedCount = 0
    private var lastBlockedToastAt = 0L
    private var lastInfoToastAt = 0L
    private var lastToast: Toast? = null

    private val keypadListener = object : Keypad.Listener {
        override fun onText(s: String) {
            typeText(s)
        }

        override fun onBackspace() {
            typeBackspace()
        }

        override fun onClear() {
            typeClear()
        }

        override fun onGo() {
            typeGo()
        }

        override fun onClose() {
            cancelKeypad()
        }
    }

    // =====================================================================================
    //  Lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exclude the system IME at the window, not by disabling Chromium's editor.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

        CrashLog.install(this)   // first, so even a failure while building the screen is recorded
        DiagnosticLog.initialize(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkMode = prefs.getBoolean("darkMode", true)
        desktopSite = prefs.getBoolean("desktopSite", true)
        autoplay = prefs.getBoolean("autoplay", false)
        lowQuality = prefs.getBoolean("lowQuality", true)
        exclusiveVideo = prefs.getBoolean("exclusiveVideo", true)
        crashReloadCount = prefs.getInt("crashReloadCount", 0)
        // Read the watchdog state before the crash window is cleared: a restart the watchdog
        // caused means the previous process died in a native crash nothing here could catch.
        val watchdogRestart = prefs.getBoolean("watchdogRestart", false)
        val watchdogUrl = prefs.getString("lastCrashUrl", "") ?: ""
        val crashWindow = prefs.getLong("crashWindowStartedAt", 0L)
        if (crashWindow <= 0L || CrashRecoveryPolicy.expired(crashWindow, System.currentTimeMillis())) {
            clearCrashRecovery()
        }
        // cleanExit stays false unless the activity ends normally, so a process death that
        // leaves it false was a crash. wasForeground tells the watchdog not to reopen an app
        // the system killed while it was backgrounded.
        prefs.edit().putBoolean("cleanExit", false).putBoolean("wasForeground", true).commit()
        startWatchdog()
        softwareRendering = prefs.getBoolean("softwareRendering", false)

        // Bookmark export writes to public Downloads; on API 23+ that needs the
        // runtime WRITE_EXTERNAL_STORAGE permission. Request it up front so the
        // user grants it once and export just works.
        requestStoragePermission()

        ui = Ui(this)
        guard = PopupGuard(this)
        store = BookmarkStore.shared(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        guardJs = readAsset(this, "popup_guard.js")
        helpersJs = readAsset(this, "page_helpers.js")
        flixerImageGuardJs = readAsset(this, "flixer_image_guard.js")
        flixerPosterGuardJs = readAsset(this, "flixer_poster_guard.js")
        fmhyThemeJs = readAsset(this, "fmhy_theme.js")
        tvUiJs = readAsset(this, "tv_ui.js")
        seekClickJs = readAsset(this, "seek_click.js")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        webView = TvWebView(this)
        webView.setBackgroundColor(Color.BLACK)
        if (softwareRendering) {
            // Software rendering avoids the PowerVR GPU driver crash (SIGSEGV in
            // glBindFramebuffer) that hits certain pages (flixer) on this Fire TV.
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))

        progress = BarView(this, Color.argb(60, 255, 255, 255), Colors.ACCENT)
        progress.visibility = View.GONE
        root.addView(progress, FrameLayout.LayoutParams(MATCH, ui.u(4)))

        val cursorSize = ui.u(26)
        cursor = CursorView(this, cursorSize)
        cursor.visibility = View.GONE
        root.addView(cursor, FrameLayout.LayoutParams(cursorSize, cursorSize))
        cx = ui.screenW / 2f
        cy = ui.screenH / 2f
        cursor.moveTo(cx, cy)

        startPage = StartPage(this, ui, store)
        startPage.listener = object : StartPage.Listener {
            override fun openUrl(url: String) {
                this@MainActivity.openUrl(url)
            }

            override fun search() {
                promptUrl()
            }

            override fun manage(folder: String?) {
                showBookmarks(folder, 0)
            }
        }
        root.addView(startPage, FrameLayout.LayoutParams(MATCH, MATCH))

        menu = MenuPanel(this, ui)
        root.addView(menu, FrameLayout.LayoutParams(MATCH, MATCH))

        listPanel = ListPanel(this, ui)
        root.addView(listPanel, FrameLayout.LayoutParams(MATCH, MATCH))

        textPanel = TextPanel(this, ui)
        root.addView(textPanel, FrameLayout.LayoutParams(MATCH, MATCH))

        keypad = Keypad(this, ui)
        keypad.listener = keypadListener
        val keypadW = Math.min(ui.screenW - ui.u(40), ui.u(820))
        val keypadLp = FrameLayout.LayoutParams(keypadW, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        keypadLp.bottomMargin = ui.u(14)
        root.addView(keypad, keypadLp)
        keypad.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateEditorViewport() }

        fullscreenBox = FrameLayout(this)
        fullscreenBox.setBackgroundColor(Color.BLACK)
        fullscreenBox.visibility = View.GONE
        root.addView(fullscreenBox, FrameLayout.LayoutParams(MATCH, MATCH))

        osd = Osd(this, ui)
        val osdW = Math.min(ui.screenW - ui.u(80), ui.u(620))
        val osdLp = FrameLayout.LayoutParams(osdW, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        osdLp.bottomMargin = ui.u(30)
        root.addView(osd, osdLp)

        dimOverlay = View(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            visibility = View.GONE
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(dimOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            val w = r - l; val h = b - t
            if (w > 0 && h > 0 && (w != oldR - oldL || h != oldB - oldT)) {
                cx = cx.coerceIn(0f, (w - 1).toFloat()); cy = cy.coerceIn(0f, (h - 1).toFloat())
                cursor.moveTo(cx, cy)
                fun size(view: View, width: Int, height: Int = WRAP, bottom: Int? = null) {
                    val lp = view.layoutParams as FrameLayout.LayoutParams
                    lp.width = width.coerceAtLeast(1); lp.height = height
                    if (bottom != null) lp.bottomMargin = bottom
                    view.layoutParams = lp
                }
                size(keypad, minOf(w-ui.u(40), ui.u(820)), bottom=ui.u(14))
                updateEditorViewport()
                size(osd, minOf(w-ui.u(80), ui.u(620)), bottom=ui.u(30))
                size(menu.getChildAt(0), minOf(w,ui.u(380)), MATCH)
                size(listPanel.getChildAt(0), minOf(w-ui.u(60),ui.u(700)), minOf(h-ui.u(50),ui.u(470)).coerceAtLeast(1))
                size(textPanel.getChildAt(0), minOf(w-ui.u(60),ui.u(780)), minOf(h-ui.u(40),ui.u(490)).coerceAtLeast(1))
            }
        }
        configureWebView()
        try { webView.resumeTimers() } catch (_: Throwable) {}
        // Reopen the page that crashed (at most twice), instead of dropping to the start page.
        val reopen = prefs.getString("lastCrashUrl", "") ?: ""
        val restore = prefs.getString("restoreAfterRecreateUrl", "") ?: ""
        prefs.edit().remove("restoreAfterRecreateUrl").apply()
        if (watchdogRestart) {
            prefs.edit().remove("watchdogRestart").commit()
            if (watchdogUrl.startsWith("http")) {
                toast("Restarted after the crash; reopened the page")
                navigateUrl(watchdogUrl, userInitiated = false)
            } else {
                showStartPage(true)
            }
        } else if (restore.startsWith("http")) {
            navigateUrl(restore, userInitiated = false)
        } else if (crashReloadCount in 1..2 && reopen.startsWith("http")) {
            navigateUrl(reopen, userInitiated = false)
        } else {
            showStartPage(true)
            if (crashReloadCount > 2 && reopen.startsWith("http")) showCrashRecovery(reopen)
        }
        handleBookmarkIntent(intent)
    }

    override fun onPause() {
        DiagnosticLog.record(this, "activity_pause")
        super.onPause()
        BookmarkJobs.detach(this)
        interactionSerial++
        foreground = false
        selectPending = false
        backPending = false
        backLongFired = false
        wakeKey = null
        swallowedUp.clear()
        cancelPlayerWork()
        editorSession++
        cancelPageEdits()
        finishPendingTap(cancel = true)
        handler.removeCallbacks(idleCheck)
        idleQueryPending = false
        if (pageAvailable()) {
            eval("window.__fb && __fb.pause && __fb.pause()")
            webView.onPause()
        }
        prefs.edit().putBoolean("wasForeground", false).commit()
    }

    /** The watchdog lives in a second process so it survives a crash that kills this one. */
    private fun startWatchdog() {
        try {
            startService(Intent(this, WatchdogService::class.java))
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {}
                override fun onServiceDisconnected(name: ComponentName) {}
            }
            mainProcessConnection = connection
            bindService(Intent(this, MainProcessService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        DiagnosticLog.record(this, "activity_resume")
        prefs.edit().putBoolean("wasForeground", true).commit()
        super.onResume()
        if (pageAvailable()) { webView.resumeTimers(); webView.onResume() }
        foreground = true
        val resumeNavigation = deferredNavigation
        deferredNavigation = null
        resumeNavigation?.invoke()
        requestRendererRecovery()
        BookmarkJobs.attach(this)
        // Returning from a picker resets idle time, but is not a new user command.
        resetIdleActivity()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 10_000L)
    }

    override fun onLowMemory() {
        DiagnosticLog.record(this, "low_memory", JSONObject().put("page", DiagnosticLog.safeUrl(lastPageUrl)))
        super.onLowMemory()
    }

    override fun onDestroy() {
        DiagnosticLog.record(this, "activity_destroy")
        // Only a normal finish counts as a clean exit; a process death that leaves the flag
        // false is the crash the watchdog is watching for.
        if (isFinishing) prefs.edit().putBoolean("cleanExit", true).commit()
        val connection = mainProcessConnection
        if (connection != null) {
            try { unbindService(connection) } catch (_: Throwable) {}
            mainProcessConnection = null
        }
        cancelPendingJsPrompt()
        finishPendingTap(cancel = false, deliver = false)
        if (pageAvailable()) try { webView.resumeTimers() } catch (_: Throwable) {}
        foreground = false
        activityDestroyed = true
        pendingEvaluations.clear()
        handler.removeCallbacksAndMessages(null)
        popupViews.toList().forEach { disposePopup(it) }
        if (::fullscreenBox.isInitialized) leaveFullscreen(false)
        destroyBrowser(false)
        if (::root.isInitialized) root.removeAllViews()
        BookmarkJobs.detach(this)
        super.onDestroy()
    }

    // =====================================================================================
    //  WebView setup
    // =====================================================================================

    // General browser: scripting is required; file access is disabled and permission requests are denied.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // No video or sound may start by itself: a hidden preview or an ad must not take the only
        // video decoder away from the player you chose. (Settings > Autoplay without a click.)
        s.mediaPlaybackRequiresUserGesture = !autoplay
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        // Popup Guard: with the guard ON, scripts may not open windows on their own at all.
        s.javaScriptCanOpenWindowsAutomatically = !guard.enabled
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = false

        defaultUa = s.userAgentString
        desktopUa = buildDesktopUa(defaultUa)
        applyUserAgent()
        applyDarkMode()
        DiagnosticLog.record(this, "webview_config", JSONObject().apply {
            put("desktopSite", desktopSite)
            put("autoplay", autoplay)
            put("exclusiveVideo", exclusiveVideo)
            put("popupGuard", guard.enabled)
            put("multiWindow", s.supportMultipleWindows())
            put("jsPopupAuto", s.javaScriptCanOpenWindowsAutomatically)
            put("mixedContentMode", s.mixedContentMode)
        })

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.addJavascriptInterface(Bridge(), "Buccaneer")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { _, _, _, _, _ -> toast("Downloads are not supported in this browser") }

        installScripts()
    }

    /** popup_guard.js and page_helpers.js should run before any page script, in every frame. */
    private fun installScripts() {
        scriptsAtDocumentStart = false
        uiScriptsAtDocumentStart = false
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val origins = setOf("*")
                // Do not rewrite navigator.webdriver. Keep the WebView environment unmodified
                // for verification compatibility and predictable site behavior.
                WebViewCompat.addDocumentStartJavaScript(webView, guardJs, origins)
                WebViewCompat.addDocumentStartJavaScript(webView, helpersJs, origins)
                // Flixer TMDB image firewall. Registered at DOCUMENT START (not just on
                // page commit) — this is the fix for the flixer PowerVR crash. The guard
                // must observe the poster grid BEFORE flixer's own scripts append the
                // ~180 TMDB posters, so its allowlist sync runs while the burst is
                // happening. Injecting it only from onPageCommitVisible (via
                // evaluateJavascript) ran too late: sync() saw count:0 (images not yet
                // laid out), the allowlist stayed empty, and the full 182-image burst
                // still hit the GPU → SIGSEGV in KEGLReleaseRenderTarget. At document
                // start it runs in every frame before any page script, so it catches the
                // burst as it happens: visible posters get real textures, the off-screen
                // ones are served 1x1 pixels by shouldInterceptRequest. The script
                // self-guards on the flixer host and is idempotent, so registering it
                // for all origins is safe.
                WebViewCompat.addDocumentStartJavaScript(webView, flixerImageGuardJs, origins)
                scriptsAtDocumentStart = true
                // An optional UI/theme failure must never prevent Flixer's intro fix.
                try {
                    WebViewCompat.addDocumentStartJavaScript(webView, fmhyThemeJs, setOf("https://fmhy.net", "https://*.fmhy.net"))
                    WebViewCompat.addDocumentStartJavaScript(webView, tvUiJs, origins)
                    uiScriptsAtDocumentStart = true
                } catch (e: Exception) {
                    DiagnosticLog.record(this, "ui_scripts_install_failed", JSONObject().put("error", e.javaClass.simpleName))
                }
                DiagnosticLog.record(this, "scripts_installed", JSONObject().put("mode", "document_start").put("frames", "all"))
            }
        } catch (e: Exception) {
            scriptsAtDocumentStart = false
            DiagnosticLog.record(this, "scripts_install_failed", JSONObject().put("error", e.javaClass.simpleName.take(120)))
        }
    }

    /** Old WebViews cannot run scripts at document start; inject on page events instead (scripts are idempotent). */
    private fun injectFallback(view: WebView?) {
        if (view == null) return
        if (!scriptsAtDocumentStart) {
            try { view.evaluateJavascript(guardJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(helpersJs, null) } catch (_: Throwable) { }
        }
        if (!uiScriptsAtDocumentStart) {
            try { view.evaluateJavascript(fmhyThemeJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(tvUiJs, null) } catch (_: Throwable) { }
        }
    }

    /**
     * Dark mode: handled entirely by Chromium's render-client darkening (FORCE_DARK_ON,
     * set in applyDarkMode). No CSS injection — injected style tags can trigger GPU
     * compositing issues on the PowerVR driver (flixer crashes). fmhy (VitePress)
     * doesn't get darkened because it declares color-scheme in its CSS, which makes
     * Chromium skip render-client darkening. Known limitation. Called from both
     * onPageCommitVisible and onPageFinished. When dark mode is off, cleans up any
     * style tags left by older app versions.
     */
    private fun injectDarkMode(view: WebView?) {
        if (view == null) return
        // NO CSS injection on any page. Every attempt to force fmhy dark
        // (style tag, color-scheme override) has broken flixer — PowerVR GPU
        // crash (SIGSEGV in KEGLReleaseRenderTarget). fmhy stays light.
        // Dark mode is handled entirely by Chromium render-client darkening
        // (FORCE_DARK_ON + strategy 2 in applyDarkMode).
        // Do NOT add any evaluateJavascript style injection here.
    }

    private fun buildDesktopUa(ua: String): String {
        val match = Regex("Chrome/([0-9.]+)").find(ua)
        val version = match?.groupValues?.get(1) ?: "120.0.0.0"
        // Desktop-layout UA for the existing Desktop site setting. This changes the
        // reported identity only; it does not turn Android WebView into desktop Chrome.
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    private fun applyUserAgent() {
        webView.settings.userAgentString = if (desktopSite) desktopUa else defaultUa
    }

    /** Port of rules.json: decides whether a page-initiated navigation is allowed. */
    private fun interceptNavigation(view: WebView?, uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            if (mainFrame && guard.blocksNavigation(view?.url, uri)) {
                DiagnosticLog.record(this, "redirect_blocked", JSONObject().apply {
                    put("mechanism", "native")
                    put("initiator", DiagnosticLog.safeUrl(view?.url))
                    put("target", DiagnosticLog.safeUrl(uri.toString()))
                })
                noteBlocked("a redirect")
                return true
            }
            return false
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob" ||
            scheme == "javascript" || scheme == "file"
        ) {
            return false
        }
        // intent:, market:, tel:, custom app schemes... nothing on a TV browser can open them
        if (guard.enabled) noteBlocked("an app link")
        return true
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            return interceptNavigation(view, req.url, req.isForMainFrame)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (url == null) return false
            return interceptNavigation(view, Uri.parse(url), true)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!pageAvailable() || (view != null && view !== webView)) return
            if (navigationPending()) {
                // This load predates the pending native command. Its notification
                // cannot cancel the command or replace the recovery destination.
                try { view?.stopLoading() } catch (_: Exception) { }
                DiagnosticLog.record(this@MainActivity, "prior_load_start_ignored")
                return
            }
            finishPendingTap(cancel = true)
            pageEpoch++
            resetDocumentWork()
            leaveFullscreen(true)
            osd.hideNow()
            val cancelledWebPrompt = cancelPendingJsPrompt()
            if (textMode == TextMode.PAGE || cancelledWebPrompt) closeKeypad()
            lastTapAt = 0L
            selectPending = false
            backPending = false
            backLongFired = false
            inputFrame = ""
            inputFieldState = ""
            inputFieldId = ""
            playbackHeartbeatAt = 0L
            idleQueryPending = false
            scrollMode = false
            scrollXPending = 0
            scrollYPending = 0
            scrollPending = false
            cancelOsdRefresh()
            handler.removeCallbacks(syncRunnable)
            lastPageUrl = url ?: ""
            // A native GPU crash kills the process before any handler runs, so the page has to
            // be recorded here for the watchdog restart to reopen it.
            if (url != null && url.startsWith("http")) prefs.edit().putString("lastCrashUrl", url).commit()
            pageStartedAt = SystemClock.elapsedRealtime()
            DiagnosticLog.record(this@MainActivity, "page_started", JSONObject().apply {
                put("url", DiagnosticLog.safeUrl(url))
                put("host", try { Uri.parse(url ?: "").host ?: "" } catch (_: Throwable) { "" })
            })
            maybeBlockAdRedirect(url)
            injectFallback(view)
            cursor.loading = true
            if (!startPage.shown()) {
                progress.fraction = 0.05f
                progress.visibility = View.VISIBLE
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (!pageAvailable() || navigationPending() || (view != null && view !== webView)) return
            DiagnosticLog.record(this@MainActivity, "page_commit_visible", JSONObject().put("url", DiagnosticLog.safeUrl(url)))
            injectFallback(view)
            injectDarkMode(view)
            try { view?.evaluateJavascript(fmhyThemeJs, null) } catch (_: Throwable) {}
            // Detect guarded poster-grid page: activate TMDB image firewall.
            val host = try { Uri.parse(url ?: "").host?.lowercase(Locale.US)?.removeSuffix(".") ?: "" } catch (_: Throwable) { "" }
            val isFlixer = host == "flixer.gd" || host.endsWith(".flixer.gd") || host == "flixer.su" || host.endsWith(".flixer.su")
            val isImageGuarded = isImageGuardedHost(host)
            if (isImageGuarded != onImageGuardedPage) {
                onImageGuardedPage = isImageGuarded
                if (!isImageGuarded) tmdbAllowlist.clear()
                DiagnosticLog.record(this@MainActivity, "image_firewall", JSONObject().apply {
                    put("active", isImageGuarded)
                    put("host", host)
                    put("allowlistSize", tmdbAllowlist.size)
                })
            }
            if (isFlixer != onFlixerPage) {
                onFlixerPage = isFlixer
                DiagnosticLog.record(this@MainActivity, "flixer_image_firewall", JSONObject().apply {
                    put("active", isFlixer)
                    put("allowlistSize", tmdbAllowlist.size)
                })
                // GPU render reset: flixer crashes (KEGLReleaseRenderTarget) when
                // loaded after a video site — residual video GPU state tips the
                // PowerVR driver over the edge. Force a render reset on the
                // video-site -> flixer transition.
                if (isFlixer && lastNonFlixerHost.isNotEmpty()) {
                    DiagnosticLog.record(this@MainActivity, "flixer_render_reset", JSONObject().apply {
                        put("from", lastNonFlixerHost)
                    })
                    try {
                        view?.pauseTimers()
                        view?.invalidate()
                        handler.postDelayed({
                            try {
                                view?.resumeTimers()
                                view?.invalidate()
                            } catch (_: Throwable) { }
                        }, 150)
                    } catch (_: Throwable) { }
                }
            }
            // Track the last non-flixer host for the render-reset trigger.
            if (!isFlixer && host.isNotEmpty()) {
                lastNonFlixerHost = host
            }
            // Inject the image guard JS (idempotent — safe to call repeatedly).
            if (isImageGuarded) {
                try { view?.evaluateJavascript(flixerImageGuardJs, null) } catch (_: Throwable) { }
            }
            // Inject the poster hover-preview guard (flixer only). Keeps the
            // DPAD-focus-triggered floating preview hidden so the grid is
            // navigable; posters still open on click (DPAD OK -> movie page).
            if (isFlixer) {
                try { view?.evaluateJavascript(flixerPosterGuardJs, null) } catch (_: Throwable) { }
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
            if (request != null) {
                val url = request.url
                // Which host actually failed (e.g. SSL net_error -202 shows up here as a failed
                // request). Recording every failed subresource tells us whether it is the video
                // CDN or only ad/tracker domains. The log is bounded, so noise is acceptable.
                val entry = JSONObject().apply {
                    put("url", DiagnosticLog.safeUrl(url.toString()))
                    put("host", url.host ?: "")
                    put("code", error?.errorCode ?: -1)
                    put("description", error?.description?.toString() ?: "unknown")
                    put("mainFrame", request.isForMainFrame)
                }
                DiagnosticLog.record(this@MainActivity, if (request.isForMainFrame) "page_error" else "subresource_error", entry)
            }
            super.onReceivedError(view, request, error)
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                DiagnosticLog.record(this@MainActivity, "http_error", JSONObject().apply {
                    put("url", DiagnosticLog.safeUrl(request.url.toString()))
                    put("status", errorResponse?.statusCode ?: -1)
                    put("reason", errorResponse?.reasonPhrase ?: "")
                })
            }
            super.onReceivedHttpError(view, request, errorResponse)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
            val r = request
            if (r != null && DiagnosticLog.isEnabled(this@MainActivity)) {
                val u = r.url.toString()
                val p = (r.url.path ?: "").lowercase(Locale.US)
                if (p.endsWith(".m3u8") || p.endsWith(".mpd") || p.contains("/manifest")) {
                    DiagnosticLog.record(this@MainActivity, "media_manifest_request", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(u))
                        put("main", r.isForMainFrame)
                        put("method", r.method)
                    })
                }
            }
            // Flixer trailer video: the show/movie detail page embeds a looping
            // YouTube trailer in an iframe. The continuous per-frame decode+composite
            // is a major GPU load that tips the PowerVR driver over (the
            // KEGLReleaseRenderTarget crash). Intercept the YouTube embed request and
            // return a blank page so the video never loads — no DOM reflow (unlike
            // removing the iframe, which destabilizes the compositor), just no video.
            // Gated on a guarded host so direct YouTube navigation is unaffected.
            if (r != null && onImageGuardedPage) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = (r.url.path ?: "").lowercase(Locale.US)
                if ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/embed/")) {
                    DiagnosticLog.record(this@MainActivity, "trailer_video_blocked", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(r.url.toString()))
                    })
                    return android.webkit.WebResourceResponse(
                        "text/html", "utf-8",
                        java.io.ByteArrayInputStream("<html><body style=\"margin:0;background:#000\"></body></html>".toByteArray())
                    )
                }
            }
            // Flixer hero downscale: the show/movie detail page loads a 4K TMDB
            // backdrop (image.tmdb.org/t/p/original/..., 3840x2160, ~33MB GPU
            // texture) displayed at ~945x540. That oversized texture is the single
            // biggest GPU-memory waste on the page and a major driver of the
            // PowerVR render-target churn that crashes under D-pad navigation
            // (KEGLReleaseRenderTarget). Intercept the /t/p/original/ request and
            // serve the w780 variant (~1.4MB) directly — no DOM or URL change, no
            // allowlist interaction (we return the image ourselves, so the TMDB
            // firewall below is bypassed for this request). On any fetch failure we
            // fall through and let the original 4K request proceed (no regression).
            if (r != null && onImageGuardedPage) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = r.url.path ?: ""
                if ((host == "image.tmdb.org" || host.endsWith(".tmdb.org")) && path.contains("/t/p/original/")) {
                    // Only downscale images the firewall already allows (in the
                    // allowlist). The hero is allowlisted when requested (that's why
                    // it displays today), so this fires for it. Off-screen images the
                    // firewall blocks are left alone (still get the 1x1 pixel).
                    if (tmdbAllowlist.contains(r.url.toString())) {
                        val smallUrl = r.url.toString().replace("/t/p/original/", "/t/p/w780/")
                        val fetched = fetchImageBytes(smallUrl, 6000L)
                        if (fetched != null) {
                            DiagnosticLog.record(this@MainActivity, "hero_downscaled", JSONObject().apply {
                                put("url", DiagnosticLog.safeUrl(r.url.toString()))
                                put("small", DiagnosticLog.safeUrl(smallUrl))
                                put("bytes", fetched.first.size)
                            })
                            return android.webkit.WebResourceResponse(
                                fetched.second, "utf-8", java.io.ByteArrayInputStream(fetched.first)
                            )
                        }
                        // fetch failed: fall through to the firewall (allows the 4K original)
                    }
                }
            }
            // Flixer episode-poster downscale: the detail page's episode list
            // loads w500 TMDB posters (500x750, ~1.5MB GPU texture each)
            // displayed at ~180x230. 18+ posters = ~27MB GPU memory, which
            // evicts the hero's texture (raster loss / black hero). Scrolling
            // the episode list loads them all and accelerates the black.
            // Intercept /t/p/w500/ and serve w185 (~0.18MB) instead — 87%
            // GPU-memory reduction with no visible quality loss at 180px.
            if (r != null && onImageGuardedPage) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = r.url.path ?: ""
                if ((host == "image.tmdb.org" || host.endsWith(".tmdb.org")) && path.contains("/t/p/w500/")) {
                    if (tmdbAllowlist.contains(r.url.toString())) {
                        val smallUrl = r.url.toString().replace("/t/p/w500/", "/t/p/w185/")
                        val fetched = fetchImageBytes(smallUrl, 6000L)
                        if (fetched != null) {
                            DiagnosticLog.record(this@MainActivity, "poster_downscaled", JSONObject().apply {
                                put("url", DiagnosticLog.safeUrl(r.url.toString()))
                                put("small", DiagnosticLog.safeUrl(smallUrl))
                                put("bytes", fetched.first.size)
                            })
                            return android.webkit.WebResourceResponse(
                                fetched.second, "utf-8", java.io.ByteArrayInputStream(fetched.first)
                            )
                        }
                        // fetch failed: fall through to the firewall
                    }
                }
            }
            // TMDB image firewall: on a guarded poster-grid host, only allow
            // images in the allowlist. Everything else gets a 1x1 transparent
            // pixel to keep GPU texture count low.
            if (r != null && onImageGuardedPage) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                if (host == "image.tmdb.org" || host.endsWith(".tmdb.org")) {
                    val url = r.url.toString()
                    if (tmdbAllowlist.contains(url)) {
                        return null // allow real image
                    }
                    // Return the cached 1x1 transparent PNG; only the stream is per-request.
                    val resp = android.webkit.WebResourceResponse(
                        "image/png", "inline", java.io.ByteArrayInputStream(TRANSPARENT_PIXEL_PNG_1X1)
                    )
                    DiagnosticLog.record(this@MainActivity, "tmdb_image_blocked", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(url))
                    })
                    return resp
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!pageAvailable() || navigationPending() || (view != null && view !== webView)) return
            val loadMs = if (pageStartedAt > 0L) SystemClock.elapsedRealtime() - pageStartedAt else -1L
            DiagnosticLog.record(this@MainActivity, "page_finished", JSONObject().apply {
                put("url", DiagnosticLog.safeUrl(url))
                put("loadMs", loadMs)
            })
            injectFallback(view)
            injectDarkMode(view)
            try { view?.evaluateJavascript(fmhyThemeJs, null) } catch (_: Throwable) {}
            cursor.loading = false
            progress.visibility = View.GONE
            // Loading can finish before a delayed GPU crash. Keep the URL/time-window
            // retry budget; a page_finished callback is not evidence of stability.
            if (pendingClearHistory) {
                pendingClearHistory = false
                view?.clearHistory()
            }
        }

        @TargetApi(Build.VERSION_CODES.O)
        @SuppressLint("ApplySharedPref") // Crash-loop counter must be durable before recreating the renderer.
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Retired callbacks cannot dispose the replacement or misreport a new crash.
            if (view !== webView || recoveringRenderer || activityDestroyed) return true
            val crashed = detail != null && detail.didCrash()
            val what = if (crashed) "The web page crashed" else "The web page renderer stopped"
            CrashLog.record(this@MainActivity, what, "Page: ${DiagnosticLog.safeUrl(lastPageUrl)}")
            DiagnosticLog.recordCritical(this@MainActivity, "render_process_gone", JSONObject().apply {
                put("didCrash", crashed)
                put("detailAvailable", detail != null)
                put("page", DiagnosticLog.safeUrl(lastPageUrl))
            })
            recoveringRenderer = true
            pendingJsPrompt = null // The dead renderer owns this result; never call back into it.
            handler.removeCallbacksAndMessages(null)
            popupViews.toList().forEach { disposePopup(it) }
            leaveFullscreen(false)
            destroyBrowser(true)
            val url = lastPageUrl
            if (url.startsWith("http")) {
                val now = System.currentTimeMillis()
                val previousUrl = prefs.getString("lastCrashUrl", "")
                val started = prefs.getLong("crashWindowStartedAt", 0L)
                val sameWindow = !CrashRecoveryPolicy.expired(started, now)
                crashReloadCount = CrashRecoveryPolicy.nextCount(url, previousUrl, started,
                    prefs.getInt("crashReloadCount", 0), now)
                prefs.edit().putInt("crashReloadCount", crashReloadCount)
                    .putString("lastCrashUrl", url)
                    .putLong("crashWindowStartedAt", if (sameWindow) started else now).commit()
                toast(if (crashReloadCount <= 2) "$what - reopening the page" else "$what - automatic retries stopped")
            } else {
                clearCrashRecovery()
                toast("$what - returning to the start page")
            }
            requestRendererRecovery()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (!pageAvailable() || startPage.shown() || navigationPending() || (view != null && view !== webView)) return
            progress.fraction = newProgress / 100f
            if (newProgress >= 100) cursor.loading = false
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
            if (result == null) return false
            if (!pageAvailable() || !foreground) { result.cancel(); return true }
            promptText((message ?: "Enter text").take(160), defaultValue ?: "", {
                if (pendingJsPrompt === result) cancelPendingJsPrompt()
            }, "OK") {
                if (pendingJsPrompt === result) {
                    pendingJsPrompt = null
                    result.confirm(it)
                }
            }
            pendingJsPrompt = result
            promptAllowsEmpty = true
            hideIme()
            return true
        }

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (!pageAvailable() || !foreground) { callback?.onCustomViewHidden(); return }
            DiagnosticLog.record(this@MainActivity, "fullscreen_show")
            try {
                enterFullscreen(view, callback)
            } catch (e: Exception) {
                report("Error entering full screen", e)
                callback?.onCustomViewHidden()
            }
        }

        override fun onHideCustomView() {
            DiagnosticLog.record(this@MainActivity, "fullscreen_hide")
            try {
                leaveFullscreen(false)
            } catch (e: Exception) {
                report("Error leaving full screen", e)
            }
        }

        /** Port of background.js tabs.onCreated / windows.onCreated: popups and script-opened tabs are closed. */
        // Bounded temporary popup view, no native bridge, permissions denied; scripts required to resolve its URL.
        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            DiagnosticLog.record(this@MainActivity, "popup_requested", JSONObject().apply {
                put("gesture", isUserGesture)
                put("dialog", isDialog)
                put("guard", guard.enabled)
            })
            if (guard.enabled) {
                noteBlocked("a popup window")
                return false
            }
            // Guard is OFF: a browser with a single view opens the new "tab" in the same view.
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            if (!pageAvailable() || !foreground || popupViews.isNotEmpty()) return false
            val temp = WebView(this@MainActivity)
            popupViews.add(temp)
            val popupEpoch = pageEpoch
            val timeout = Runnable { disposePopup(temp) }
            popupTimeouts[temp] = timeout
            handler.postDelayed(timeout, 10_000L)
            temp.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) { disposePopup(temp) }
                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            }
            DiagnosticLog.record(this@MainActivity, "popup_webview_created")
            temp.settings.javaScriptEnabled = true
            temp.settings.allowFileAccess = false
            temp.settings.allowContentAccess = false
            temp.webViewClient = object : WebViewClient() {
                @TargetApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    disposePopup(temp)
                    return true
                }

                override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) disposePopup(temp)
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, code: Int, description: String?, failingUrl: String?) {
                    disposePopup(temp)
                }

                private var forwarded = false

                private fun forward(url: String?) {
                    if (forwarded || url == null || url.startsWith("about:")) return
                    val scheme = try { Uri.parse(url).scheme?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                    if (scheme != "http" && scheme != "https") { disposePopup(temp); return }
                    forwarded = true
                    handler.post {
                        if (popupViews.contains(temp) && pageAvailable() && foreground && popupEpoch == pageEpoch) {
                            DiagnosticLog.record(this@MainActivity, "popup_forwarded", JSONObject().put("url", DiagnosticLog.safeUrl(url)))
                            disposePopup(temp)
                            navigateUrl(url, userInitiated = false)
                        } else disposePopup(temp)
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    forward(request.url.toString())
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                    forward(url)
                    return true
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    forward(url)
                }
            }
            transport.webView = temp
            msg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val msg = consoleMessage
            if (msg != null && (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                msg.messageLevel() == ConsoleMessage.MessageLevel.WARNING)) {
                DiagnosticLog.record(this@MainActivity, "console", JSONObject().apply {
                    put("level", msg.messageLevel().toString())
                    put("message", msg.message().take(1200))
                    put("source", DiagnosticLog.safeUrl(msg.sourceId()))
                    put("line", msg.lineNumber())
                })
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        /** Only DRM playback (protected media) is allowed; camera, microphone etc. are always refused. */
        override fun onPermissionRequest(request: PermissionRequest?) {
            val req = request ?: return
            val onlyDrm = req.resources.isNotEmpty() &&
                req.resources.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (onlyDrm) req.grant(req.resources) else req.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }
    }

    /** Called by the page scripts (popup_guard.js, page_helpers.js) through window.Buccaneer. */
    inner class Bridge {
        @JavascriptInterface
        fun isDiagnosticsEnabled(): Boolean = DiagnosticLog.isEnabled(this@MainActivity)

        @JavascriptInterface
        fun diagnosticEvent(event: String?, details: String?) {
            val e = event?.trim().orEmpty()
            if (e.isEmpty()) return
            DiagnosticLog.record(this@MainActivity, e, details)
        }

        @JavascriptInterface
        fun isGuardEnabled(): Boolean = guard.enabled

        @JavascriptInterface
        fun isDarkMode(): Boolean = darkMode

        @JavascriptInterface
        fun pageIsForeground(): Boolean = foreground

        /** Page-level (CSS) fullscreen: the page reports enter/exit so remote keys route to it. */
        @JavascriptInterface
        fun cssFullscreen(active: Boolean) {
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch != pageEpoch || !pageAvailable()) return@runOnUiThread
                if (cssFullscreenActive == active) return@runOnUiThread
                cssFullscreenActive = active
                DiagnosticLog.record(this@MainActivity, if (active) "css_fullscreen_on" else "css_fullscreen_off")
                if (active) {
                    hideCursor()
                    // The playback heartbeat may not be fresh yet (it lands on a 2 s cadence);
                    // retry the pointer lift once it can be, so the parked cursor over the
                    // controls cannot pin pointer-aware players' controls visible.
                    handler.postDelayed({
                        if (epoch == pageEpoch && cssFullscreenActive) liftPointerIfPlaying()
                    }, 3000)
                    refreshOsd(500, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")
                } else {
                    osd.hideNow()
                    if (pageAvailable()) webView.requestFocus()
                }
            }
        }

        @JavascriptInterface
        fun playbackActive(token: String?) {
            val epoch = pageEpoch
            if (token != documentToken) return
            heartbeatUi.offer(Heartbeat(epoch, token))
        }

        @JavascriptInterface
        fun activeInputFrame(): String = inputFrame

        @JavascriptInterface
        fun currentDocument(): String = documentToken

        @JavascriptInterface
        fun editableFocused(frame: String?, state: String?) {
            val id = frame?.take(120) ?: return
            val data = state?.takeIf { it.length <= 32768 && JsonInputBudget.allows(it) } ?: return
            val token = try { val o = JSONObject(data); if (o.optInt("ok", 0) != 1) return; o.optString("doc", "") } catch (_: Exception) { return }
            val epoch = pageEpoch
            if (token != documentToken) return
            runOnUiThread {
                if (epoch == pageEpoch && token == documentToken && pageAvailable() && foreground &&
                    (textMode == TextMode.PAGE || SystemClock.elapsedRealtime() - lastUserInputAt <= 20_000L)) {
                    val fieldId = try { JSONObject(data).optString("field", "") } catch (_: Exception) { "" }
                    if (textMode == TextMode.PAGE && inputFrame.isNotEmpty() &&
                        (inputFrame != id || (inputFieldId.isNotEmpty() && inputFieldId != fieldId))) {
                        // Legacy/unqualified reports may be stale. Only the current
                        // focused-document snapshot may move an open editor's identity.
                        if (try { JSONObject(data).optInt("focused", 0) != 1 } catch (_: Exception) { true }) return@runOnUiThread
                        // Read-only identity reconciliation, never forced DOM focus.
                        editorSession++
                        cancelPageEdits()
                        buffer.setLength(0)
                        bufferPassword = false
                        handler.removeCallbacks(syncRunnable)
                    }
                    if (textMode == TextMode.PAGE && inputFrame.isEmpty() && SystemClock.uptimeMillis() - lastTapAt !in 0L..2000L) return@runOnUiThread
                    if (textMode != TextMode.PAGE && (lastTapAt <= 0 || SystemClock.uptimeMillis() - lastTapAt !in 0L..2000L)) return@runOnUiThread
                    inputFrame = id
                    inputFieldId = fieldId
                    inputFieldState = data
                    inputFocusConfirmed = true
                    focusFromPage(data)
                    if (textMode == TextMode.PAGE) syncFieldSoon()
                }
            }
        }

        /** A blur invalidates the preview/typing permit until the focused field reports. */
        @JavascriptInterface
        fun editableBlurred(frame: String?, token: String?, field: String?) {
            val epoch = pageEpoch
            if (token != documentToken) return
            runOnUiThread {
                if (epoch == pageEpoch && token == documentToken && foreground && pageAvailable() &&
                    textMode == TextMode.PAGE && frame == inputFrame && field == inputFieldId) {
                    // Search handlers commonly blur/remove their input on Enter.
                    // The already-dispatched submit must not leave a dead keypad over results.
                    if (pageEditActive?.action == "submit") {
                        closeKeypad()
                        return@runOnUiThread
                    }
                    // The recorded field identity stands: the JS edit path targets
                    // it by ID without Android focus. Only a field the app never
                    // saw loses the typing permit.
                    if (inputFieldId.isEmpty()) inputFocusConfirmed = false
                    inputFieldState = ""
                    editorSession++
                    cancelPageEdits()
                    handler.removeCallbacks(syncRunnable)
                    showBufferPreview()
                }
            }
        }

        @JavascriptInterface
        fun blocked(what: String?) {
            val w = (what ?: "a popup").take(160)
            blockedUi.offer(BlockedBatch(pageEpoch, w))
        }

        @JavascriptInterface
        fun editableClicked() {
            val epoch = pageEpoch
            DiagnosticLog.record(this@MainActivity, "editable_clicked")
            runOnUiThread { if (epoch == pageEpoch && pageAvailable() && foreground) onEditableClicked() }
        }

        @JavascriptInterface
        fun editableClickedForDocument(token: String?) {
            val epoch = pageEpoch
            if (token != documentToken) return
            runOnUiThread { if (epoch == pageEpoch && token == documentToken && foreground && pageAvailable()) onEditableClicked() }
        }

        /** Settings > One video at a time. */
        @JavascriptInterface
        fun exclusiveEnabled(): Boolean = exclusiveVideo

        /** Settings > Prefer low quality streams. */
        @JavascriptInterface
        fun lowQualityEnabled(): Boolean = lowQuality

        /** A frame started a real player; every other frame stops its own media. */
        @JavascriptInterface
        fun mediaStarted(id: String?) {
            if (id == null || !id.startsWith(documentToken + ":")) return
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch == pageEpoch && foreground && pageAvailable() && id.startsWith(documentToken + ":")) {
                    mediaOwner = id.take(240)
                    videoActive = true
                    DiagnosticLog.record(this@MainActivity, "media_owner_started", JSONObject().put("frame", id.take(120)))
                }
            }
        }

        @JavascriptInterface
        fun mediaStopped(id: String?) {
            if (id == null || !id.startsWith(documentToken + ":")) return
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch == pageEpoch && pageAvailable() && id.startsWith(documentToken + ":")) {
                    if (mediaOwner == id) mediaOwner = ""
                    DiagnosticLog.record(this@MainActivity, "media_owner_stopped", JSONObject().put("frame", id.take(120)))
                }
            }
        }

        @JavascriptInterface
        fun activeOwner(): String = mediaOwner

        /** Flixer image virtualization: JS syncs the allowlist of visible TMDB image URLs. */
        @JavascriptInterface
        fun tmdbAllowlist(urls: String?) {
            val list = urls ?: "[]"
            if (list.length > 131072 || !JsonInputBudget.allows(list)) return
            try {
                val arr = org.json.JSONArray(list)
                val newSet = mutableSetOf<String>()
                if (arr.length() > 256) return
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "")
                    if (u.isNotEmpty() && u.length <= 8192) newSet.add(u)
                }
                tmdbAllowlist = newSet
                DiagnosticLog.record(this@MainActivity, "tmdb_allowlist_sync", JSONObject().apply {
                    put("count", newSet.size)
                })
            } catch (e: Exception) {
                DiagnosticLog.record(this@MainActivity, "tmdb_allowlist_error", JSONObject().put("error", e.message ?: ""))
            }
        }
    }

    private fun noteBlocked(what: String, count: Int = 1) {
        DiagnosticLog.record(this, "popup_guard_action", JSONObject().put("what", what.take(160)).put("count", count))
        blockedCount = (blockedCount.toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockedToastAt > 3000) {
            lastBlockedToastAt = now
            toast("Popup Guard blocked $what")
        }
    }

    /**
     * Anchor-based ad-redirect detection. Catches server-side 301/302 redirect chains that
     * bypass shouldOverrideUrlLoading (the native blocksNavigation never fires for them).
     * A main-frame load to a different domain, with no user interaction since the anchor
     * navigation and within the window, is treated as an ad redirect and the user is
     * returned to the anchor. See docs/review/blocker-redesign.md.
     */
    private fun maybeBlockAdRedirect(url: String?) {
        if (!prefs.getBoolean("redirectGuardEnabled", true)) return
        val anchor = anchorUrl ?: return
        if (url == null || !url.startsWith("http")) return
        val now = SystemClock.elapsedRealtime()
        if (now - anchorAt > prefs.getLong("redirectWindowMs", 30_000L)) return   // (c) window
        if (interactionSerial != anchorInteraction) return                          // (b) user interacted
        val aHost = try { PopupGuard.cleanHost(Uri.parse(anchor).host ?: "") } catch (_: Throwable) { "" }
        val tHost = try { PopupGuard.cleanHost(Uri.parse(url).host ?: "") } catch (_: Throwable) { "" }
        if (aHost.isEmpty() || tHost.isEmpty()) return
        if (PopupGuard.hostMatches(tHost, aHost) || PopupGuard.hostMatches(aHost, tHost)) return  // (a) same domain
        val safe = prefs.getStringSet("safeDomains", emptySet()) ?: emptySet()
        if (safe.any { d -> PopupGuard.hostMatches(tHost, d) }) return              // safe-domain escape
        val cooldown = now - lastRedirectBlockAt
        lastRedirectBlockAt = now
        DiagnosticLog.record(this, "redirect_blocked", JSONObject().apply {
            put("mechanism", "anchor")
            put("anchor", DiagnosticLog.safeUrl(anchor))
            put("target", DiagnosticLog.safeUrl(url))
            put("elapsedMs", now - anchorAt)
        })
        noteBlocked("a redirect")
        if (cooldown > 2000L) navigateUrl(anchor, userInitiated = false)
    }

    // =====================================================================================
    //  Navigation
    // =====================================================================================

    private fun openUrl(url: String) = navigateUrl(url, userInitiated = true)

    private fun navigateUrl(url: String, userInitiated: Boolean) {
        if (!pageAvailable()) return
        // Explicit user navigation is a fresh attempt; automatic retries/redirects are not.
        if (userInitiated && crashReloadCount > 0) clearCrashRecovery()
        prepareNavigation()
        DiagnosticLog.record(this, if (userInitiated) "user_navigation" else "automatic_navigation", JSONObject().put("url", DiagnosticLog.safeUrl(url)))
        if (userInitiated) {
            anchorUrl = url
            anchorAt = SystemClock.elapsedRealtime()
            anchorInteraction = interactionSerial
        }
        if (startPage.shown()) {
            startPage.hide()
            pendingClearHistory = true
        }
        progress.fraction = 0.05f
        progress.visibility = View.VISIBLE
        // Preserve intended URL for recovery, even if this process dies before page start.
        lastPageUrl = url
        handoffNavigation {
            webView.loadUrl(url)
            webView.requestFocus()
            showCursor()
        }
    }

    /** Invalidate editor/player/scroll callbacks before *any* native navigation. */
    private fun prepareNavigation(rotateDocument: Boolean = true) {
        finishPendingTap(cancel = true)
        leaveFullscreen(true)
        pageEpoch++
        resetDocumentWork(rotateDocument)
        osd.hideNow()
        cancelOsdRefresh()
        popupViews.toList().forEach { disposePopup(it) }
        closeAllPanels()
        lastBackAt = 0L
    }

    private fun navigateHistory(offset: Int) {
        if (!pageAvailable() || startPage.shown()) return
        if (offset == -1 && !webView.canGoBack() || offset == 1 && !webView.canGoForward() || offset !in listOf(-1, 1)) return
        val history = webView.copyBackForwardList()
        val destination = history.getItemAtIndex(history.currentIndex + offset)?.url
        // History may be pushState/hash-only: the existing page and its bridge remain alive.
        // Full-document navigation rotates the token in onPageStarted instead.
        prepareNavigation(rotateDocument = false)
        if (destination != null) lastPageUrl = destination
        handoffNavigation(releaseMedia = false) { webView.goBackOrForward(offset); webView.requestFocus(); showCursor() }
    }

    private fun reloadPage() {
        if (!pageAvailable() || startPage.shown()) return
        val destination = webView.url ?: lastPageUrl
        clearCrashRecovery()
        prepareNavigation()
        lastPageUrl = destination
        progress.fraction = 0.05f
        progress.visibility = View.VISIBLE
        handoffNavigation { webView.reload(); webView.requestFocus(); showCursor() }
    }

    private fun navigationPending() = navigationHandoff.isPending || deferredNavigation != null

    /** One shared teardown path for explicit loads, history and return-home. */
    private fun handoffNavigation(releaseMedia: Boolean = true, commit: () -> Unit) {
        if (!pageAvailable()) return
        val source = webView
        val epoch = pageEpoch
        fun current() = pageAvailable() && source === webView && epoch == pageEpoch
        fun commitWhenForeground() {
            if (!current()) return
            if (foreground) commit()
            else deferredNavigation = { if (current()) commit() }
        }
        // No old document to quiesce on a first load or an empty home WebView.
        val oldUrl = source.url
        if (oldUrl.isNullOrBlank() || oldUrl == "about:blank") {
            navigationHandoff.cancel()
            commitWhenForeground()
            return
        }
        DiagnosticLog.record(this, "navigation_cleanup_dispatched")
        navigationHandoff.start(cleanup = { done ->
            // Set the handoff marker BEFORE stopLoading: provider callbacks can re-enter.
            try { source.stopLoading() } catch (_: Exception) { }
            source.evaluateJavascript(
                "(function(){" +
                "try{" +
                "var media=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<Math.min(media.length,64);i++){" +
                "try{media[i].pause();" +
                (if (releaseMedia) "media[i].src='';media[i].load();media[i].remove();" else "") +
                "}catch(e){}" +
                "}" +
                "}catch(e){return 'unconfirmed';}" +
                "return 'stopped';" +
                "})()"
            ) { done(it) }
        }, commit = { reason ->
            if (current()) {
                // Script completion/timeout is NOT proof of native resource release.
                DiagnosticLog.record(this, reason)
                // GPU render reset (general): clear residual video GPU state when
                // navigating away from a page that had real playback. The flixer
                // reset in onPageCommitVisible only covers the video-site -> flixer
                // edge; this covers video-site -> video-site and video-site -> other,
                // where residual state otherwise accumulates and degrades the next
                // video (frame drops, black surface). Fires after media teardown,
                // before the new page loads. Same proven pauseTimers/invalidate/
                // resumeTimers dance as the flixer reset.
                if (videoActive) {
                    videoActive = false
                    DiagnosticLog.record(this, "video_render_reset")
                    try {
                        source.pauseTimers()
                        source.invalidate()
                        handler.postDelayed({
                            try {
                                source.resumeTimers()
                                source.invalidate()
                            } catch (_: Throwable) { }
                        }, 150)
                    } catch (_: Throwable) { }
                }
                commitWhenForeground()
            }
        })
    }

    private fun showStartPage(initial: Boolean) {
        lastBackAt = 0L
        pageEpoch++
        resetDocumentWork()
        leaveFullscreen(true)
        osd.hideNow()
        closeAllPanels()
        if (!initial) {
            handoffNavigation { webView.loadUrl("about:blank") }
        }
        pendingClearHistory = true
        progress.visibility = View.GONE
        cursor.loading = false
        hideCursor()
        startPage.show()
    }

    private fun normalizeInput(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("file://")
        ) {
            return s
        }
        if (s.contains(' ')) return searchUrl(s)
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
        if (lower == "localhost" || lower.startsWith("localhost:") || lower.startsWith("localhost/") ||
            ipv4.matches(s)
        ) {
            return "http://$s"
        }
        if (s.contains('.')) return "https://$s"
        return searchUrl(s)
    }

    private fun searchUrl(query: String): String = "https://www.google.com/search?q=" + Uri.encode(query)

    private fun confirmExit() {
        val now = SystemClock.uptimeMillis()
        if (lastBackAt > 0L && now - lastBackAt < 2500) {
            finish()
        } else {
            lastBackAt = now
            toast("Press Back again to exit")
        }
    }

    // =====================================================================================
    //  Key routing
    // =====================================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (recoveringRenderer || activityDestroyed || !foreground) return true
        val code = event.keyCode
        if (wakeKey == code) {
            if (event.action == KeyEvent.ACTION_DOWN) noteUserInput()
            if (event.action == KeyEvent.ACTION_UP) {
                wakeKey = null
                swallowedUp.remove(code)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val wasDimmed = dimmed
            if (event.repeatCount > 0 && selectPending && code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) resetIdleActivity()
            else noteUserInput()
            if (wasDimmed) { wakeKey = code; return true }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (selectPending && (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                selectPending = false
                if (!selectLongFired) tapAtCursor()
            }
            if (backPending && code == KeyEvent.KEYCODE_BACK) {
                backPending = false
                if (!backLongFired) {
                    if (startPage.shown()) {
                        if (!startPage.back()) confirmExit()
                    } else {
                        if (webView.canGoBack()) navigateHistory(-1) else showStartPage(false)
                    }
                }
            }
            if (swallowedUp.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val handled: Boolean = try {
            val branch = when {
                keypad.shown() -> "keypad"
                !startPage.shown() && isTransportKey(code) -> "transport"
                textPanel.shown() -> "textPanel"
                menu.shown() -> "menu"
                listPanel.shown() -> "listPanel"
                customView != null || cssFullscreenActive -> "fullscreen"
                startPage.shown() -> "startPage"
                else -> "browse"
            }
            diagKey(code, branch)
            when {
                keypad.shown() -> keypad.handleKey(event)
                !startPage.shown() && isTransportKey(code) -> onTransportKey(event)
                textPanel.shown() -> onTextPanelKey(event)
                menu.shown() -> onMenuKey(event)
                listPanel.shown() -> onListKey(event)
                customView != null || cssFullscreenActive -> onFullscreenKey(event)
                startPage.shown() -> onStartKey(event)
                else -> onBrowseKey(event)
            }
        } catch (e: Exception) {
            report("Error while handling key " + KeyEvent.keyCodeToString(code), e)
            true
        }
        if (handled) {
            swallowedUp.add(code)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun diagKey(code: Int, handler: String) {
        DiagnosticLog.record(this, "key", JSONObject()
            .put("code", KeyEvent.keyCodeToString(code)).put("handler", handler))
    }

    private fun isTransportKey(code: Int): Boolean = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND

    /** Hardware transport keys are independent of menu/list focus, but not an open text keypad. */
    private fun onTransportKey(event: KeyEvent): Boolean {
        val rep = event.repeatCount
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (rep == 0) videoCommand("togglePlay()", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (rep == 0) videoCommand("play()", KeyEvent.KEYCODE_MEDIA_PLAY, true)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (rep == 0) videoCommand("pause()", KeyEvent.KEYCODE_MEDIA_PAUSE)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekFromKey(event, 1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekFromKey(event, -1)
            else -> return false
        }
        return true
    }

    // ---- start page and panels: Android focus does the work, we only keep focus inside the panel

    private fun onStartKey(event: KeyEvent): Boolean {
        val rep = event.repeatCount
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Short press = go back / exit (done on key-up), long press = open the menu.
            if (rep == 0) {
                backPending = true
                backLongFired = false
            } else if (rep == 1 && backPending && !backLongFired) {
                backLongFired = true
                showMainMenu()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (rep == 0) showMainMenu()
            return true
        }
        return focusKey(event, startPage)
    }

    private fun onTextPanelKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> textPanel.scrollStep(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> textPanel.scrollStep(1)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // nothing to do, but keep the arrows away from the page underneath
            }
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) hideTextPanel()
            else -> return false
        }
        return true
    }

    private fun onMenuKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.repeatCount == 1 && !startPage.shown()) {
            hideMenu()
            scrollMode = true
            wakeKey = KeyEvent.KEYCODE_MENU
            showCursor()
            toast("Scroll mode: D-pad scrolls under pointer; Back exits, Select clicks")
            return true
        }
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) {
                val back = menuBack
                hideMenu()
                back?.invoke()
            }
            return true
        }
        return focusKey(event, menu)
    }

    private fun onListKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val moving = movingBookmarkUrl
        if (moving != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveBookmarkStep(moving, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBookmarkStep(moving, 1)
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) {
                    finishBookmarkMove()
                    wakeKey = code // A held Select must not open the newly focused bookmark.
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) listBack()
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) hideList()
            return true
        }
        return focusKey(event, listPanel)
    }

    /** D-pad moves Android focus, but never out of [panel]. Select is left to the focused button. */
    private fun focusKey(event: KeyEvent, panel: View): Boolean {
        val code = event.keyCode
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        val isSelect = code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (dir == 0 && !isSelect) return false

        val cur: View? = currentFocus
        if (cur == null || !isInside(cur, panel)) {
            val first = firstFocusable(panel)
            if (first != null) first.requestFocus()
            return true
        }
        if (isSelect) return false
        val next: View? = cur.focusSearch(dir)
        if (next != null && isInside(next, panel)) next.requestFocus()
        return true
    }

    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable && v.isEnabled) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val f = firstFocusable(v.getChildAt(i))
                if (f != null) return f
            }
        }
        return null
    }

    // ---- browsing with the pointer

    private fun onBrowseKey(event: KeyEvent): Boolean {
        val rep = event.repeatCount
        if (scrollMode && event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) {
            scrollMode = false
            toast("Pointer mode")
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (scrollMode) {
                    val delta = ui.u(60) * (if (rep > 12) 2 else 1)
                    scrollPage(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -delta else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) delta else 0,
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -delta else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) delta else 0)
                    showCursor()
                } else moveCursor(event.keyCode, rep)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Short press = click (done on key-up), long press = open the keypad.
                if (rep == 0) {
                    selectPending = true
                    selectLongFired = false
                } else if (rep == 1 && !selectLongFired) {
                    selectLongFired = true
                    openKeypadSmart()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (rep == 0) showMainMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Short press = go back (done on key-up), long press = open the menu.
                if (rep == 0) {
                    backPending = true
                    backLongFired = false
                } else if (rep == 1 && backPending && !backLongFired) {
                    backLongFired = true
                    showMainMenu()
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolume(1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolume(-1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (rep == 0) {
                    val interaction = interactionSerial
                    val intent = playerIntent
                    val target: View = customView ?: webView
                    fun current() = foreground && interaction == interactionSerial && intent == playerIntent && target === (customView ?: webView) && !keypad.shown()
                    // Force-unmute: the player's own button may be broken (cross-origin frame),
                    // so set muted=false directly on every video we can reach.
                    eval("window.__fb ? __fb.unmute() : 'none'") { raw ->
                        if (!current()) return@eval
                        when (jsString(raw)) {
                            "ok", "frame" -> refreshOsd(1500, "Unmuted")
                            "none" -> {
                                // The video is in a frame we cannot script: tap the player's
                                // mute button, as a finger would.
                                eval("window.__fb ? __fb.muteButton() : ''") mute@{ mb ->
                                    if (!current()) return@mute
                                    var ok = false
                                    var fx = 0.5f
                                    var fy = 0.5f
                                    val text = jsString(mb)
                                    if (!text.isNullOrEmpty()) {
                                        try {
                                            val o = JSONObject(text)
                                            ok = o.optInt("ok", 0) == 1
                                            fx = o.optDouble("x", 0.5).toFloat()
                                            fy = o.optDouble("y", 0.5).toFloat()
                                        } catch (e: Exception) { ok = false }
                                    }
                                    if (ok) sendTap(fx * webView.width, fy * webView.height)
                                    else videoCommand("toggleMute()", KeyEvent.KEYCODE_M)
                                }
                            }
                            else -> { }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private fun moveCursor(code: Int, repeat: Int) {
        val speed = ui.u(10) * (1f + Math.min(repeat, 30) / 3f)
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -speed
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = speed
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -speed
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = speed
        }
        val w = (root.width.takeIf { it > 0 } ?: ui.screenW).toFloat()
        val h = (root.height.takeIf { it > 0 } ?: ui.screenH).toFloat()
        val edge = ui.u(36).toFloat()

        var nx = cx + dx
        var ny = cy + dy
        var scrollX = 0
        var scrollY = 0
        // Pushing the pointer into a screen edge scrolls the page instead.
        if (dy < 0f && ny < edge) {
            scrollY = (dy * 3f).toInt()
            ny = edge
        }
        if (dy > 0f && ny > h - edge) {
            scrollY = (dy * 3f).toInt()
            ny = h - edge
        }
        if (dx < 0f && nx < edge) {
            scrollX = (dx * 3f).toInt()
            nx = edge
        }
        if (dx > 0f && nx > w - edge) {
            scrollX = (dx * 3f).toInt()
            nx = w - edge
        }
        cx = Math.max(0f, Math.min(nx, w - 1f))
        cy = Math.max(0f, Math.min(ny, h - 1f))
        if (scrollX != 0 || scrollY != 0) scrollPage(scrollX, scrollY)

        cursor.moveTo(cx, cy)
        showCursor()
        sendHover(cx, cy)
    }

    /**
     * Scroll the page. When the page itself scrolls we move the document; when it does not (most web
     * apps scroll inside an element) we send mouse-wheel events, which scroll whatever is under the pointer.
     */
    private fun scrollPage(dx: Int, dy: Int) {
        if (!pageAvailable() || !foreground) return
        scrollXPending = (scrollXPending + dx).coerceIn(-1500, 1500)
        scrollYPending = (scrollYPending + dy).coerceIn(-1500, 1500)
        if (scrollPending) return
        scrollPending = true
        val epoch = pageEpoch
        handler.postDelayed({
            if (!pageAvailable() || !foreground || epoch != pageEpoch) { if (epoch == pageEpoch) scrollPending = false; return@postDelayed }
            val sx = scrollXPending; val sy = scrollYPending
            scrollXPending = 0; scrollYPending = 0
            val viewW = webView.width.coerceAtLeast(1)
            val viewH = webView.height.coerceAtLeast(1)
            eval("(function(){if(!window.__tvUi)return 'none';" +
                "var scaleX=window.innerWidth/$viewW,scaleY=window.innerHeight/$viewH;" +
                "return __tvUi.scrollAt($cx*scaleX,$cy*scaleY,$sx*scaleX,$sy*scaleY);})()") { raw ->
                scrollPending = false
                if (jsString(raw) != "nested") {
                    val notch = ui.u(60).coerceAtLeast(1).toFloat()
                    sendWheel(cx, cy, sx / notch, -sy / notch)
                }
                if (scrollXPending != 0 || scrollYPending != 0) scrollPage(0, 0)
            }
        }, 25L)
    }

    /** One mouse-wheel event at (x, y). [v] > 0 scrolls up, [v] < 0 scrolls down, [h] > 0 scrolls right. */
    private fun sendWheel(x: Float, y: Float, h: Float, v: Float) {
        if (!foreground || !pageAvailable()) return
        try {
            val now = SystemClock.uptimeMillis()
            val props = MotionEvent.PointerProperties()
            props.id = 0
            props.toolType = MotionEvent.TOOL_TYPE_MOUSE
            val coords = MotionEvent.PointerCoords()
            coords.x = x
            coords.y = y
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            webView.dispatchGenericMotionEvent(ev)
            ev.recycle()
        } catch (e: Exception) {
            // wheel scrolling is best effort
        }
    }

    private fun showCursor() {
        cursor.visibility = View.VISIBLE
        handler.removeCallbacks(hideCursorRunnable)
        handler.postDelayed(hideCursorRunnable, 6000)
    }

    private fun hideCursor() {
        handler.removeCallbacks(hideCursorRunnable)
        cursor.visibility = View.GONE
        liftPointerIfPlaying()
        hoverActive = false
    }

    private fun tapAtCursor() {
        // The pointer remembers where it was, so the first press clicks there and shows the
        // cursor at the same time — you can act on a page right after it loads.
        osd.seekFractionAt(cx, cy)?.let {
            seekToFraction(it)
            showCursor()
            return
        }
        lastTapAt = SystemClock.uptimeMillis()
        val interaction = interactionSerial
        val editor = editorSession
        val target: View = customView ?: webView
        fun current() = foreground && interaction == interactionSerial && editor == editorSession && target === (customView ?: webView) && textMode == TextMode.NONE && !menu.shown() && !listPanel.shown() && !textPanel.shown() && !startPage.shown()
        val x = cx
        val y = cy
        if (webView.width > 0 && webView.height > 0) eval("window.__fb && __fb.selectAt ? __fb.selectAt(${x/webView.width},${y/webView.height}) : 'none'")
        if (onFlixerPage && customView == null && webView.width > 0 && webView.height > 0) {
            // Preserve working Flixer button taps. Mouse input is only needed over a
            // recognized timeline, or inside an opaque player frame we cannot inspect.
            eval("($seekClickJs)(${x / webView.width},${y / webView.height},'probe')") { raw ->
                if (!current()) return@eval
                val mouse = jsString(raw) in listOf("track", "frame")
                sendTap(x, y, mouse)
                if (mouse) repairSeekClick(x, y, interaction)
            }
        } else sendTap(x, y)
        showCursor()
        // On-demand field reads also cover Flixer without adding any listeners or
        // recurring instrumentation to its protected documents.
        for (delay in listOf(150L, 650L)) postForPage(delay) {
            if (!current()) return@postForPage
            eval("window.__fb ? __fb.field() : ''") { raw ->
                if (!current()) return@eval
                val state = jsString(raw)
                if (!state.isNullOrBlank()) focusFromPage(state)
            }
        }
        handler.postDelayed({ hideIme() }, 300)
    }

    /** One-shot fallback for identifiable seek tracks only; no Flixer listeners/timers/DOM styling. */
    private fun repairSeekClick(x: Float, y: Float, interaction: Long = interactionSerial) {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0 || seekClickJs.isBlank()) return
        postForPage(120) {
            if (!onFlixerPage || customView != null || interaction != interactionSerial || keypad.shown() || menu.shown() || listPanel.shown() || textPanel.shown()) return@postForPage
            eval("($seekClickJs)(${x / width},${y / height})")
        }
    }

    /** Trusted native input: touch by default, mouse only for Flixer pointer/player clicks. */
    private fun sendTap(x: Float, y: Float, mouse: Boolean = false) {
        if (!pageAvailable() || !foreground) return
        val epoch = pageEpoch
        val target: View = customView ?: webView
        DiagnosticLog.record(this, "tap_dispatched", JSONObject().put("x", x).put("y", y).put("w", webView.width).put("h", webView.height))
        // Diagnostic: capture the element stack at the tap point so we can see exactly
        // which element eats the click. The probe runs in the top frame; if the player is
        // in a cross-origin iframe the top-frame stack will show the <iframe> as the top
        // element, which is itself the answer (the ad layer is inside that frame).
        if (DiagnosticLog.isEnabled(this)) {
            try {
                val script = "window.__fb ? __fb.tapStack(${x},${y}) : 'none'"
                eval(script) { raw ->
                    try {
                        val text = jsString(raw)
                        if (!text.isNullOrEmpty() && text != "none") {
                            DiagnosticLog.record(this, "tap_stack", JSONObject(text))
                        }
                    } catch (_: Throwable) { /* best effort */ }
                }
            } catch (_: Throwable) { /* ignore */ }
        }
        finishPendingTap(cancel = true)
        val downAt = SystemClock.uptimeMillis()
        val tap = PendingTap(target, downAt, x, y, mouse)
        pendingTap = tap
        val down = PointerTap.event(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, mouse)
        try { target.dispatchTouchEvent(down) } catch (_: Exception) {
            finishPendingTap(cancel = true)
        } finally { down.recycle() }
        // Navigation can happen synchronously while DOWN is being dispatched.
        if (pendingTap !== tap) return
        val up = Runnable {
            if (pendingTap !== tap) return@Runnable
            if (!pageAvailable()) finishPendingTap(cancel = true, deliver = false)
            else finishPendingTap(cancel = epoch != pageEpoch || target !== (customView ?: webView))
        }
        tapUp = up
        handler.postDelayed(up, 70L)
    }

    /** Every live DOWN terminates with UP or CANCEL; renderer loss clears without native calls. */
    private fun finishPendingTap(cancel: Boolean, deliver: Boolean = true) {
        if (cancel) { selectPending = false; selectLongFired = false; backPending = false; backLongFired = false }
        val tap = pendingTap
        pendingTap = null
        tapUp?.let { handler.removeCallbacks(it) }
        tapUp = null
        if (tap == null || !deliver || !pageAvailable()) return
        val event = PointerTap.event(tap.downAt, SystemClock.uptimeMillis(),
            if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, tap.x, tap.y, tap.mouse)
        try { tap.target.dispatchTouchEvent(event) } catch (_: Exception) { } finally { event.recycle() }
    }

    /**
     * Lift the simulated mouse pointer out of the page. The browser keeps the pointer parked at
     * the last hover position and never tells the page it is gone, so pointer-aware players (Movy)
     * see "pointer over the controls" forever and pin their controls visible. Lifting only while
     * video plays: while browsing, the parked pointer is what keeps hover menus open while the
     * user reads them, so it must stay.
     */
    private fun liftPointerIfPlaying() {
        if (!hoverActive) return
        val now = SystemClock.elapsedRealtime()
        if (playbackHeartbeatAt <= 0L || now - playbackHeartbeatAt >= 12_000L) return
        hoverActive = false
        try {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_EXIT, cx, cy, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            webView.dispatchGenericMotionEvent(ev)
            ev.recycle()
            DiagnosticLog.record(this, "pointer_lifted", JSONObject().put("x", cx).put("y", cy))
        } catch (_: Exception) {
            // hover is a nicety; never let it break browsing
        }
    }

    /** Mouse-style hover so menus and video controls that react to the pointer appear. */
    private fun sendHover(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverAt < 40) return
        lastHoverAt = now
        try {
            val action = if (hoverActive) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER
            hoverActive = true
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            webView.dispatchGenericMotionEvent(ev)
            ev.recycle()
        } catch (e: Exception) {
            // hover is a nicety; never let it break browsing
        }
    }

    /** Dispose dead renderers immediately, but recreate only while this Activity is foreground. */
    private fun requestRendererRecovery() {
        if (!recoveringRenderer || activityDestroyed || !foreground || rendererRecreatePosted) return
        rendererRecreatePosted = true
        handler.post {
            rendererRecreatePosted = false
            if (recoveringRenderer && !activityDestroyed && foreground) recreate()
        }
    }

    /** Page editing gets an actual viewport above the native keypad, not a covered WebView. */
    private fun updateEditorViewport() {
        if (!::webView.isInitialized || webViewDestroyed || !::keypad.isInitialized) return
        val lp = webView.layoutParams ?: return
        val height = if (textMode == TextMode.PAGE && keypad.shown()) {
            val total = if (root.height > 0) root.height else ui.screenH
            val keyLp = keypad.layoutParams as FrameLayout.LayoutParams
            (total - keypad.measuredHeight - keyLp.bottomMargin - 8).coerceAtLeast(80)
        } else MATCH
        if (lp.height == height) return
        lp.height = height
        webView.layoutParams = lp
        if (textMode == TextMode.PAGE) postForPage(30L) {
            if (textMode == TextMode.PAGE) eval("window.__fb && __fb.focusIntoView()")
        }
    }

    private fun hideIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(webView.windowToken, 0)
        } catch (e: Exception) {
            // ignore
        }
    }

    // =====================================================================================
    //  Video: play/pause, seek, volume, full screen
    // =====================================================================================

    /** Bounded completion: native failures and deadlines cannot leave a queue busy. */
    private fun eval(script: String, callback: ((String?) -> Unit)? = null) {
        if (!pageAvailable()) { callback?.invoke(null); return }
        // Fire-and-forget commands have no result queue to unblock. Do not retain
        // a deadline/closure for two seconds for each such command.
        if (callback == null) {
            try { webView.evaluateJavascript(script, null) }
            catch (error: Exception) { Log.w("Buccaneer", "Page script unavailable", error) }
            return
        }
        val epoch = pageEpoch
        val id = ++evaluationId
        var completed = false
        lateinit var deadline: Runnable
        fun complete(value: String?) {
            if (completed) return
            completed = true
            pendingEvaluations.remove(id)
            handler.removeCallbacks(deadline)
            if (pageAvailable() && epoch == pageEpoch) callback.invoke(value)
        }
        deadline = Runnable { complete(null) }
        pendingEvaluations[id] = deadline
        handler.postDelayed(deadline, 2000L)
        try { webView.evaluateJavascript(script) { complete(it) } }
        catch (e: Exception) { Log.w("Buccaneer", "Page script unavailable", e); complete(null) }
    }

    private fun cancelPendingFullscreen() {
        if (!pendingFullscreen) return
        pendingFullscreen = false
        if (pageAvailable()) eval("window.__fb && __fb.cancelFullscreen && __fb.cancelFullscreen()")
    }

    private fun cancelPlayerWork() {
        cancelPendingFullscreen()
        playerIntent++
        seekGeneration++
        seekInFlight = false
        queuedSeek = 0
        seekRepeat.reset()
    }

    private fun resetDocumentWork(rotateDocument: Boolean = true) {
        deferredNavigation = null
        cancelPageEdits()
        inputFocusConfirmed = true
        navigationHandoff.cancel()
        heartbeatUi.cancel()
        blockedUi.cancel()
        interactionSerial++
        cancelPlayerWork()
        editorSession++
        if (rotateDocument) documentToken = java.util.UUID.randomUUID().toString()
        cssFullscreenActive = false
        mediaOwner = ""
        playbackHeartbeatAt = 0L
        pendingEvaluations.values.toList().forEach { handler.removeCallbacks(it) }
        pendingEvaluations.clear()
        scrollPending = false
        idleQueryPending = false
        scrollXPending = 0
        scrollYPending = 0
    }

    /**
     * Runs a page_helpers.js video command. It answers "ok", "frame" (the player lives in a
     * frame from another site; that frame's own copy of the helper ran the command — except on
     * Flixer, which keeps the trusted-tap path), or "none" (no player found; send the key).
     */
    private fun videoCommand(call: String, fallbackKey: Int, tapFrame: Boolean = false) {
        cancelPlayerWork()
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.$call : 'none'") { raw ->
            if (!foreground || intent != playerIntent || target !== (customView ?: webView)) return@eval
            when (jsString(raw)) {
                "ok" -> if (tapFrame) postForPage(350) { if (intent == playerIntent && target === (customView ?: webView)) retryIfPlayRefused() }
                "frame" -> if (tapFrame && fallbackKey == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && onFlixerPage) {
                    // Flixer's player frame is not scripted: a real tap starts and toggles it.
                    tapPlayerFrame(mouse = true)
                }
                // "frame" on any other site: the frame's own helper already ran the command.
                "none", null -> sendKeyToPage(fallbackKey)
                else -> sendKeyToPage(fallbackKey)
            }
            refreshOsd(150, "")
        }
    }

    /** The browser refuses play() until the page has had a click. Then a real tap on the player does it. */
    private fun retryIfPlayRefused() {
        if (!foreground) return
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.playBlocked() : 0") { raw ->
            if (foreground && intent == playerIntent && target === (customView ?: webView) && jsString(raw) == "1") tapPlayerFrame()
        }
    }

    /** The player is inside a frame from another site, so we tap its middle, as a finger would. */
    private fun tapPlayerFrame(mouse: Boolean = onFlixerPage) {
        if (!foreground) return
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.frameCenter() : ''") { raw ->
            if (!foreground || intent != playerIntent || target !== (customView ?: webView)) return@eval
            var ok = false
            var fx = 0.5f
            var fy = 0.5f
            val text = jsString(raw)
            if (!text.isNullOrEmpty()) {
                try {
                    val o = JSONObject(text)
                    ok = o.optInt("ok", 0) == 1
                    fx = o.optDouble("x", 0.5).toFloat()
                    fy = o.optDouble("y", 0.5).toFloat()
                } catch (e: Exception) {
                    ok = false
                }
            }
            if (ok) sendTap(fx * webView.width, fy * webView.height, mouse) else sendKeyToPage(KeyEvent.KEYCODE_SPACE)
        }
    }

    private fun seekFromKey(event: KeyEvent, direction: Int) {
        val seconds = seekRepeat.step(event.keyCode, event.repeatCount, event.downTime, event.eventTime)
        if (seconds != 0) videoSeek(seconds * direction)
    }

    private fun videoSeek(seconds: Int) {
        if (!pageAvailable() || !foreground || seconds == 0) return
        if (pendingFullscreen) { cancelPendingFullscreen(); playerIntent++ }
        if (seekInFlight) { queuedSeek = (queuedSeek + seconds).coerceIn(-120, 120); return }
        seekInFlight = true
        val generation = ++seekGeneration
        val intent = playerIntent
        val target: View = customView ?: webView
        fun valid() = foreground && generation == seekGeneration && intent == playerIntent && target === (customView ?: webView)
        fun finish() {
            if (!valid()) return
            seekInFlight = false
            val next = queuedSeek
            queuedSeek = 0
            if (next != 0) videoSeek(next)
        }
        val amount = seconds.coerceIn(-60, 60)
        queuedSeek = (queuedSeek + seconds - amount).coerceIn(-120, 120)
        eval("window.__fb ? __fb.seek($amount) : 'none'") { raw ->
            if (!valid()) return@eval
            if (jsString(raw) == "ok" || jsString(raw) == "frame") {
                // "frame": the player frame's own helper ran the seek.
                refreshOsd(150, "Hold FF/Rewind: larger skips"); finish()
            } else {
                // Focus the selected iframe without a center click that might toggle playback.
                eval("window.__fb && __fb.focusTarget ? __fb.focusTarget() : 'none'") focus@{
                    if (!valid()) return@focus
                    val count = (kotlin.math.abs(amount) / 10).coerceIn(1, 6)
                    val key = if (amount > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    for (i in 0 until count) postForPage(i * 80L) {
                        if (valid() && !keypad.shown() && !startPage.shown()) sendKeyToPage(key)
                    }
                    postForPage(count * 80L + 40L) { if (valid()) { refreshOsd(75, "Native seek steps depend on this player"); finish() } }
                }
            }
        }
    }

    private fun seekToFraction(fraction: Float) {
        if (!fraction.isFinite() || !pageAvailable() || !foreground) return
        cancelPlayerWork()
        val intent = playerIntent
        val amount = fraction.coerceIn(0f, 1f)
        // Passive, one-shot API only: no new player hooks, observers or site styling.
        eval("(function(){try{if(!window.__fb)return 'none';var s=JSON.parse(__fb.state());" +
            "if(s.f!==1||typeof s.d!=='number'||typeof s.t!=='number'||!isFinite(s.d)||s.d<=0||!isFinite(s.t))return 'none';" +
            "var t=s.d*$amount;var r=__fb.seek(t-s.t);if(r!=='ok')return r;" +
            "var after=JSON.parse(__fb.state());return Math.abs(after.t-t)<=2?'ok':'blocked';" +
            "}catch(e){return 'blocked';}})()") { raw ->
            if (!foreground || intent != playerIntent) return@eval
            if (jsString(raw) != "ok") toastOnce("This player did not accept the position; use FF/Rewind")
            refreshOsd(150, "Select position • Hold FF/Rewind for larger skips")
        }
    }

    private fun adjustVolume(dir: Int) {
        if (!audio.isVolumeFixed) {
            val flag = if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, 0)
            refreshOsd(0, "")
        } else {
            // Some devices pin the system volume (the TV owns it); fall back to the video's own volume.
            eval("window.__fb ? __fb.volume(" + (dir * 0.1) + ") : 'none'") { raw ->
                if (jsString(raw) == "none") toastOnce("Volume is controlled by your TV")
                refreshOsd(60, "")
            }
        }
    }

    private fun refreshOsd(delayMs: Long, hint: String) {
        if (!pageAvailable() || !foreground) return
        cancelOsdRefresh()
        val request = osdRequest
        val epoch = pageEpoch
        val refresh = Runnable {
            osdRefresh = null
            if (!pageAvailable() || !foreground || epoch != pageEpoch || request != osdRequest) return@Runnable
            eval("window.__fb ? __fb.state() : ''") { raw ->
                if (!foreground || request != osdRequest) return@eval
                var found = false
                var paused = false
                var t = 0.0
                var d = 0.0
                var vol = 1.0
                var muted = false
                val text = jsString(raw)
                if (!text.isNullOrEmpty()) {
                    try {
                        val o = JSONObject(text)
                        found = o.optInt("f", 0) == 1
                        paused = o.optInt("p", 0) == 1
                        t = o.optDouble("t", 0.0)
                        d = o.optDouble("d", 0.0)
                        vol = o.optDouble("v", 1.0)
                        muted = o.optInt("m", 0) == 1
                    } catch (e: Exception) {
                        found = false
                    }
                }
                showOsd(found, paused, t, d, vol, muted, hint)
            }
        }
        osdRefresh = refresh
        handler.postDelayed(refresh, Math.max(75L, delayMs))
    }

    private fun showOsd(
        found: Boolean,
        paused: Boolean,
        t: Double,
        d: Double,
        videoVolume: Double,
        muted: Boolean,
        hint: String
    ) {
        val status = if (!found) "No video detected" else if (paused) "PAUSED" else "PLAYING"
        val seek: Float? = if (found && d.isFinite() && t.isFinite() && d > 0.0) (t / d).coerceIn(0.0, 1.0).toFloat() else null
        var time = ""
        if (found) {
            time = formatTime(t)
            if (d > 0.0) time += " / " + formatTime(d)
        }

        var volFraction: Float
        var volText: String
        if (!audio.isVolumeFixed) {
            val max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            volFraction = cur / max.toFloat()
            volText = "Volume " + Math.round(volFraction * 100) + "%"
        } else {
            volFraction = videoVolume.toFloat()
            volText = "Video volume " + Math.round(volFraction * 100) + "%"
        }
        if (muted) {
            volFraction = 0f
            volText = "Muted"
        }
        osd.show(status, seek, time, volText, volFraction, hint)
        if (customView == null && cursor.visibility == View.VISIBLE) cursor.bringToFront()
    }

    /** Menu > Full-screen video: the page only allows full screen after a real tap, so we tap for you. */
    private fun fullscreenVideo() {
        cancelPlayerWork()
        val intent = playerIntent
        val interaction = interactionSerial
        val target: View = customView ?: webView
        pendingFullscreen = true
        eval("window.__fb ? __fb.prepareFullscreen() : 'none'") { raw ->
            if (!foreground || interaction != interactionSerial || intent != playerIntent || target !== (customView ?: webView)) return@eval
            if (jsString(raw) != "ok") {
                cancelPendingFullscreen()
                toast("Fullscreen was not prepared; use the player's own button")
            } else {
                postForPage(150) { if (pendingFullscreen && interaction == interactionSerial && intent == playerIntent && target === (customView ?: webView)) sendTap(webView.width / 2f, webView.height / 2f) }
                postForPage(2200) {
                    if (interaction == interactionSerial && intent == playerIntent && customView == null && !cssFullscreenActive) {
                        cancelPendingFullscreen()
                        toast("The page refused full screen. Use the player's own full-screen button.")
                    }
                }
            }
        }
    }

    private fun enterFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        interactionSerial++
        if (view == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        cancelPlayerWork()
        finishPendingTap(cancel = true)
        if (textMode == TextMode.PAGE) closeKeypad()
        customView = view
        customCallback = callback
        // Defensive: if the view still has a parent (a renderer-recovery path can
        // leave a stale customView, or the page can re-request fullscreen), remove
        // it first — otherwise addView throws "child already has a parent" on the
        // main thread. Mirrors the guard in destroyBrowser / disposePopup.
        try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
        // Center the video in the box: the WebView's fullscreen VideoView measures to the
        // video's own aspect ratio (a 2.39:1 film is ~1920x780 on this 16:9 screen), and
        // FrameLayout's default top-left gravity pins it to the top with a black bar below.
        // A same-aspect (16:9) video fills the box exactly, so the gravity is a no-op there.
        val lp = FrameLayout.LayoutParams(MATCH, MATCH)
        lp.gravity = Gravity.CENTER
        fullscreenBox.addView(view, lp)
        fullscreenBox.visibility = View.VISIBLE
        fullscreenBox.keepScreenOn = true
        hideCursor()
        refreshOsd(500, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")
    }

    /** [notifyPage] is true when we leave on our own (Back key) and must tell the WebView. */
    private fun leaveFullscreen(notifyPage: Boolean) {
        interactionSerial++
        val view = customView ?: return
        cancelPlayerWork()
        finishPendingTap(cancel = true)
        val callback = customCallback
        customView = null
        customCallback = null
        fullscreenBox.removeView(view)
        fullscreenBox.visibility = View.GONE
        fullscreenBox.keepScreenOn = false
        osd.hideNow()
        if (pageAvailable()) webView.requestFocus()
        if (notifyPage && pageAvailable()) callback?.onCustomViewHidden()
    }

    /** Ask the page to leave page-level (CSS) fullscreen. */
    private fun exitCssFullscreen() {
        if (!cssFullscreenActive) return
        eval("window.__fb ? __fb.exitCssFullscreen() : 'none'") {
            // The page's exit reports back through the bridge and clears the flag.
            // If it never does (JS error, mid-navigation), clear it ourselves.
            if (cssFullscreenActive) {
                cssFullscreenActive = false
                osd.hideNow()
                if (pageAvailable()) webView.requestFocus()
            }
        }
    }


    /** Keys while a video is full screen. */
    private fun onFullscreenKey(event: KeyEvent): Boolean {
        val rep = event.repeatCount
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (rep == 0) {
                    if (customView != null) leaveFullscreen(true) else exitCssFullscreen()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (rep == 0) videoCommand("togglePlay()", KeyEvent.KEYCODE_SPACE, true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekFromKey(event, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekFromKey(event, 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (rep == 0 || rep % 2 == 0) adjustVolume(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (rep == 0 || rep % 2 == 0) adjustVolume(-1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (rep == 0) {
                    val interaction = interactionSerial
                    val intent = playerIntent
                    val target: View = customView ?: webView
                    fun current() = foreground && interaction == interactionSerial && intent == playerIntent && target === (customView ?: webView) && !keypad.shown()
                    eval("window.__fb ? __fb.unmute() : 'none'") { raw ->
                        if (!current()) return@eval
                        when (jsString(raw)) {
                            "ok", "frame" -> refreshOsd(1500, "Unmuted")
                            "none" -> {
                                eval("window.__fb ? __fb.muteButton() : ''") mute@{ mb ->
                                    if (!current()) return@mute
                                    var ok = false
                                    var fx = 0.5f
                                    var fy = 0.5f
                                    val text = jsString(mb)
                                    if (!text.isNullOrEmpty()) {
                                        try {
                                            val o = JSONObject(text)
                                            ok = o.optInt("ok", 0) == 1
                                            fx = o.optDouble("x", 0.5).toFloat()
                                            fy = o.optDouble("y", 0.5).toFloat()
                                        } catch (e: Exception) { ok = false }
                                    }
                                    if (ok) sendTap(fx * webView.width, fy * webView.height)
                                    else videoCommand("toggleMute()", KeyEvent.KEYCODE_M)
                                }
                            }
                            else -> { }
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (rep == 0) refreshOsd(0, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")
                return true
            }
        }
        return false
    }

    // =====================================================================================
    //  Keyboard input (the on-screen keypad)
    // =====================================================================================

    private fun cancelPendingJsPrompt(): Boolean {
        val result = pendingJsPrompt ?: return false
        pendingJsPrompt = null
        try { result.cancel() } catch (_: Exception) { }
        return true
    }

    private fun promptText(
        title: String,
        initial: String,
        onCancel: (() -> Unit)?,
        goLabel: String = "OK",
        onDone: (String) -> Unit
    ) {
        finishPendingTap(cancel = true)
        interactionSerial++
        cancelPlayerWork()
        editorSession++
        cancelPageEdits()
        cancelPendingJsPrompt()
        hideCursor()
        promptAllowsEmpty = false
        textMode = TextMode.PROMPT
        promptDone = onDone
        promptCancel = onCancel
        buffer.setLength(0)
        buffer.append(initial)
        keypad.open(title, goLabel)
        keypad.bringToFront()
        keypad.setPreview(buffer.toString())
        updateEditorViewport()
    }

    private fun promptUrl() {
        promptText("Enter a web address or search", "", null, "GO") { text ->
            openUrl(normalizeInput(text))
        }
    }

    private fun openPageKeypad() {
        finishPendingTap(cancel = true)
        interactionSerial++
        cancelPlayerWork()
        editorSession++
        cancelPageEdits()
        cancelPendingJsPrompt()
        hideCursor()
        hideIme()
        promptAllowsEmpty = false
        textMode = TextMode.PAGE
        inputFocusConfirmed = true
        promptDone = null
        promptCancel = null
        buffer.setLength(0)
        bufferPassword = false
        keypad.open("Type into the web page", "ENTER")
        keypad.bringToFront()
        updateEditorViewport()
        keypad.post { updateEditorViewport() }
        webView.requestFocus()
        eval("window.__fb && __fb.focusIntoView()")
        syncFieldSoon()
    }

    /** Long-press Select: type into the page if a text field is active, otherwise ask for an address. */
    private fun openKeypadSmart() {
        val interaction = interactionSerial
        val editor = editorSession
        eval("window.__fb ? __fb.field() : ''") { raw ->
            if (!foreground || interaction != interactionSerial || editor != editorSession || textMode != TextMode.NONE || menu.shown() || listPanel.shown() || textPanel.shown() || startPage.shown()) return@eval
            var hasField = false
            try {
                val text = jsString(raw)
                if (!text.isNullOrEmpty()) hasField = JSONObject(text).optInt("ok", 0) == 1
            } catch (e: Exception) {
                hasField = false
            }
            if (hasField) openPageKeypad() else promptUrl()
        }
    }

    /** A real tap on a text field (reported by page_helpers.js) opens the keypad by itself. */
    private fun onEditableClicked() {
        // The legacy helper has already checked the actual active editable after
        // a trusted click, including in a frame we cannot inspect from the top.
        focusFromPage("{\"ok\":1}")
    }

    private fun canTypeIntoPage(): Boolean {
        if (!pageAvailable() || !foreground) return false
        // A field recorded from a real tap is a typing permit: the JS edit path
        // targets it by ID without Android focus. Only an unselected field blocks.
        if (inputFrame.isEmpty() || inputFieldId.isEmpty()) {
            toastOnce("Select the search field again")
            return false
        }
        return true
    }

    private fun queuePageEdit(action: String, text: String = "") {
        if (!canTypeIntoPage() || textMode != TextMode.PAGE) return
        if (pageEdits.size >= 128 || text.length > 4096) {
            toastOnce("Typing queue is full; wait for the page")
            return
        }
        pageEdits.addLast(PageEdit(action, text, editorSession, documentToken, inputFrame, inputFieldId))
        drainPageEdits()
    }

    private fun currentEdit(edit: PageEdit): Boolean = foreground && pageAvailable() &&
        textMode == TextMode.PAGE && edit.session == editorSession && edit.token == documentToken &&
        edit.frame == inputFrame && edit.field == inputFieldId &&
        (inputFocusConfirmed || (edit.frame.isNotEmpty() && edit.field.isNotEmpty()))

    private fun cancelPageEdits() {
        nativeEditCancel?.invoke()
        nativeEditCancel = null
        pageEdits.clear()
        pageEditActive = null
    }

    private fun drainPageEdits() {
        if (pageEditActive != null) return
        val edit = pageEdits.pollFirst() ?: return
        if (!currentEdit(edit)) { pageEdits.clear(); return }
        pageEditActive = edit
        val qualified = edit.frame.isNotEmpty() && edit.field.isNotEmpty()
        if (!qualified) {
            dispatchNativePageEdit(edit)
            return
        }
        val script = "window.__tvUi && __tvUi.edit ? __tvUi.edit(" +
            JSONObject.quote(edit.action) + "," + JSONObject.quote(edit.text) + "," +
            JSONObject.quote(edit.frame) + "," + JSONObject.quote(edit.field) + ",0) : '{\"status\":\"unreachable\"}'"
        eval(script) { raw ->
            if (pageEditActive !== edit) return@eval
            if (!currentEdit(edit)) { cancelPageEdits(); return@eval }
            val state = try { JSONObject(jsString(raw) ?: "") } catch (_: Exception) { null }
            val status = state?.optString("status", "").orEmpty()
            // Unreachable is a pre-action result, so native fallback cannot duplicate a DOM edit.
            // A timeout/exception/blocked edit is NOT permission to replay keystrokes.
            if (status == "unreachable") dispatchNativePageEdit(edit)
            else finishPageEdit(edit, status == "applied" || status == "submitted" || status == "key-dispatched", state)
        }
    }

    private fun dispatchNativePageEdit(edit: PageEdit) {
        nativeEditCancel = NativePageEditor.dispatch(webView, edit.action, edit.text) { accepted ->
            nativeEditCancel = null
            finishPageEdit(edit, accepted, null)
        }
    }

    private fun finishPageEdit(edit: PageEdit, accepted: Boolean, state: JSONObject?) {
        if (pageEditActive !== edit) return
        pageEditActive = null
        DiagnosticLog.record(this, "edit_result", JSONObject()
            .put("action", edit.action).put("field", edit.field)
            .put("status", state?.optString("status", "none") ?: "none"))
        if (!currentEdit(edit)) { pageEdits.clear(); return }
        if (!accepted) {
            inputFocusConfirmed = false
            pageEdits.clear()
            keypad.setPreview("(website did not accept input)")
            toastOnce("Select the search field again; input was not confirmed")
            return
        }
        if (state?.optInt("ok", 0) == 1 && state.optString("doc") == edit.token &&
            state.optString("frame") == edit.frame && state.optString("field") == edit.field) {
            inputFieldState = state.toString()
            bufferPassword = state.optInt("pw", 0) == 1
            val preview = if (state.optInt("pw", 0) == 1) "•".repeat(state.optInt("length", 0).coerceIn(0, 8192)) else state.optString("v", "")
            keypad.setPreview(preview)
        }
        if (edit.action == "submit") { pageEdits.clear(); closeKeypad(); return }
        syncFieldSoon()
        drainPageEdits()
    }

    /** The preview shows what the user typed; the page's report refines it when available. */
    private fun showBufferPreview() {
        keypad.setPreview(if (bufferPassword) "\u2022".repeat(buffer.length) else buffer.toString())
    }

    private fun typeText(s: String) {
        if (textMode == TextMode.PAGE) {
            buffer.append(s)
            showBufferPreview()
            queuePageEdit("insert", s); return
        }
        buffer.append(s)
        keypad.setPreview(buffer.toString())
    }

    private fun typeBackspace() {
        if (textMode == TextMode.PAGE) {
            if (buffer.isNotEmpty()) buffer.setLength(buffer.offsetByCodePoints(buffer.length, -1))
            showBufferPreview()
            queuePageEdit("delete"); return
        }
        if (buffer.isNotEmpty()) buffer.setLength(buffer.offsetByCodePoints(buffer.length, -1))
        keypad.setPreview(buffer.toString())
    }

    private fun typeClear() {
        if (textMode == TextMode.PAGE) {
            buffer.setLength(0)
            showBufferPreview()
            queuePageEdit("clear"); return
        }
        buffer.setLength(0)
        keypad.setPreview("")
    }

    private fun typeGo() {
        if (textMode == TextMode.PAGE) { queuePageEdit("submit"); return }
        val allowEmpty = promptAllowsEmpty
        val text = if (allowEmpty) buffer.toString() else buffer.toString().trim()
        val done = promptDone
        val cancel = promptCancel
        closeKeypad()
        if (text.isEmpty() && !allowEmpty) cancel?.invoke() else done?.invoke(text)
    }

    private fun cancelKeypad() {
        val cancel = if (textMode == TextMode.PROMPT) promptCancel else null
        closeKeypad()
        cancel?.invoke()
    }

    private fun closeKeypad() {
        interactionSerial++
        editorSession++
        cancelPageEdits()
        inputFrame = ""
        inputFieldState = ""
        inputFieldId = ""
        inputFocusConfirmed = true
        keyboardDismissedUntil = SystemClock.elapsedRealtime() + 1000L
        handler.removeCallbacks(syncRunnable)
        keypad.close()
        textMode = TextMode.NONE
        promptAllowsEmpty = false
        promptDone = null
        promptCancel = null
        updateEditorViewport()
        restoreFocus()
    }

    private fun dispatchToPage(action: Int, keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            now, now, action, keyCode, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        // The page always lives in the WebView. In full screen the custom view is only the
        // video's rendering surface — keys sent to it never reach the page.
        webView.dispatchKeyEvent(ev)
    }

    private fun pressKey(keyCode: Int, meta: Int) {
        dispatchToPage(KeyEvent.ACTION_DOWN, keyCode, meta)
        dispatchToPage(KeyEvent.ACTION_UP, keyCode, meta)
    }

    private fun sendKeyToPage(keyCode: Int) {
        if (!pageAvailable() || !foreground) return
        val previousFocus = root.findFocus()
        webView.requestFocus()
        pressKey(keyCode, 0)
        if (previousFocus != null && previousFocus !== webView && previousFocus.isAttachedToWindow &&
            (menu.shown() || listPanel.shown() || textPanel.shown() || keypad.shown())) previousFocus.requestFocus()
    }

    private fun syncFieldSoon() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, 90)
    }

    /** Mirror the page field's real contents (autocomplete, max length...) in the keypad preview. */
    private fun syncField() {
        if (textMode != TextMode.PAGE) return
        val session = editorSession
        val frame = inputFrame
        val field = inputFieldId
        val token = documentToken
        val qualified = frame.isNotEmpty() && field.isNotEmpty()
        val script = if (qualified) "window.__tvUi ? __tvUi.fieldState(" + JSONObject.quote(frame) + "," + JSONObject.quote(field) + ",0) : ''"
                     else "window.__fb ? __fb.field() : ''"
        eval(script) { raw ->
            if (foreground && session == editorSession && textMode == TextMode.PAGE) {
                var ok = false
                var value = ""
                var password = false
                if (qualified && (frame != inputFrame || field != inputFieldId || token != documentToken)) return@eval
                val fresh = jsString(raw)
                val validFresh = if (qualified) try {
                    val o = JSONObject(fresh ?: "")
                    o.optInt("ok") == 1 && o.optString("doc") == token && o.optString("frame") == frame && o.optString("field") == field
                } catch (_: Exception) { false } else !fresh.isNullOrEmpty()
                // An unreachable cross-origin field may use its qualified bridge snapshot.
                val text = if (validFresh) fresh else if (qualified) inputFieldState else fresh
                if (!text.isNullOrEmpty()) {
                    try {
                        val o = JSONObject(text)
                        ok = o.optInt("ok", 0) == 1
                        value = o.optString("v", "")
                        password = o.optInt("pw", 0) == 1
                        if (password) value = "•".repeat(o.optInt("length", value.length).coerceIn(0, 8192))
                        bufferPassword = password
                    } catch (e: Exception) {
                        ok = false
                    }
                }
                if (ok) {
                    keypad.setPreview(if (password) "\u2022".repeat(value.length) else value)
                } else if (buffer.isEmpty()) {
                    keypad.setPreview("(tap a text field in the page first)")
                } else {
                    keypad.setPreview(buffer.toString())
                }
            }
        }
    }

    // =====================================================================================
    //  Menus, bookmarks
    // =====================================================================================

    private fun syncFocusBlocking() {
        val blocked = menu.shown() || listPanel.shown() || textPanel.shown()
        startPage.descendantFocusability =
            if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    private fun restoreFocus() {
        if (menu.shown() || listPanel.shown() || textPanel.shown()) return
        if (startPage.shown()) startPage.focusFirst() else webView.requestFocus()
    }

    private fun closeAllPanels() {
        interactionSerial++
        cancelPendingFullscreen()
        promptAllowsEmpty = false
        cancelPendingJsPrompt()
        finishPendingTap(cancel = true)
        menu.hide()
        listPanel.hide()
        textPanel.hide()
        menuBack = null
        bookmarksFolder = null
        movingBookmarkUrl = null
        if (keypad.shown()) {
            editorSession++
            cancelPageEdits()
            inputFrame = ""
            inputFieldId = ""
            inputFieldState = ""
            handler.removeCallbacks(syncRunnable)
            keypad.close()
            textMode = TextMode.NONE
            promptDone = null
            promptCancel = null
        }
        updateEditorViewport()
        syncFocusBlocking()
    }

    private fun showMenu(title: String, items: List<MenuItem>, back: (() -> Unit)?) {
        showMenuAt(title, items, 0, back)
    }

    private fun showMenuAt(title: String, items: List<MenuItem>, focusIndex: Int, back: (() -> Unit)?) {
        interactionSerial++
        cancelPendingFullscreen()
        finishPendingTap(cancel = true)
        hideCursor()
        listPanel.hide()
        movingBookmarkUrl = null
        textPanel.hide()
        menuBack = back
        val wrapped = ArrayList<MenuItem>()
        for (item in items) {
            wrapped.add(MenuItem(item.label) {
                menuBack = null
                syncFocusBlocking()
                item.action()
                // actions that open nothing new (Reload, toggles...) hand focus back to the page / start page
                restoreFocus()
            })
        }
        menu.show(title, wrapped, focusIndex)
        menu.bringToFront()
        syncFocusBlocking()
    }

    private fun hideMenu() {
        menu.hide()
        menuBack = null
        syncFocusBlocking()
        restoreFocus()
    }

    private fun hideList() {
        listPanel.hide()
        bookmarksFolder = null
        movingBookmarkUrl = null
        syncFocusBlocking()
        startPage.refresh()
        restoreFocus()
    }

    private fun listBack() {
        if (bookmarksFolder != null && store.hasFolders()) showBookmarks(null, 0) else hideList()
    }

    private fun showMainMenu() {
        val onStart = startPage.shown()
        val url: String? = if (onStart) null else webView.url
        val items = ArrayList<MenuItem>()

        // 1. Scroll submenu
        items.add(MenuItem("Scroll submenu/page with D-pad") {
            scrollMode = true
            showCursor()
            toast("D-pad scrolls under pointer; Back returns to pointer mode")
        })
        // 2. Start page
        items.add(MenuItem("Start page") { showStartPage(false) })
        // 3. Reload
        items.add(MenuItem("Reload") { reloadPage() })
        // 4. Add bookmark
        if (url != null && store.contains(url)) {
            items.add(MenuItem("Remove bookmark") {
                if (editBookmarks { store.remove(url) }) toast("Bookmark removed")
            })
        } else {
            items.add(MenuItem("Add bookmark") { startSaveBookmark() })
        }
        // 5. Bookmarks
        items.add(MenuItem("Bookmarks...") { showBookmarks(null, 0) })
        // 6. Search
        items.add(MenuItem("Address / search") { promptUrl() })
        // 7. Full screen
        items.add(MenuItem("Full-screen video") { fullscreenVideo() })
        // 8. Fit submenus to screen
        items.add(MenuItem("Fit open submenus to screen") {
            eval("window.__tvUi ? __tvUi.fitMenus() : 0")
            toast("Adjusted recognized dropdown menus")
        })
        // 9. Block redirects
        val host: String? = if (url != null) {
            try {
                Uri.parse(url).host
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        if (host != null) {
            val site = PopupGuard.cleanHost(host)
            val label = "Block redirects from $site: " + (if (guard.isProtected(site)) "ON" else "OFF")
            items.add(MenuItem(label) { toggleProtectedSite(site) })
        }
        // 10. Back
        items.add(MenuItem("Back") { navigateHistory(-1) })
        // 11. Settings
        items.add(MenuItem("Settings...") { showSettings(0) })
        // 12. Exit browser
        items.add(MenuItem("Exit browser") { finish() })

        showMenu("Menu", items, null)
    }

    private fun toggleGuard() {
        val on = guard.toggle()
        DiagnosticLog.record(this, "setting_popup_guard", JSONObject().put("enabled", on))
        webView.settings.javaScriptCanOpenWindowsAutomatically = !on
        toast("Popup Guard " + (if (on) "ON" else "OFF"))
    }

    private fun toggleProtectedSite(site: String) {
        if (guard.isBuiltIn(site)) {
            toast("$site is protected by default")
            return
        }
        guard.toggleProtected(site)
        toast("Redirects from $site: " + (if (guard.isProtected(site)) "blocked" else "allowed"))
    }

    private fun toggleDesktop() {
        desktopSite = !desktopSite
        DiagnosticLog.record(this, "setting_desktop", JSONObject().put("enabled", desktopSite))
        prefs.edit().putBoolean("desktopSite", desktopSite).apply()
        applyUserAgent()
        if (!startPage.shown()) reloadPage()
        toast("Desktop site " + (if (desktopSite) "ON" else "OFF"))
    }

    /** Menu > Add bookmark: choose where to save it. */
    private fun startSaveBookmark() {
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

    private fun editBookmarks(action: () -> Unit): Boolean {
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
    // Legacy Fire OS: public Downloads. Android 10+: document picker where available,
    // with public MediaStore Downloads for TVs that lack a document picker.
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 28) return
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            DiagnosticLog.record(this, "storage_permission", JSONObject().put("granted", granted))
            if (!granted) toast("Storage permission denied — public Downloads export needs it")
        }
    }

    private fun bookmarkPicker(export: Boolean) {
        try {
            val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                if (export) putExtra(Intent.EXTRA_TITLE, "bookmarks.json")
            }
            startActivityForResult(intent, if (export) REQ_BOOKMARK_EXPORT else REQ_BOOKMARK_IMPORT)
        } catch (_: android.content.ActivityNotFoundException) {
            toast("No document picker; export uses public Downloads. Import can use Open with Buccaneer.")
            if (export) exportBookmarksFile() else importBookmarksFile()
        } catch (e: Exception) { report("Cannot open bookmark picker", e); toast("Bookmark picker unavailable") }
    }

    private fun exportBookmarks() {
        if (Build.VERSION.SDK_INT <= 28) { exportBookmarksFile(); return }
        showMenu("Export bookmarks", listOf(
            MenuItem("Choose a file location") { bookmarkPicker(true) },
            MenuItem("Public Downloads (survives uninstall)") { exportBookmarksFile() }
        )) { showMainMenu() }
    }

    private fun importBookmarks() {
        if (Build.VERSION.SDK_INT <= 28) { importBookmarksFile(); return }
        showMenu("Import bookmarks", listOf(
            MenuItem("Choose a JSON file") { bookmarkPicker(false) },
            MenuItem("Read last backup made by this install") { importBookmarksFile() }
        )) { showMainMenu() }
    }

    private fun runFileOperation(work: () -> BookmarkJobs.Result) {
        val owner = BookmarkJobs.UiOwner(activityInstance, interactionSerial)
        if (!BookmarkJobs.submit { work().copy(owner = owner) }) toast("A bookmark transfer is already running")
    }

    override fun onBookmarkJob(result: BookmarkJobs.Result): Boolean {
        if (!foreground || activityDestroyed || recoveringRenderer) return false
        result.error?.let { report("Bookmark transfer failed", it) }
        toast(result.message)
        if (result.imported) {
            startPage.refresh()
            val owner = result.owner
            if (owner != null && owner.activity == activityInstance && owner.interaction == interactionSerial && !keypad.shown()) {
                closeAllPanels()
                showBookmarks(null, 0)
            }
        }
        return true
    }

    private fun exportBookmarksFile() {
        val app = applicationContext
        val bookmarks = store
        runFileOperation { BookmarkJobs.Result(BookmarkFiles.export(app, bookmarks.exportPortableJson())) }
    }

    private fun importBookmarkText(text: String) {
        val bookmarks = store
        runFileOperation {
            if (!bookmarks.importJson(text)) throw java.io.IOException("Not a supported bookmark backup")
            BookmarkJobs.Result("Imported ${bookmarks.all().size} bookmarks", imported = true)
        }
    }

    private fun importBookmarksFile() {
        val app = applicationContext
        val bookmarks = store
        runFileOperation {
            val raw = BookmarkFiles.readSaved(app)
            if (!bookmarks.importJson(raw)) throw java.io.IOException("Not a supported bookmark backup")
            BookmarkJobs.Result("Imported ${bookmarks.all().size} bookmarks", imported = true)
        }
    }

    private fun importBookmarkUri(uri: Uri) {
        if (uri.scheme != "content") { toast("Use a file manager that grants access to the backup"); return }
        val app = applicationContext
        val bookmarks = store
        runFileOperation {
            val raw = BookmarkFiles.read(app.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Cannot read selected backup"))
            if (!bookmarks.importJson(raw)) throw java.io.IOException("Not a supported bookmark backup")
            BookmarkJobs.Result("Imported ${bookmarks.all().size} bookmarks", imported = true)
        }
    }

    private fun handleBookmarkIntent(value: Intent?) {
        if (value?.type != "application/json") return
        val uri = when (value.action) {
            Intent.ACTION_VIEW -> value.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") value.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        closeAllPanels()
        importBookmarkUri(uri)
        value.action = null // Do not replay an import after Activity recreation.
    }

    override fun onNewIntent(value: Intent?) {
        super.onNewIntent(value)
        if (value != null) { intent = value; handleBookmarkIntent(value) }
    }

    /** Menu > Bookmarks: folders (when you use them) or all bookmarks, with an Options button per row. */
    private fun showBookmarks(folder: String?, focusRow: Int) = showBookmarks(folder, focusRow, null)

    private fun showBookmarks(folder: String?, focusRow: Int, movingUrl: String?) {
        interactionSerial++
        cancelPendingFullscreen()
        finishPendingTap(cancel = true)
        hideCursor()
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

        menu.hide()
        menuBack = null
        textPanel.hide()
        val moving = movingBookmarkUrl?.let { store.find(it) }
        val heading = if (moving != null) "Moving: ${moving.title}" else if (totalRows > 50) "$title • ${offset / 50 + 1}/${(totalRows + 49) / 50}" else title
        listPanel.show(heading, rows, empty, footer, focusRow - offset,
            if (moving != null) "Up / Down: move bookmark     Select / Back: done (saved automatically)" else null)
        listPanel.bringToFront()
        syncFocusBlocking()
    }

    private fun showBookmarkOptions(b: Bookmark, folder: String?, index: Int) {
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
    private fun moveBookmarkStep(url: String, direction: Int) {
        val b = store.find(url) ?: run { finishBookmarkMove(); return }
        val list = store.inFolder(b.folder)
        val index = list.indexOfFirst { it.url == b.url }
        if (index < 0 || index + direction !in list.indices) return
        if (!editBookmarks { if (direction < 0) store.moveUp(url) else store.moveDown(url) }) return
        val updated = store.inFolder(b.folder).indexOfFirst { it.url == b.url }.coerceAtLeast(0)
        showBookmarks(bookmarksFolder, updated, url)
    }

    private fun finishBookmarkMove() {
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

    // =====================================================================================
    //  Settings, dark mode, diagnostics
    // =====================================================================================

    /** True when this device's WebView can darken web pages at all. */
    @Suppress("DEPRECATION")
    private fun darkModeSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ||
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
        } catch (e: Exception) {
            false
        }

    /**
     * Dark mode for web pages. The app theme is dark (res/values-v29/styles.xml), so sites that ship a
     * dark style use it, and a WebView that can darken pages does so for the rest.
     */
    @Suppress("DEPRECATION")
    private fun applyDarkMode() {
        val s = webView.settings
        try {
            // VERIFIED WORKING: FORCE_DARK_ON + strategy 2
            // (PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING). This is the configuration
            // where flixer loads without crashing and coreflix buttons work.
            // Omitting setForceDarkStrategy (the render-client default) breaks flixer.
            // fmhy stays light (VitePress color-scheme) — do NOT inject CSS to fix it.
            // Every fmhy dark-mode attempt has crashed flixer's PowerVR GPU.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(s, if (darkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(s, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
                }
                DiagnosticLog.record(this, "dark_mode_applied", JSONObject().apply {
                    put("method", "force_dark_strategy_2")
                    put("enabled", darkMode)
                })
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, darkMode)
                DiagnosticLog.record(this, "dark_mode_applied", JSONObject().apply {
                    put("method", "algorithmic_darkening")
                    put("enabled", darkMode)
                })
            } else {
                DiagnosticLog.record(this, "dark_mode_applied", JSONObject().apply {
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
        DiagnosticLog.record(this, "setting_autoplay", JSONObject().put("enabled", autoplay))
        prefs.edit().putBoolean("autoplay", autoplay).apply()
        webView.settings.mediaPlaybackRequiresUserGesture = !autoplay
        toast("Autoplay without a click " + (if (autoplay) "ON" else "OFF"))
    }

    private fun toggleLowQuality() {
        lowQuality = !lowQuality
        DiagnosticLog.record(this, "setting_low_quality", JSONObject().put("enabled", lowQuality))
        prefs.edit().putBoolean("lowQuality", lowQuality).apply()
        toast("Prefer low quality streams " + (if (lowQuality) "ON" else "OFF"))
    }

    private fun toggleExclusive() {
        exclusiveVideo = !exclusiveVideo
        DiagnosticLog.record(this, "setting_exclusive_video", JSONObject().put("enabled", exclusiveVideo))
        prefs.edit().putBoolean("exclusiveVideo", exclusiveVideo).apply()
        toast("One video at a time " + (if (exclusiveVideo) "ON" else "OFF"))
    }

    private fun toggleSoftwareRendering() {
        softwareRendering = !softwareRendering
        DiagnosticLog.record(this, "setting_software_rendering", JSONObject().put("enabled", softwareRendering))
        prefs.edit().putBoolean("softwareRendering", softwareRendering).apply()
        // Save the current URL so onCreate can restore it after the WebView is recreated.
        val url = lastPageUrl
        if (!softwareRendering && (try { val h = Uri.parse(url).host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: ""; h == "flixer.gd" || h.endsWith(".flixer.gd") || h == "flixer.su" || h.endsWith(".flixer.su") } catch (_: Exception) { false })) {
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
    private fun showSettings(focus: Int) {
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
        items.add(MenuItem("Diagnostics...") { showDiagnosticMenu() })
        showMenuAt("Settings", items, focus) { showMainMenu() }
    }

    private fun showDiagnosticMenu() {
        val logging = DiagnosticLog.isEnabled(this)
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Diagnostic logging: " + (if (logging) "ON" else "OFF")) {
            if (logging) {
                DiagnosticLog.record(this, "logging_disabled")
                DiagnosticLog.stop(this)
                updatePageDiagnosticState(false)
                toast("Diagnostic logging OFF")
            } else {
                DiagnosticLog.startNewSession(this)
                updatePageDiagnosticState(true)
                DiagnosticLog.record(this, "diagnostic_enabled", JSONObject().put("page", DiagnosticLog.safeUrl(webView.url)))
                toast("New diagnostic session started")
            }
            showDiagnosticMenu()
        })
        items.add(MenuItem("Start new diagnostic session") {
            DiagnosticLog.startNewSession(this)
            updatePageDiagnosticState(true)
            DiagnosticLog.record(this, "diagnostic_enabled", JSONObject().put("page", DiagnosticLog.safeUrl(webView.url)))
            toast("New diagnostic session started")
            showDiagnosticMenu()
        })
        items.add(MenuItem("Stop diagnostic logging") {
            DiagnosticLog.record(this, "logging_disabled")
            DiagnosticLog.stop(this)
            updatePageDiagnosticState(false)
            toast("Diagnostic logging OFF")
            showDiagnosticMenu()
        })
        items.add(MenuItem("Export diagnostic log") { exportDiagnostics() })
        items.add(MenuItem("Clear diagnostic log") {
            DiagnosticLog.clear(this)
            updatePageDiagnosticState(false)
            toast("Diagnostic log cleared")
            showDiagnosticMenu()
        })
        items.add(MenuItem("Show diagnostics snapshot") { showDiagnostics() })
        showMenuAt("Diagnostics", items, 0) { showSettings(4) }
    }

    private fun updatePageDiagnosticState(enabled: Boolean) {
        val js = if (enabled) "true" else "false"
        try {
            webView.evaluateJavascript("window.__fb && __fb.setDiagnosticLogging && __fb.setDiagnosticLogging($js)", null)
        } catch (_: Throwable) { }
    }

    private fun exportDiagnostics() {
        if (!DiagnosticLog.hasData(this)) {
            toast("No diagnostic data to export")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "Buccaneer-diagnostics-${System.currentTimeMillis()}.zip")
            }
            startActivityForResult(intent, REQ_DIAGNOSTIC_EXPORT)
        } catch (e: Exception) {
            report("Cannot open diagnostic export", e)
            toast("Diagnostic export is unavailable")
        }
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    /** Menu > Settings > Diagnostics: what this device can do, the last problem, and what the page's video is doing. */
    private fun showDiagnostics() {
        val interaction = interactionSerial
        val sb = StringBuilder()
        sb.append("Buccaneer ").append(versionName()).append('\n')
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append(", Android ").append(Build.VERSION.RELEASE)
        sb.append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        val chrome = Regex("Chrome/([0-9.]+)").find(defaultUa)?.groupValues?.get(1) ?: "unknown"
        sb.append("Web engine: Chrome ").append(chrome).append('\n')
        val provider = try { WebViewCompat.getCurrentWebViewPackage(this) } catch (_: Throwable) { null }
        sb.append("WebView provider: ")
        sb.append(if (provider == null) "unknown" else provider.packageName + "/" + provider.versionName).append('\n')
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("Memory: ").append(mi.totalMem / 1048576L).append(" MB total, ")
            sb.append(mi.availMem / 1048576L).append(" MB free, low-RAM device: ")
            sb.append(if (am.isLowRamDevice) "yes" else "no").append('\n')
        } catch (e: Exception) {
            sb.append("Memory: unknown\n")
        }
        sb.append("Dark mode: ")
        sb.append(if (!darkModeSupported()) "not supported by this WebView" else if (darkMode) "ON" else "OFF")
        sb.append('\n')
        sb.append("Page scripts run before the page: ")
        sb.append(if (scriptsAtDocumentStart) "yes" else "no (fallback)").append('\n')
        sb.append("Diagnostic logging: ").append(if (DiagnosticLog.isEnabled(this)) "ON" else "OFF")
        sb.append(", session: ").append(DiagnosticLog.currentSessionId(this).take(12)).append('\n')
        sb.append("Popup Guard: ").append(if (guard.enabled) "ON" else "OFF")
        sb.append(", blocked this session: ").append(blockedCount).append('\n')
        sb.append("Autoplay without a click: ").append(if (autoplay) "ON" else "OFF")
        sb.append(",  one video at a time: ").append(if (exclusiveVideo) "ON" else "OFF").append('\n')
        sb.append('\n').append("Last problem: ").append(CrashLog.last(this) ?: "none recorded").append('\n')

        if (startPage.shown()) {
            showTextPanel("Diagnostics", sb.toString())
            return
        }
        DiagnosticLog.record(this, "diagnostic_snapshot_requested", JSONObject().put("page", DiagnosticLog.safeUrl(webView.url)))
        eval("window.__fb ? __fb.diag() : ''") { raw ->
            if (!foreground || interaction != interactionSerial) return@eval
            val reportText = jsString(raw)
            sb.append('\n').append("Video on this page:\n")
            sb.append(describeVideoReport(reportText))
            if (!reportText.isNullOrBlank()) {
                try { DiagnosticLog.record(this, "diagnostic_snapshot", JSONObject(reportText)) } catch (_: Throwable) { }
            }
            showTextPanel("Diagnostics", sb.toString())
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_BOOKMARK_EXPORT) {
            val resolver = applicationContext.contentResolver
            val bookmarks = store
            runFileOperation {
                val json = bookmarks.exportPortableJson()
                resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw java.io.IOException("Cannot write bookmark document")
                BookmarkJobs.Result("Exported ${bookmarks.all().size} bookmarks to selected document")
            }
            return
        }
        if (requestCode == REQ_BOOKMARK_IMPORT) {
            importBookmarkUri(uri)
            return
        }
        if (requestCode != REQ_DIAGNOSTIC_EXPORT) return
        try {
            val app = applicationContext
            DiagnosticLog.record(this, "diagnostic_export_started")
            DiagnosticLog.exportAsync(app, {
                app.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Cannot write diagnostic file")
            }) { error ->
                handler.post {
                    if (!activityDestroyed && foreground) {
                        if (error == null) toast("Diagnostic log exported")
                        else { report("Diagnostic export failed", error); toast("Diagnostic export failed") }
                    }
                }
            }
        } catch (e: Exception) {
            report("Diagnostic export failed", e)
            toast("Diagnostic export failed")
        }
    }

    private fun yesNo(v: Int): String = if (v == 1) "yes" else "no"

    private fun describeVideoReport(text: String?): String {
        if (text.isNullOrEmpty()) return "  The page helper script is not running on this page.\n"
        val sb = StringBuilder()
        try {
            val o = JSONObject(text)
            val videos = o.optJSONArray("videos")
            val count = if (videos == null) 0 else videos.length()
            if (videos == null || count == 0) {
                sb.append("  No video element found in the main page.\n")
                if (o.optInt("frames", 0) > 0) {
                    sb.append("  A frame from another site is on the page. The player is probably inside it ")
                    sb.append("and cannot be inspected from here.\n")
                }
            } else {
                for (i in 0 until count) {
                    val v = videos.getJSONObject(i)
                    val readyState = v.optInt("rs", 0)
                    val picW = v.optInt("vw", 0)
                    val picH = v.optInt("vh", 0)
                    val frames = v.optInt("fr", -1)
                    val dropped = v.optInt("dr", -1)
                    val paused = v.optInt("p", 0) == 1
                    sb.append("  Video ").append(i + 1).append(": shown ")
                    sb.append(v.optInt("w", 0)).append('x').append(v.optInt("h", 0))
                    sb.append(", picture ").append(picW).append('x').append(picH)
                    sb.append(if (paused) ", paused" else ", playing")
                    if (v.optInt("m", 0) == 1) sb.append(", muted")
                    sb.append(", ready ").append(readyState)
                    if (frames >= 0) sb.append(", frames ").append(frames).append(" (dropped ").append(dropped).append(')')
                    if (v.optInt("e", 0) != 0) sb.append(", ERROR code ").append(v.optInt("e", 0))
                    sb.append('\n')
                    if (readyState >= 2 && picW == 0) {
                        sb.append("    -> sound is decoded but NO PICTURE yet: stream format, metadata, buffering or decoder state may be involved.\n")
                    } else if (!paused && frames == 0) {
                        sb.append("    -> playing, but no video frame has been decoded yet.\n")
                    } else if (frames > 50 && dropped * 4 > frames) {
                        sb.append("    -> many frames are dropped: decoding, rendering, buffering or system load may be involved.\n")
                    }
                }
            }
            val mse = o.optJSONObject("mse")
            if (mse == null) {
                sb.append("  This WebView has no MediaSource, which streaming players need.\n")
            } else {
                sb.append("  Streaming codecs (MSE): H.264 ").append(yesNo(mse.optInt("h264", 0)))
                sb.append(", HEVC ").append(yesNo(mse.optInt("hevc", 0)))
                sb.append(", VP9 ").append(yesNo(mse.optInt("vp9", 0)))
                sb.append(", AV1 ").append(yesNo(mse.optInt("av1", 0))).append('\n')
            }
        } catch (e: Exception) {
            sb.append("  Could not read the video report.\n")
        }
        return sb.toString()
    }

    private fun showTextPanel(title: String, text: String) {
        interactionSerial++
        cancelPendingFullscreen()
        finishPendingTap(cancel = true)
        hideCursor()
        menu.hide()
        listPanel.hide()
        menuBack = null
        textPanel.show(title, text)
        textPanel.bringToFront()
        syncFocusBlocking()
    }

    private fun hideTextPanel() {
        textPanel.hide()
        syncFocusBlocking()
        restoreFocus()
    }

    /** Keep a problem for Settings > Diagnostics (and logcat, tag "Buccaneer"). */
    private fun report(what: String, e: Throwable) {
        DiagnosticLog.recordCritical(this, "app_error", JSONObject().apply {
            put("what", what.take(160))
            put("type", e.javaClass.name.take(200))
            put("message", (e.message ?: "").take(1000))
        })
        CrashLog.record(this, what, Log.getStackTraceString(e))
    }

    // =====================================================================================
    //  Small helpers
    // =====================================================================================

    private fun toast(msg: String) {
        lastToast?.cancel()
        val t = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        lastToast = t
        t.show()
    }

    /** Same as toast() but at most once every 2.5 s (key repeats must not spam). */
    private fun toastOnce(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastInfoToastAt > 2500) {
            lastInfoToastAt = now
            toast(msg)
        }
    }
}
