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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale

/**
 * Fire TV browser, operated entirely by remote: a start page of bookmark buttons; D-pad moves an
 * on-screen pointer (Select clicks, pointer at the screen edge scrolls); keypad via Menu >
 * Address/search, tapping a text field, or holding Select; media keys drive the page's video.
 * All remote keys arrive in dispatchKeyEvent() and are routed by what is onscreen.
 */
class MainActivity : Activity(), BookmarkJobs.Listener {


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

    // Dead-video-retry cooldown, keyed by the top-level page host+path (stable across reloads).
    // On the Activity, not cross-origin iframe localStorage; lost on process death (worst case: one extra retry).
    private val deadVideoRetryState = HashMap<String, Long>()
    private val deadVideoRetryLock = Any()
    private val DEAD_VIDEO_RETRY_COOLDOWN_MS = 60000L
    private val DEAD_VIDEO_RETRY_MAX_ENTRIES = 50
    // Top-level page URL, cached on the main thread so the retry-cooldown key can be read off
    // the UI thread (WebView.getUrl() is main-thread-only; JavascriptInterface runs on the JavaBridge thread).
    @Volatile
    private var cachedPageUrl: String? = null

    private val pageKeys = PageKeyDispatcher({ webView }, { root }, { pageAvailable() }, { foreground },
        { menu.shown() || listPanel.shown() || textPanel.shown() || keypad.shown() })
    private val media: MediaController by lazy { MediaController(object : MediaHost {
        override val webView get() = this@MainActivity.webView
        override val customView get() = this@MainActivity.customView
        override val cssFullscreenActive get() = this@MainActivity.cssFullscreenActive
        override val onFlixerPage get() = this@MainActivity.onFlixerPage
        override val nativeSeekAllowed get() = !keypad.shown() && !startPage.shown()
        override val nativeMuteAllowed get() = !keypad.shown()
        override val audio get() = this@MainActivity.audio
        override val osd get() = this@MainActivity.osd
        override val cursor get() = this@MainActivity.cursor
        override val handler get() = this@MainActivity.handler
        override fun pageAvailable() = this@MainActivity.pageAvailable()
        override fun eval(script: String, done: ((String?) -> Unit)?) = this@MainActivity.eval(script, done)
        override fun postForPage(delayMs: Long, action: () -> Unit) = this@MainActivity.postForPage(delayMs, action)
        override fun sendKeyToPage(keyCode: Int) = pageKeys.sendKeyToPage(keyCode)
        override fun sendTap(x: Float, y: Float, mouse: Boolean) = this@MainActivity.sendTap(x,y,mouse)
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun toastOnce(msg: String) = this@MainActivity.toastOnce(msg)
    }, pageIdentity) }

    private val bookmarkUi: BookmarkUiController by lazy { BookmarkUiController(object : BookmarkUiHost {
        override val guard get() = this@MainActivity.guard
        override val store get() = this@MainActivity.store
        override val webView get() = this@MainActivity.webView
        override val startPage get() = this@MainActivity.startPage
        override val listPanel get() = this@MainActivity.listPanel
        override fun promptText(title: String, initial: String, onCancel: (() -> Unit)?, goLabel: String, onDone: (String) -> Unit) = this@MainActivity.promptText(title,initial,onCancel,goLabel,onDone)
        override fun showMenu(title: String, items: List<MenuItem>, back: (() -> Unit)?) = this@MainActivity.showMenu(title,items,back)
        override fun hideList() = this@MainActivity.hideList()
        override fun openUrl(url: String) = this@MainActivity.openUrl(url)
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun exportBookmarks() = this@MainActivity.exportBookmarks()
        override fun importBookmarks() = this@MainActivity.importBookmarks()
        override fun runFileOperation(work: () -> BookmarkJobs.Result) = this@MainActivity.runFileOperation(work)
        override fun beginBookmarkList() { advanceInteraction();cancelPendingFullscreen();finishPendingTap(cancel=true);hideCursor() }
        override fun prepareBookmarkList() { menu.hide();menuBack=null;textPanel.hide() }
        override fun syncFocusBlocking() = this@MainActivity.syncFocusBlocking()
    }) }

    private val settings: BrowserSettings by lazy { BrowserSettings(object : SettingsHost {
        override val context get() = this@MainActivity
        override val prefs get() = this@MainActivity.prefs
        override val webView get() = this@MainActivity.webView
        override val startPage get() = this@MainActivity.startPage
        override val guard get() = this@MainActivity.guard
        override val lastPageUrl get() = this@MainActivity.lastPageUrl
        override val blockedCount get() = this@MainActivity.blockedCount
        override val handler get() = this@MainActivity.handler
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun reloadPage() = this@MainActivity.reloadPage()
        override fun recreate() = this@MainActivity.recreate()
        override fun applyUserAgent() = this@MainActivity.applyUserAgent()
        override fun showMenuAt(title: String, items: List<MenuItem>, focusIndex: Int, back: (() -> Unit)?) = this@MainActivity.showMenuAt(title,items,focusIndex,back)
        override fun showDiagnosticMenu() = this@MainActivity.showDiagnosticMenu()
        override fun showMainMenu() = this@MainActivity.showMainMenu()
        override fun ipv6ShieldState() = this@MainActivity.ipv6ShieldState()
        override fun toggleIpv6Shield() = this@MainActivity.toggleIpv6Shield()
    }) }

    private val cx get() = pointer.cx
    private val cy get() = pointer.cy
    private val lastTapAt get() = pointer.lastTapAt
    private val pointer: PointerController by lazy { PointerController(object : PointerHost {
        override val context get() = this@MainActivity
        override val root get() = this@MainActivity.root
        override val webView get() = this@MainActivity.webView
        override val customView get() = this@MainActivity.customView
        override val cursor get() = this@MainActivity.cursor
        override val ui get() = this@MainActivity.ui
        override val osd get() = this@MainActivity.osd
        override val handler get() = this@MainActivity.handler
        override val foreground get() = this@MainActivity.foreground
        override val onFlixerPage get() = this@MainActivity.onFlixerPage
        override val interactionSerial get() = this@MainActivity.interactionSerial
        override val playbackHeartbeatAt get() = this@MainActivity.playbackHeartbeatAt
        override val seekClickJs get() = this@MainActivity.seekClickJs
        override fun pageAvailable() = this@MainActivity.pageAvailable()
        override fun eval(script: String, done: ((String?) -> Unit)?) = this@MainActivity.eval(script,done)
        override fun postForPage(delayMs: Long, action: () -> Unit) = this@MainActivity.postForPage(delayMs,action)
        override fun hideIme() = this@MainActivity.hideIme()
        override fun focusFromPage(state: String) = this@MainActivity.focusFromPage(state)
        override fun seekToFraction(fraction: Float) = this@MainActivity.seekToFraction(fraction)
        override fun captureTapPermit(target: View): () -> Boolean {
            val interaction=interactionSerial
            val stamp=editorState.editorSession
            return { foreground && interaction==interactionSerial && stamp==editorState.editorSession && target === (customView ?: webView) && editorState.textMode==TextMode.NONE && !menu.shown() && !listPanel.shown() && !textPanel.shown() && !startPage.shown() }
        }
        override fun canRepairSeek(interaction: Long) = onFlixerPage && customView==null && interaction==interactionSerial && !keypad.shown() && !menu.shown() && !listPanel.shown() && !textPanel.shown()
        override fun clearPressedKeys() { selectPending=false;selectLongFired=false;backPending=false;backLongFired=false }
    },pageIdentity) }

    private val editorState: EditorStateView get() = editor
    private val editor: EditorController by lazy { EditorController(object : EditorHost {
        override val context get() = this@MainActivity
        override val handler get() = this@MainActivity.handler
        override val webView get() = this@MainActivity.webView
        override val keypad get() = this@MainActivity.keypad
        override val lastTapAt get() = this@MainActivity.lastTapAt
        override val lastUserInputAt get() = this@MainActivity.lastUserInputAt
        override val panelsOrStartShown get() = menu.shown() || listPanel.shown() || textPanel.shown() || startPage.shown()
        override val editorBlocked get() = customView != null || cssFullscreenActive || panelsOrStartShown
        override fun pageAvailable() = this@MainActivity.pageAvailable()
        override fun eval(script: String, done: ((String?) -> Unit)?) = this@MainActivity.eval(script, done)
        override fun runOnUiThread(action: () -> Unit) = this@MainActivity.runOnUiThread(action)
        override fun beginEditorInteraction() { finishPendingTap(cancel=true);this@MainActivity.advanceInteraction();cancelPlayerWork() }
        override fun advanceInteraction() { this@MainActivity.advanceInteraction() }
        override fun hideCursor() = this@MainActivity.hideCursor()
        override fun hideIme() = this@MainActivity.hideIme()
        override fun updateEditorViewport() = this@MainActivity.updateEditorViewport()
        override fun navigateInput(text: String) = openUrl(normalizeInput(text))
        override fun toastOnce(text: String) = this@MainActivity.toastOnce(text)
        override fun restoreEditorFocus() { if (menu.shown() || listPanel.shown() || textPanel.shown()) return;restoreFocus() }
    }, pageIdentity) }

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
    private val foregroundState = ForegroundState()
    private val foregroundView: ForegroundView = foregroundState
    private val foreground get() = foregroundView.foreground
    private var lastUserInputAt = 0L
    private var dimmed = false
    private var wakeKey: Int? = null
    private var wakeTouch = false
    private var scrollMode = false
    @Volatile private var playbackHeartbeatAt = 0L
    private var fmhyThemeJs = ""
    private var tvUiJs = ""
    private var seekClickJs = ""
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
        advanceInteraction()
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
            if (!wakeTouch) pointer.recordTap()
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

    private var activityDestroyed = false
    private var webViewDestroyed = false
    private var recoveringRenderer = false
    private val pageIdentity = PageIdentity()
    private val mediaOwnership = MediaOwnership(pageIdentity)
    private val mediaState: MediaOwnershipView = mediaOwnership
    private val document: DocumentIdentity = pageIdentity
    private val pageEpoch get() = document.epoch
    private val documentToken get() = document.token
    private var interactionSerial = 0L
    private val activityInstance = java.util.UUID.randomUUID().toString()
    private var cssFullscreenActive = false
    private val pendingEvaluations = HashMap<Long, Runnable>()
    private var evaluationId = 0L
    private val popupViews = HashSet<WebView>()
    private val popupTimeouts = HashMap<WebView, Runnable>()


    /** UI arbitration invalidates independent controller snapshots; no shared input/media serial. */
    private fun advanceInteraction() {
        interactionSerial++
        editor.interactionChanged()
        media.interactionChanged()
    }

    private fun pageAvailable(): Boolean = ::webView.isInitialized &&
        !webViewDestroyed && !activityDestroyed && !recoveringRenderer

    private fun postForPage(delayMs: Long, action: () -> Unit) {
        val epoch = pageEpoch
        handler.postDelayed({ if (pageAvailable() && foreground && epoch == pageEpoch) action() }, delayMs)
    }

    private fun cancelOsdRefresh() = media.cancelOsdRefresh()

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
        pendingSiteLink = null
        finishPendingTap(cancel = true, deliver = !rendererGone)
        webViewDestroyed = true
        pageIdentity.advance()
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
    private val darkMode get() = settings.darkMode
    private val desktopSite get() = settings.desktopSite
    private val autoplay get() = settings.autoplay
    private var confirmSiteExit = true

    // ---- low quality stream preference (the page scripts read this through the bridge, hence @Volatile)
    private val lowQuality get() = settings.lowQuality

    // ---- one video at a time (the page scripts read these through the bridge, hence @Volatile)
    private val exclusiveVideo get() = settings.exclusiveVideo

    private val mediaOwner get() = mediaState.owner

    // ---- browser state
    private var lastPageUrl = ""
    private var mainProcessConnection: ServiceConnection? = null
    private var pageStartedAt = 0L
    private lateinit var uaPolicy: UserAgentPolicy
    private var pendingClearHistory = false
    private var deferredNavigation: (() -> Unit)? = null
    private var crashReloadCount = 0
    // ---- site-anchor guard (docs/firetv-guard-plan.md, Revision 11) ----
    // anchorUrl is the single navigation authority: the URL of the last explicit native
    // action (address bar, bookmark, history, reload, restore/watchdog) or the directed
    // nippleflix.com -> ghostflix.st migration observed on an allowed page start. It is
    // NEVER sourced from WebView.url. The guard allows only HTTP(S) destinations on the
    // anchor host/subdomains (plus that one directed migration); everything else is
    // blocked at every native entry point (shouldOverrideUrlLoading, page start,
    // commit, finish). At most ONE automatic recovery per explicit navigation is
    // allowed (redirectRecoveries); a second violation returns to the start page.
    private var anchorUrl: String? = null
    private var redirectRecoveries = 0
    private data class PendingSiteLink(val view: WebView, val epoch: Long, val anchor: String, val target: String)
    private var pendingSiteLink: PendingSiteLink? = null
    private val softwareRendering get() = settings.softwareRendering
    private var scriptsAtDocumentStart = false
    private var uiScriptsAtDocumentStart = false
    private var guardJs = ""
    private var helpersJs = ""
    private var flixerImageGuardJs = ""
    private var flixerPosterInfoJs = ""
    private var flixerPosterGuardJs = ""
    private var scrollRestoreJs = ""
    private var heroChurnGuardJs = ""
    private var canvasBgGuardJs = ""
    private var videoLayerGuardJs = ""

    // TMDB image virtualization: allowlist of image URLs currently visible on a guarded
    // poster-grid host. Off-allowlist image.tmdb.org requests get a 1x1 transparent pixel,
    // keeping the GPU texture count low enough to avoid the PowerVR KEGLReleaseRenderTarget crash.
    private val imageFirewall by lazy { ImageFirewall(this, guard) { lowQuality } }
    @Volatile private var onFlixerPage = false
    // True when the active page is a guarded poster-grid host (the poster-grid site or the lazy-load poster site)
    // — i.e. the TMDB image firewall (allowlist + 1x1 backstop) is active.
    private val onImageGuardedPage get() = imageFirewall.active

    // Guarded poster-grid hosts (TMDB firewall active): both load large poster grids whose
    // GPU texture churn tips the PowerVR driver over.
    private fun isImageGuardedHost(host: String) = imageFirewall.isSupportedHost(host)

    // GPU render-reset: the Flixer crashes (KEGLReleaseRenderTarget) when loaded after a video
    // site — residual video GPU state tips the PowerVR driver over. Track the last non-Flixer host to force a reset.
    @Volatile private var lastNonFlixerHost = ""

    // GPU render-reset (general): residual video GPU state accumulates across video sites and
    // tips the PowerVR driver over (frame drops, black video with audio). Set on real playback; consumed by a reset on the next navigation away.
    private val videoActive get() = mediaState.videoActive

    // ---- remote pointer
    private var selectPending = false
    private var selectLongFired = false
    private var backPending = false
    private var backLongFired = false


    // ---- full-screen video
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // ---- keypad
    private var menuBack: (() -> Unit)? = null

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

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exclude the system IME at the window, not by disabling Chromium's editor.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

        CrashLog.install(this)   // first, so even a failure while building the screen is recorded
        AmazonVideoComposition.prepare()
        DiagnosticLog.initialize(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.loadGeneral()
        Ipv6Preference.apply(this, ipv6ShieldState())
        confirmSiteExit = prefs.getBoolean("confirmSiteExit", true)
        crashReloadCount = prefs.getInt("crashReloadCount", 0)
        // A clean exit means the previous session ended normally, not a crash: reset the
        // crash-retry budget so a normal launch never reopens lastCrashUrl. Read before the line below clears it.
        if (prefs.getBoolean("cleanExit", false)) {
            crashReloadCount = 0
            prefs.edit().putInt("crashReloadCount", 0).commit()
        }
        // Read the watchdog state before the crash window is cleared: a restart the watchdog
        // caused means the previous process died in a native crash nothing here could catch.
        val watchdogRestart = prefs.getBoolean("watchdogRestart", false)
        val watchdogUrl = prefs.getString("lastCrashUrl", "") ?: ""
        val crashWindow = prefs.getLong("crashWindowStartedAt", 0L)
        if (crashWindow <= 0L || CrashRecoveryPolicy.expired(crashWindow, System.currentTimeMillis())) {
            clearCrashRecovery()
        }
        // cleanExit stays false unless the activity ends normally, so a process death that
        // leaves it false was a crash. wasForeground tells the watchdog not to reopen an app killed while backgrounded.
        prefs.edit().putBoolean("cleanExit", false).putBoolean("wasForeground", true).commit()
        startWatchdog()
        settings.loadRendering()

        // Bookmark export writes to public Downloads; on API 23+ that needs the runtime
        // WRITE_EXTERNAL_STORAGE permission, requested up front so export just works.
        requestStoragePermission()

        ui = Ui(this)
        guard = PopupGuard(this)
        store = sharedBookmarks(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        guardJs = readAsset(this, "popup_guard.js")
        helpersJs = readAsset(this, "page_helpers.js")
        flixerImageGuardJs = readAsset(this, "flixer_image_guard.js")
        flixerPosterInfoJs = readAsset(this, "flixer_poster_info.js")
        flixerPosterGuardJs = readAsset(this, "flixer_poster_guard.js")
        scrollRestoreJs = readAsset(this, "scroll_restore.js")
        heroChurnGuardJs = readAsset(this, "hero_churn_guard.js")
        canvasBgGuardJs = readAsset(this, "canvas_bg_guard.js")
        videoLayerGuardJs = readAsset(this, "video_layer_guard.js")
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
            // glBindFramebuffer) that hits certain pages (the poster-grid site) on this Fire TV.
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
        pointer.position(ui.screenW / 2f, ui.screenH / 2f)
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
                pointer.position(cx.coerceIn(0f, (w - 1).toFloat()),cy.coerceIn(0f, (h - 1).toFloat()))
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
        advanceInteraction()
        foregroundState.pause()
        mediaOwnership.setForeground(false)
        media.setForeground(false)
        editor.setForeground(false)
        selectPending = false
        backPending = false
        backLongFired = false
        wakeKey = null
        swallowedUp.clear()
        cancelPlayerWork()
        editor.invalidate()
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
                // No payload is used; allow framework/shadow empty binding notifications.
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
                override fun onServiceDisconnected(name: ComponentName?) {}
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
        foregroundState.resume()
        mediaOwnership.setForeground(true)
        media.setForeground(true)
        editor.setForeground(true)
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
        foregroundState.pause()
        mediaOwnership.setForeground(false)
        media.setForeground(false)
        editor.setForeground(false)
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

    // WebView setup

    // General browser: scripting is required; file access is disabled and permission requests are denied.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // Autoplay without a click is ON by default (user request). Protection against a hidden
        // preview/ad stealing the single video decoder is the exclusive-video subsystem (page_helpers.js,
        // default ON); the gesture flag is an optional hardening via Settings > Autoplay without a click.
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

        uaPolicy = UserAgentPolicy(s)
        uaPolicy.initialize(s.userAgentString)
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
                // Hero-carousel churn guard FIRST: it wraps window.setInterval before any other script
                // runs, so it catches the hero carousel's 8000ms timer (the app's own in-range timers
                // are tagged by name and pass through). Mitigation for the PowerVR hero-carousel
                // texture-churn crash, not a root-cause fix.
                WebViewCompat.addDocumentStartJavaScript(webView, heroChurnGuardJs, origins)
                WebViewCompat.addDocumentStartJavaScript(webView, guardJs, origins)
                WebViewCompat.addDocumentStartJavaScript(webView, helpersJs, origins)
                // Flixer TMDB image firewall — the fix for the Flixer PowerVR crash. Must run at
                // DOCUMENT START, before the site's own scripts append the ~180-poster burst:
                // injecting from onPageCommitVisible ran too late (sync saw count:0, allowlist
                // stayed empty, the full 182-image burst hit the GPU → SIGSEGV in
                // KEGLReleaseRenderTarget). At document start it catches the burst as it happens:
                // visible posters get real textures, off-screen ones get 1x1 pixels from
                // shouldInterceptRequest. Self-guarding and idempotent, so all origins is safe.
                WebViewCompat.addDocumentStartJavaScript(webView, flixerImageGuardJs, origins)
                // Flixer poster -> info page: on the TV a poster click is a silent no-op in the
                // site's desktop path and the hover popup is too short-lived to navigate. When
                // the user activates a poster card, ensure the popup is up and click its "i"
                // (More Info) button — the site's own control that opens the show info view
                // (season/episode selectable). Deliberately simple: no screen-size spoofing,
                // no layout involvement. Self-gated to flixer.gd/flixer.su, idempotent.
                WebViewCompat.addDocumentStartJavaScript(webView, flixerPosterInfoJs, origins)
                // Scroll restore: some SPA portals scrollTo(0,0) when hydration completes, yanking the
                // user back to the top. Document start (all origins) so it captures the native scrollTo
                // before any page script. Self-guarding and idempotent, safe on every page.
                WebViewCompat.addDocumentStartJavaScript(webView, scrollRestoreJs, origins)
                // Canvas background guard: mitigation for the PowerVR crash on pages that mount a
                // full-viewport animated <canvas> background (cinejoy /search's .liquid-bg —
                // SIGSEGV in KEGLReleaseRenderTarget). Top frame only; kills the decorative
                // background (display:none + 1x1 canvas collapse) as it mounts, with a 1500ms
                // backstop poll. Self-guarding and idempotent, safe on every page.
                WebViewCompat.addDocumentStartJavaScript(webView, canvasBgGuardJs, origins)
                // Video layer guard: black-video (audio-only) fix for movy.sx. Promotes the
                // <video> element to its own compositor layer (will-change:transform) so the
                // PowerVR GPU keeps its raster alive under sustained load. Top frame only;
                // host-scoped to movy.sx; idempotent, safe on every page.
                WebViewCompat.addDocumentStartJavaScript(webView, videoLayerGuardJs, origins)
                scriptsAtDocumentStart = true
                // An optional UI/theme failure must never prevent the poster-grid site's intro fix.
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
            try { view.evaluateJavascript(heroChurnGuardJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(guardJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(helpersJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(flixerPosterInfoJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(scrollRestoreJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(canvasBgGuardJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(videoLayerGuardJs, null) } catch (_: Throwable) { }
        }
        if (!uiScriptsAtDocumentStart) {
            try { view.evaluateJavascript(fmhyThemeJs, null) } catch (_: Throwable) { }
            try { view.evaluateJavascript(tvUiJs, null) } catch (_: Throwable) { }
        }
    }

    /**
     * Dark mode is handled entirely by Chromium render-client darkening (FORCE_DARK_ON in
     * applyDarkMode). No CSS injection — injected style tags trigger PowerVR GPU compositing
     * crashes on the Flixer. The SPA portal (VitePress) declares color-scheme, so it stays light.
     */
    private fun injectDarkMode(view: WebView?) {
        if (view == null) return
        // NO CSS injection on any page: every attempt to force the SPA portal dark crashed the
        // Flixer's PowerVR GPU (SIGSEGV in KEGLReleaseRenderTarget). Do NOT add style injection here.
    }

    private fun applyUserAgent() {
        uaPolicy.apply(desktopSite)
    }

    /**
     * Single main-frame interception path (docs/firetv-guard-plan.md, Revision 11).
     * Authority is the native anchor, never the WebView's already-changed URL.
     * Fail-closed: a main-frame target the guard denies is consumed, logged, counted
     * toward the block tally, and — for a genuine user gesture — optionally captured
     * for the deliberate Menu > Review flow. Subframes keep full HTTP(S)/local support.
     */
    private fun interceptNavigation(view: WebView?, uri: Uri, mainFrame: Boolean, reviewable: Boolean = false): Boolean {
        if (view !== webView || !pageAvailable() || navigationPending() || startPage.shown()) return true
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (mainFrame && guard.blocksNavigation(anchorUrl, uri)) {
            DiagnosticLog.record(this, "redirect_blocked", JSONObject().apply {
                put("mechanism", "native")
                put("initiator", DiagnosticLog.safeUrl(anchorUrl))
                put("target", DiagnosticLog.safeUrl(uri.toString()))
            })
            noteBlocked("a redirect")
            // No automatic panel: capture at most one reviewable gesture request per
            // document; the user must deliberately open Menu > Review blocked link.
            if (reviewable && pendingSiteLink == null && foreground &&
                (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
                val anchor = anchorUrl
                if (anchor != null) {
                    pendingSiteLink = PendingSiteLink(webView, pageEpoch, anchor, uri.toString())
                    toast("Cross-site link blocked — Menu to review")
                }
            }
            return true
        }
        // Preserve subframe HTTP(S)/local/blob player support and existing custom-app denial.
        return when (scheme) {
            "http", "https", "about", "data", "blob", "javascript", "file" -> false
            else -> { if (guard.enabled) noteBlocked("an app link"); true }
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            // isRedirect is API24+; Kotlin short-circuits it on older devices. Only a
            // genuine user gesture on a non-redirect main-frame request is reviewable.
            val reviewable = Build.VERSION.SDK_INT >= 24 && req.isForMainFrame && req.hasGesture() && !req.isRedirect
            return interceptNavigation(view, req.url, req.isForMainFrame, reviewable)
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
            if (url == null) return
            val target = Uri.parse(url)
            if (startPage.shown() && url.equals("about:blank", ignoreCase = true)) return
            // First load of the session: there is no prior site to escape from, so adopt the
            // target as the anchor (mirrors navigateUrl seeding). Non-HTTP(S) targets stay
            // fail-closed in the guard below.
            val scheme = target.scheme?.lowercase(Locale.ROOT)
            if (anchorUrl == null && (scheme == "http" || scheme == "https")) anchorUrl = url
            if (guard.blocksNavigation(anchorUrl, target)) {
                try { view?.stopLoading() } catch (_: Exception) { }
                pendingSiteLink = null
                DiagnosticLog.record(this@MainActivity, "redirect_blocked", JSONObject().apply {
                    put("mechanism", "page_start")
                    put("anchor", DiagnosticLog.safeUrl(anchorUrl))
                    put("target", DiagnosticLog.safeUrl(url))
                })
                noteBlocked("a redirect")
                // One automatic recovery per explicit navigation; never widen the anchor.
                val recovery = lastPageUrl.takeIf { it.isNotBlank() && !guard.blocksNavigation(anchorUrl, Uri.parse(it)) }
                    ?: anchorUrl.orEmpty()
                if (redirectRecoveries == 0 && hostOf(recovery).isNotEmpty()) {
                    redirectRecoveries = 1
                    navigateUrl(recovery, false, updateAnchor = false)
                } else showStartPage(false)
                return
            }
            pendingSiteLink = null
            // Directed content migration: advance the anchor exactly once, HTTPS only.
            val sourceHost = try { PopupGuard.cleanHost(Uri.parse(anchorUrl.orEmpty()).host.orEmpty()) } catch (_: Exception) { "" }
            val targetScheme = target.scheme?.lowercase(Locale.ROOT)
            if (PopupGuard.isBrandHost(sourceHost, "nippleflix") && PopupGuard.isBrandHost(PopupGuard.cleanHost(target.host.orEmpty()), "ghostflix") && targetScheme == "https")
                anchorUrl = url
            cachedPageUrl = url
            finishPendingTap(cancel = true)
            pageIdentity.advance()
            resetDocumentWork()
            leaveFullscreen(true)
            osd.hideNow()
            val cancelledWebPrompt = cancelPendingJsPrompt()
            if (editorState.textMode == TextMode.PAGE || cancelledWebPrompt) closeKeypad()
            pointer.resetTap()
            pointer.resetScroll()
            selectPending = false
            backPending = false
            backLongFired = false
            editor.clearField()
            playbackHeartbeatAt = 0L
            idleQueryPending = false
            scrollMode = false



            cancelOsdRefresh()
            editor.stopSync()
            lastPageUrl = url ?: ""
            // A native GPU crash kills the process before any handler runs, so the page has to
            // be recorded here for the watchdog restart to reopen it.
            if (url != null && url.startsWith("http")) prefs.edit().putString("lastCrashUrl", url).commit()
            pageStartedAt = SystemClock.elapsedRealtime()
            DiagnosticLog.record(this@MainActivity, "page_started", JSONObject().apply {
                put("url", DiagnosticLog.safeUrl(url))
                put("host", try { Uri.parse(url ?: "").host ?: "" } catch (_: Throwable) { "" })
            })
            injectFallback(view)
            cursor.loading = true
            if (!startPage.shown()) {
                progress.fraction = 0.05f
                progress.visibility = View.VISIBLE
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (!pageAvailable() || navigationPending() || (view != null && view !== webView)) return
            if (startPage.shown() || url == null || guard.blocksNavigation(anchorUrl, Uri.parse(url))) return
            DiagnosticLog.record(this@MainActivity, "page_commit_visible", JSONObject().put("url", DiagnosticLog.safeUrl(url)))
            injectFallback(view)
            injectDarkMode(view)
            try { view?.evaluateJavascript(fmhyThemeJs, null) } catch (_: Throwable) {}
            // Detect guarded poster-grid page: activate TMDB image firewall.
            val host = try { Uri.parse(url ?: "").host?.lowercase(Locale.US)?.removeSuffix(".") ?: "" } catch (_: Throwable) { "" }
            val isFlixer = PopupGuard.isBrandHost(host, "flixer")
            val isImageGuarded = isImageGuardedHost(host)
            imageFirewall.onCommittedHost(host)
            if (isFlixer != onFlixerPage) {
                onFlixerPage = isFlixer
                DiagnosticLog.record(this@MainActivity, "flixer_image_firewall", JSONObject().apply {
                    put("active", isFlixer)
                    put("allowlistSize", imageFirewall.allowlistSize)
                })
                // GPU render reset (see lastNonFlixerHost): Flixer loaded after a video site.
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
            // Track the last non-poster-grid-site host for the render-reset trigger.
            if (!isFlixer && host.isNotEmpty()) {
                lastNonFlixerHost = host
            }
            // Inject the image guard JS (idempotent — safe to call repeatedly).
            if (isImageGuarded) {
                try { view?.evaluateJavascript(flixerImageGuardJs, null) } catch (_: Throwable) { }
            }
            // Inject the poster hover-preview guard (Flixer only): keeps the DPAD-focus-triggered
            // floating preview hidden so the grid is navigable; posters still open on click.
            if (isFlixer) {
                try { view?.evaluateJavascript(flixerPosterGuardJs, null) } catch (_: Throwable) { }
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
            if (request != null) {
                val url = request.url
                // Which host actually failed (e.g. SSL net_error -202 shows up here as a failed
                // request): tells us whether it is the video CDN or only ad/tracker domains.
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

        override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): android.webkit.WebResourceResponse? =
            AdNetworkBlocker.intercept(this@MainActivity, r) ?: imageFirewall.intercept(r)

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!pageAvailable() || navigationPending() || (view != null && view !== webView)) return
            if (startPage.shown() || url == null || guard.blocksNavigation(anchorUrl, Uri.parse(url))) return
            cachedPageUrl = url
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
            editor.abandonPrompt() // The dead renderer owns this result; never call back into it.
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

        private fun handleNativeDialog(url: String?, kind: String, result: android.webkit.JsResult?, confirm: Boolean): Boolean {
            if (!guard.enabled || result == null) return false
            if (confirm) result.confirm() else result.cancel()
            DiagnosticLog.record(this@MainActivity, "js_dialog_blocked", JSONObject().apply {
                put("url", DiagnosticLog.safeUrl(url))
                put("kind", kind)
                put("completion", if (confirm) "confirm" else "cancel")
            })
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
            handleNativeDialog(url, "alert", result, confirm = true)

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
            handleNativeDialog(url, "confirm", result, confirm = false)

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (!pageAvailable() || startPage.shown() || navigationPending() || (view != null && view !== webView)) return
            progress.fraction = newProgress / 100f
            if (newProgress >= 100) cursor.loading = false
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
            if (result == null) return false
            return editor.showJsPrompt(message, defaultValue, result)
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
    inner class Bridge : BrowserBridgeContract {
        @JavascriptInterface
        override fun isDiagnosticsEnabled(): Boolean = DiagnosticLog.isEnabled(this@MainActivity)

        @JavascriptInterface
        override fun diagnosticEvent(event: String?, details: String?) {
            val e = event?.trim().orEmpty()
            if (e.isEmpty()) return
            DiagnosticLog.record(this@MainActivity, e, details)
        }

        @JavascriptInterface
        override fun isGuardEnabled(): Boolean = guard.enabled

        @JavascriptInterface
        override fun isDarkMode(): Boolean = darkMode

        @JavascriptInterface
        override fun pageIsForeground(): Boolean = foreground

        /** Page-level (CSS) fullscreen: the page reports enter/exit so remote keys route to it. */
        @JavascriptInterface
        override fun cssFullscreen(active: Boolean) {
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch != pageEpoch || !pageAvailable()) return@runOnUiThread
                if (cssFullscreenActive == active) return@runOnUiThread
                cssFullscreenActive = active
                DiagnosticLog.record(this@MainActivity, if (active) "css_fullscreen_on" else "css_fullscreen_off")
                if (active) {
                    hideCursor()
                    // The playback heartbeat may not be fresh yet (2 s cadence); retry the pointer
                    // lift once it can be, so the parked cursor cannot pin player controls visible.
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
        override fun playbackActive(token: String?) {
            val epoch = pageEpoch
            if (token != documentToken) return
            heartbeatUi.offer(Heartbeat(epoch, token))
        }

        @JavascriptInterface
        override fun activeInputFrame(): String = editorState.inputFrame

        @JavascriptInterface
        override fun currentDocument(): String = documentToken

        /** Top-level page host+path as the dead-video-retry cooldown key; "unknown" if the URL is unavailable. */
        private fun retryCooldownKey(): String {
            // Read the main-thread-cached URL (WebView.getUrl() is not safe off the UI thread).
            val url = cachedPageUrl ?: return "unknown" // fallback key (still applies the cooldown)
            return try {
                val uri = android.net.Uri.parse(url)
                (uri.host ?: "") + (uri.path ?: "")
            } catch (t: Throwable) {
                url
            }
        }

        /**
         * Dead-video-retry cooldown gate (page_helpers.js, before reloading a dead player iframe).
         * True if a retry is allowed (and recorded); false if one happened within the cooldown window.
         */
        @JavascriptInterface
        override fun canRetryDeadVideo(): Boolean {
            if (!pageAvailable()) {
                DiagnosticLog.record(this@MainActivity, "dead_retry_blocked", "pageAvailable=false")
                return true
            }
            return try {
                val key = retryCooldownKey()
                val now = SystemClock.elapsedRealtime()
                synchronized(deadVideoRetryLock) {
                    val last = deadVideoRetryState[key]
                    val blocked = last != null && now - last < DEAD_VIDEO_RETRY_COOLDOWN_MS
                    DiagnosticLog.record(this@MainActivity, "dead_retry_check",
                        "key=$key lastAgoMs=${if (last != null) (now - last).toString() else "none"} blocked=$blocked")
                    if (blocked) {
                        return false
                    }
                    deadVideoRetryState[key] = now
                    if (deadVideoRetryState.size > DEAD_VIDEO_RETRY_MAX_ENTRIES) {
                        val oldest = deadVideoRetryState.entries.minByOrNull { it.value }?.key
                        oldest?.let { deadVideoRetryState.remove(it) }
                    }
                    true
                }
            } catch (t: Throwable) {
                DiagnosticLog.record(this@MainActivity, "dead_retry_error", t.toString())
                true
            }
        }

        @JavascriptInterface
        override fun editableFocused(frame: String?, state: String?) = editor.editableFocused(frame, state)
        @JavascriptInterface
        override fun editableBlurred(frame: String?, token: String?, field: String?) = editor.editableBlurred(frame, token, field)

        @JavascriptInterface
        override fun blocked(what: String?) {
            val w = (what ?: "a popup").take(160)
            blockedUi.offer(BlockedBatch(pageEpoch, w))
        }

        @JavascriptInterface
        override fun editableClicked() {
            val epoch = pageEpoch
            DiagnosticLog.record(this@MainActivity, "editable_clicked")
            runOnUiThread { if (epoch == pageEpoch && pageAvailable() && foreground) onEditableClicked() }
        }

        @JavascriptInterface
        override fun editableClickedForDocument(token: String?) {
            val epoch = pageEpoch
            if (token != documentToken) return
            runOnUiThread { if (epoch == pageEpoch && token == documentToken && foreground && pageAvailable()) onEditableClicked() }
        }

        /** Settings > One video at a time. */
        @JavascriptInterface
        override fun exclusiveEnabled(): Boolean = exclusiveVideo

        /** Settings > Prefer low quality streams. */
        @JavascriptInterface
        override fun lowQualityEnabled(): Boolean = lowQuality

        /** A frame started a real player; every other frame stops its own media. */
        @JavascriptInterface
        override fun mediaStarted(id: String?) {
            if (id == null || !id.startsWith(documentToken + ":")) return
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch == pageEpoch && foreground && pageAvailable() && id.startsWith(documentToken + ":")) {
                    mediaOwnership.started(id)
                    DiagnosticLog.record(this@MainActivity, "media_owner_started", JSONObject().put("frame", id.take(120)))
                }
            }
        }

        @JavascriptInterface
        override fun mediaStopped(id: String?) {
            if (id == null || !id.startsWith(documentToken + ":")) return
            val epoch = pageEpoch
            runOnUiThread {
                if (epoch == pageEpoch && pageAvailable() && id.startsWith(documentToken + ":")) {
                    mediaOwnership.stopped(id)
                    DiagnosticLog.record(this@MainActivity, "media_owner_stopped", JSONObject().put("frame", id.take(120)))
                }
            }
        }

        @JavascriptInterface
        override fun activeOwner(): String = mediaOwner

        /** the poster-grid site image virtualization: JS syncs the allowlist of visible TMDB image URLs. */
        @JavascriptInterface
        override fun tmdbAllowlist(urls: String?) {
            imageFirewall.updateAllowlist(urls)
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

    // Navigation

    private fun openUrl(url: String) = navigateUrl(url, userInitiated = true)

    /**
     * Every native navigation flows through here (address bar, bookmarks, history, reload,
     * restore/watchdog, and the one automatic recovery). `updateAnchor=false` is reserved
     * for the recovery path: it reloads a destination already authorized by the anchor
     * without widening the authority.
     */
    private fun navigateUrl(url: String, userInitiated: Boolean, updateAnchor: Boolean = true) {
        if (!pageAvailable()) return
        // Explicit user navigation is a fresh attempt; automatic retries/redirects are not.
        if (userInitiated && crashReloadCount > 0) clearCrashRecovery()
        // Non-HTTP(S), empty, or malformed destination: refuse natively, never load it.
        if (hostOf(url).isEmpty()) { showStartPage(false); return }
        if (updateAnchor) anchorUrl = url
        // Preserve intended URL for recovery, even if this process dies before page start.
        lastPageUrl = url
        pendingSiteLink = null
        if (userInitiated) redirectRecoveries = 0
        prepareNavigation()
        DiagnosticLog.record(this, if (userInitiated) "user_navigation" else "automatic_navigation", JSONObject().put("url", DiagnosticLog.safeUrl(url)))
        if (startPage.shown()) {
            startPage.hide()
            pendingClearHistory = true
        }
        progress.fraction = 0.05f
        progress.visibility = View.VISIBLE
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
        pageIdentity.advance()
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
        if (destination == null || hostOf(destination).isEmpty()) { showStartPage(false); return }
        // History entries are native-authorized destinations: adopt as the anchor.
        anchorUrl = destination
        redirectRecoveries = 0
        pendingSiteLink = null
        // History may be pushState/hash-only: the existing page and its bridge remain alive.
        // Full-document navigation rotates the token in onPageStarted instead.
        prepareNavigation(rotateDocument = false)
        lastPageUrl = destination
        handoffNavigation(releaseMedia = false) { webView.goBackOrForward(offset); webView.requestFocus(); showCursor() }
    }

    // ---- confirm-exit-site guard: a back press that would LEAVE the current site (no
    // in-site history, or the back entry is a different host) shows a confirm dialog instead
    // of bailing to the start page. In-site back is untouched. Toggle: Menu > "Confirm site exit".
    private fun backWouldExitSite(): Boolean {
        val currentUrl = webView.url ?: lastPageUrl
        val currentHost = hostOf(currentUrl)
        if (currentHost.isEmpty()) return false          // about:blank / non-http => no site to protect
        if (!webView.canGoBack()) return true            // back would go to start page
        val history = webView.copyBackForwardList()
        val backUrl = history.getItemAtIndex(history.currentIndex - 1)?.url ?: return true
        return hostOf(backUrl) != currentHost            // different host = exiting the site
    }

    private fun confirmExitSite() {
        val currentUrl = webView.url ?: lastPageUrl
        val host = hostOf(currentUrl)
        if (host.isEmpty()) { if (webView.canGoBack()) navigateHistory(-1) else showStartPage(false); return }
        showMenu("Exit $host?", listOf(
            MenuItem("Yes") { if (webView.canGoBack()) navigateHistory(-1) else showStartPage(false) },
            // Re-read the URL at action time: streaming sites redirect while the
            // dialog is open, so the captured currentUrl may be stale. The root must
            // come from a guard-validated URL, never the WebView's possibly-poisoned one.
            MenuItem("No") {
                val source = lastPageUrl.takeIf { it.isNotBlank() && !guard.blocksNavigation(anchorUrl, Uri.parse(it)) } ?: anchorUrl.orEmpty()
                val root = siteRootOf(source)
                if (root.isNotEmpty()) openUrl(root)
            }
        ), back = { })   // back on dialog = cancel, stay put
    }

    private fun hostOf(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun siteRootOf(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return ""
            val port = if (uri.port == -1) "" else ":" + uri.port
            "$scheme://$host$port/"
        } catch (_: Throwable) { "" }
    }

    /** An info page is a query-string URL on the site root path (e.g. ?tv=...&id=...). */
    private fun isInfoPage(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            val path = uri.path ?: ""
            (path.isEmpty() || path == "/") && !uri.query.isNullOrEmpty()
        } catch (_: Throwable) { false }
    }

    private fun reloadPage() {
        if (!pageAvailable() || startPage.shown()) return
        // Reload the last guard-validated page, never the WebView's possibly-poisoned URL.
        val destination = lastPageUrl.takeIf { it.isNotBlank() && !guard.blocksNavigation(anchorUrl, Uri.parse(it)) }
            ?: anchorUrl.orEmpty()
        if (hostOf(destination).isEmpty()) { showStartPage(false); return }
        anchorUrl = destination
        redirectRecoveries = 0
        pendingSiteLink = null
        clearCrashRecovery()
        prepareNavigation()
        lastPageUrl = destination
        progress.fraction = 0.05f
        progress.visibility = View.VISIBLE
        handoffNavigation {
            if (webView.url == destination) webView.reload() else webView.loadUrl(destination)
            webView.requestFocus()
            showCursor()
        }
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
                // GPU render reset (general, see videoActive): covers video-site -> video-site/other,
                // where residual state accumulates and degrades the next video (frame drops, black surface).
                if (videoActive) {
                    mediaOwnership.markInactive()
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
        pendingSiteLink = null
        anchorUrl = null
        lastBackAt = 0L
        pageIdentity.advance()
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

    // Key routing

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
                        val backFromUrl = webView.url ?: lastPageUrl
                        if (isInfoPage(backFromUrl)) {
                            // Info page: back goes to the site's main page, not history.
                            val root = siteRootOf(backFromUrl)
                            if (root.isNotEmpty()) openUrl(root) else showStartPage(false)
                        } else if (confirmSiteExit && backWouldExitSite()) confirmExitSite() else if (webView.canGoBack()) navigateHistory(-1) else showStartPage(false)
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
        val moving = bookmarkUi.movingBookmarkUrl
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
                    media.unmute()
                }
                return true
            }
        }
        return false
    }

    private fun moveCursor(code: Int, repeat: Int) = pointer.moveCursor(code,repeat)
    private fun scrollPage(dx: Int,dy: Int) = pointer.scrollPage(dx,dy)
    private fun showCursor() = pointer.showCursor()
    private fun liftPointerIfPlaying() = pointer.liftPointerIfPlaying()
    private fun hideCursor() = pointer.hideCursor()
    private fun tapAtCursor() = pointer.tapAtCursor()
    private fun repairSeekClick(x: Float,y: Float,interaction: Long=interactionSerial) = pointer.repairSeekClick(x,y,interaction)
    private fun sendTap(x: Float,y: Float,mouse: Boolean=false) = pointer.sendTap(x,y,mouse)
    private fun finishPendingTap(cancel: Boolean,deliver: Boolean=true) = pointer.finishPendingTap(cancel,deliver)

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
        val height = if (editorState.textMode == TextMode.PAGE && keypad.shown()) {
            val total = if (root.height > 0) root.height else ui.screenH
            val keyLp = keypad.layoutParams as FrameLayout.LayoutParams
            (total - keypad.measuredHeight - keyLp.bottomMargin - 8).coerceAtLeast(80)
        } else MATCH
        if (lp.height == height) return
        lp.height = height
        webView.layoutParams = lp
        if (editorState.textMode == TextMode.PAGE) postForPage(30L) {
            if (editorState.textMode == TextMode.PAGE) eval("window.__fb && __fb.focusIntoView()")
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

    // Video: play/pause, seek, volume, full screen

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

    private fun cancelPendingFullscreen() = media.cancelPendingFullscreen()
    private fun cancelPlayerWork() = media.cancelPlayerWork()

    private fun resetDocumentWork(rotateDocument: Boolean = true) {
        pointer.resetScroll()
        deferredNavigation = null
        cancelPageEdits()
        editor.resetPermit()
        navigationHandoff.cancel()
        heartbeatUi.cancel()
        blockedUi.cancel()
        advanceInteraction()
        cancelPlayerWork()
        editor.invalidate()
        if (rotateDocument) pageIdentity.rotateDocument()
        cssFullscreenActive = false
        mediaOwnership.reset()
        playbackHeartbeatAt = 0L
        pendingEvaluations.values.toList().forEach { handler.removeCallbacks(it) }
        pendingEvaluations.clear()

        idleQueryPending = false
    }

    private fun videoCommand(call: String, fallbackKey: Int, tapFrame: Boolean = false) = media.videoCommand(call, fallbackKey, tapFrame)
    private fun videoSeek(seconds: Int) = media.videoSeek(seconds)
    private fun seekFromKey(event: KeyEvent, direction: Int) = media.seekFromKey(event, direction)
    private fun seekToFraction(fraction: Float) = media.seekToFraction(fraction)
    private fun adjustVolume(dir: Int) = media.adjustVolume(dir)
    private fun refreshOsd(delayMs: Long, hint: String) = media.refreshOsd(delayMs, hint)
    private fun fullscreenVideo() = media.fullscreenVideo()
    private fun tapPlayerFrame(mouse: Boolean = onFlixerPage) = media.tapPlayerFrame(mouse)

    private fun enterFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        advanceInteraction()
        if (view == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        cancelPlayerWork()
        finishPendingTap(cancel = true)
        if (editorState.textMode == TextMode.PAGE) closeKeypad()
        customView = view
        customCallback = callback
        // Defensive: if the view still has a parent (stale customView from renderer recovery,
        // or a page re-requesting fullscreen), remove it first — otherwise addView throws on the main thread.
        try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
        // Center the video in the box: the fullscreen VideoView measures to the video's own aspect
        // ratio (a 2.39:1 film is ~1920x780 here), and FrameLayout's top-left gravity would pin it to the top.
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
        advanceInteraction()
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
                    media.unmute()
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

    private fun cancelPendingJsPrompt() = editor.cancelPendingJsPrompt()
    private fun promptText(title: String, initial: String, onCancel: (() -> Unit)?, goLabel: String = "OK", onDone: (String) -> Unit) = editor.promptText(title, initial, onCancel, goLabel, onDone)
    private fun promptUrl() = editor.promptUrl()
    private fun openPageKeypad() = editor.openPageKeypad()
    private fun openKeypadSmart() = editor.openKeypadSmart()
    private fun onEditableClicked() = editor.onEditableClicked()
    private fun focusFromPage(state: String?) = editor.focusFromPage(state)
    private fun cancelPageEdits() = editor.cancelPageEdits()
    private fun typeText(s: String) = editor.typeText(s)
    private fun typeBackspace() = editor.typeBackspace()
    private fun cancelKeypad() = editor.cancelKeypad()
    private fun typeClear() = editor.typeClear()
    private fun typeGo() = editor.typeGo()
    private fun closeKeypad() = editor.closeKeypad()
    private fun sendKeyToPage(keyCode: Int) = pageKeys.sendKeyToPage(keyCode)
    private fun syncFieldSoon() = editor.syncFieldSoon()
    private fun syncField() = editor.syncField()
    private fun showBufferPreview() = editor.showBufferPreview()

    // Menus, bookmarks

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
        advanceInteraction()
        cancelPendingFullscreen()
        cancelPendingJsPrompt()
        finishPendingTap(cancel = true)
        menu.hide()
        listPanel.hide()
        textPanel.hide()
        menuBack = null
        bookmarkUi.resetSelection()
        editor.dismissForPanels()
        updateEditorViewport()
        syncFocusBlocking()
    }

    private fun showMenu(title: String, items: List<MenuItem>, back: (() -> Unit)?) {
        showMenuAt(title, items, 0, back)
    }

    private fun showMenuAt(title: String, items: List<MenuItem>, focusIndex: Int, back: (() -> Unit)?) {
        advanceInteraction()
        cancelPendingFullscreen()
        finishPendingTap(cancel = true)
        hideCursor()
        listPanel.hide()
        bookmarkUi.stopMoving()
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
        bookmarkUi.resetSelection()
        syncFocusBlocking()
        startPage.refresh()
        restoreFocus()
    }

    private fun listBack() {
        if (bookmarkUi.bookmarksFolder != null && store.hasFolders()) showBookmarks(null, 0) else hideList()
    }

    /** A pending review link is usable only while its document, anchor, and activity state are unchanged. */
    private fun validSiteLink(link: PendingSiteLink): Boolean = pendingSiteLink === link && pageAvailable() &&
        foreground && !isFinishing && !isDestroyed &&
        !startPage.shown() && !navigationPending() && link.view === webView &&
        link.epoch == pageEpoch && link.anchor == anchorUrl

    private fun reviewSiteLink(link: PendingSiteLink) {
        if (!validSiteLink(link)) { pendingSiteLink = null; return }
        val host = try { Uri.parse(link.target).host.orEmpty() } catch (_: Throwable) { "" }
        showMenu("Leave this site?", listOf(
            MenuItem("Stay on this site") { pendingSiteLink = null },
            MenuItem(if (host.isNotEmpty()) "Open $host" else "Open link") {
                val valid = validSiteLink(link)
                pendingSiteLink = null
                if (valid) openUrl(link.target)
            }
        ), back = { pendingSiteLink = null })
    }

    private fun showMainMenu() {
        val onStart = startPage.shown()
        val url: String? = if (onStart) null else webView.url
        val items = ArrayList<MenuItem>()
        // Deliberate review of a blocked cross-site gesture (Revision 11): at most one
        // pending link per document; "Stay" is the default. No automatic popup. A stale
        // link is cleared here; its menu entry becomes a no-op (reviewSiteLink re-validates).
        val review = pendingSiteLink
        if (review != null) {
            if (!validSiteLink(review)) pendingSiteLink = null
            items.add(MenuItem("Review blocked link") { reviewSiteLink(review) })
        }
        items.add(MenuItem("Scroll submenu/page with D-pad") {
            scrollMode = true
            showCursor()
            toast("D-pad scrolls under pointer; Back returns to pointer mode")
        })
        items.add(MenuItem("Start page") { showStartPage(false) })
        items.add(MenuItem("Reload") { reloadPage() })
        if (url != null && store.contains(url)) {
            items.add(MenuItem("Remove bookmark") {
                if (editBookmarks { store.remove(url) }) toast("Bookmark removed")
            })
        } else {
            items.add(MenuItem("Add bookmark") { startSaveBookmark() })
        }
        items.add(MenuItem("Bookmarks...") { showBookmarks(null, 0) })
        items.add(MenuItem("Address / search") { promptUrl() })
        items.add(MenuItem("Full-screen video") { fullscreenVideo() })
        // Per-site redirect blocking (owner-controlled, remembered per site): the current
        // site is guarded only when the user has protected it — this toggle or a bookmark.
        // No hardcoded list; unprotected sites navigate freely.
        val curHost: String? = if (url != null) (try { Uri.parse(url).host } catch (_: Throwable) { null }) else null
        if (curHost != null) {
            val site = PopupGuard.cleanHost(curHost)
            if (site.isNotEmpty()) {
                items.add(MenuItem("Block redirects from $site: " + (if (guard.isProtected(site)) "ON" else "OFF")) {
                    settings.toggleProtectedSite(site)
                })
            }
        }
        // Confirm site exit (toggle) - guard against back bailing to start page
        items.add(MenuItem("Confirm site exit: " + (if (confirmSiteExit) "ON" else "OFF")) {
            confirmSiteExit = !confirmSiteExit
            prefs.edit().putBoolean("confirmSiteExit", confirmSiteExit).apply()
            toast("Confirm site exit " + (if (confirmSiteExit) "ON" else "OFF"))
        })
        items.add(MenuItem("Back") { navigateHistory(-1) })
        items.add(MenuItem("Settings...") { showSettings(0) })
        items.add(MenuItem("Exit browser") { finish() })

        showMenu("Menu", items, null)
    }

    private fun toggleGuard() = settings.toggleGuard()
    private fun toggleDesktop() = settings.toggleDesktop()

    private fun editBookmarks(action: () -> Unit) = bookmarkUi.editBookmarks(action)
    private fun startSaveBookmark() = bookmarkUi.startSaveBookmark()

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

    private fun showBookmarks(folder: String?, focusRow: Int) = bookmarkUi.showBookmarks(folder, focusRow)
    private fun showBookmarks(folder: String?, focusRow: Int, movingUrl: String?) = bookmarkUi.showBookmarks(folder, focusRow, movingUrl)
    private fun moveBookmarkStep(url: String, direction: Int) = bookmarkUi.moveBookmarkStep(url, direction)
    private fun showBookmarkOptions(b: Bookmark, folder: String?, index: Int) = bookmarkUi.showBookmarkOptions(b, folder, index)
    private fun finishBookmarkMove() = bookmarkUi.finishBookmarkMove()

    // Settings, dark mode, diagnostics

    private fun darkModeSupported() = settings.darkModeSupported()
    private fun applyDarkMode() = settings.applyDarkMode()
    private fun showSettings(focus: Int) = settings.showSettings(focus)

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
        val chrome = uaPolicy.chromeVersion()
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

    // The settings host method names remain stable; no VPN/service or system routing.
    private fun ipv6ShieldState(): Boolean = prefs.getBoolean("ipv6Preference", true)

    private fun toggleIpv6Shield() {
        val newState = !ipv6ShieldState()
        prefs.edit().putBoolean("ipv6Preference", newState).apply()
        DiagnosticLog.record(this, "setting_ipv6_preference", JSONObject().put("enabled", newState))
        Ipv6Preference.apply(this, newState)
        toast("IPv6 preference " + (if (ipv6ShieldState()) "ON" else "OFF") + "; applies to new connections")
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
        advanceInteraction()
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

    // Small helpers

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
