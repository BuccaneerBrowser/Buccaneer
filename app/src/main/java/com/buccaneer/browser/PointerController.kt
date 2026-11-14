package com.buccaneer.browser

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebView
import org.json.JSONObject

interface PointerHost {
    val context: Context
    val root: FrameLayout
    val webView: WebView
    val customView: View?
    val cursor: CursorView
    val ui: Ui
    val osd: Osd
    val handler: Handler
    val foreground: Boolean
    val onFlixerPage: Boolean
    val interactionSerial: Long
    val playbackHeartbeatAt: Long
    val seekClickJs: String
    fun pageAvailable(): Boolean
    fun eval(script: String, done: ((String?) -> Unit)? = null)
    fun postForPage(delayMs: Long, action: () -> Unit)
    fun hideIme()
    fun focusFromPage(state: String)
    fun seekToFraction(fraction: Float)
    fun captureTapPermit(target: View): () -> Boolean
    fun canRepairSeek(interaction: Long): Boolean
    fun clearPressedKeys()
}
/** Native pointer events and their lifetime. No editor or media owner is exposed. */
class PointerController(private val host: PointerHost, private val document: DocumentIdentity): PointerHost by host {
    private var scrollXPending = 0
    private var scrollYPending = 0
    private var scrollPending = false
    var cx = 0f; private set
    var cy = 0f; private set
    var lastTapAt = 0L; private set
    private var pendingTap: PendingTap? = null
    private var tapUp: Runnable? = null
    private var hoverActive = false
    private var lastHoverAt = 0L
    private data class PendingTap(val target: View, val downAt: Long, val x: Float, val y: Float, val mouse: Boolean)
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
    fun position(x: Float,y: Float) { cx=x;cy=y }
    fun recordTap() { lastTapAt=SystemClock.uptimeMillis() }
    fun resetTap() { lastTapAt=0L }
    fun resetScroll() { scrollPending=false;scrollXPending=0;scrollYPending=0 }
    fun moveCursor(code: Int, repeat: Int) {
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

    /** Scroll the page: move the document when it scrolls itself, otherwise send mouse-wheel events that scroll whatever is under the pointer. */
    fun scrollPage(dx: Int, dy: Int) {
        if (!pageAvailable() || !foreground) return
        scrollXPending = (scrollXPending + dx).coerceIn(-1500, 1500)
        scrollYPending = (scrollYPending + dy).coerceIn(-1500, 1500)
        if (scrollPending) return
        scrollPending = true
        val epoch = document.epoch
        handler.postDelayed({
            if (!pageAvailable() || !foreground || epoch != document.epoch) { if (epoch == document.epoch) scrollPending = false; return@postDelayed }
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

    fun showCursor() {
        cursor.visibility = View.VISIBLE
        handler.removeCallbacks(hideCursorRunnable)
        handler.postDelayed(hideCursorRunnable, 6000)
    }

    fun hideCursor() {
        handler.removeCallbacks(hideCursorRunnable)
        cursor.visibility = View.GONE
        liftPointerIfPlaying()
        hoverActive = false
    }

    fun tapAtCursor() {
        // The pointer remembers where it was, so the first press clicks there and shows the
        // cursor at the same time — you can act on a page right after it loads.
        osd.seekFractionAt(cx, cy)?.let {
            seekToFraction(it)
            showCursor()
            return
        }
        lastTapAt = SystemClock.uptimeMillis()
        val interaction = interactionSerial
        val target: View = customView ?: webView
        val currentTap = captureTapPermit(target)
        fun current() = currentTap()
        val x = cx
        val y = cy
        if (webView.width > 0 && webView.height > 0) eval("window.__fb && __fb.selectAt ? __fb.selectAt(${x/webView.width},${y/webView.height}) : 'none'")
        if (onFlixerPage && customView == null && webView.width > 0 && webView.height > 0) {
            // Preserve working poster-grid site button taps. Mouse input is only needed over a
            // recognized timeline, or inside an opaque player frame we cannot inspect.
            eval("($seekClickJs)(${x / webView.width},${y / webView.height},'probe')") { raw ->
                if (!current()) return@eval
                val mouse = jsString(raw) in listOf("track", "frame")
                sendTap(x, y, mouse)
                if (mouse) repairSeekClick(x, y, interaction)
            }
        } else sendTap(x, y)
        showCursor()
        // On-demand field reads also cover the poster-grid site without adding any listeners or
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

    /** One-shot fallback for identifiable seek tracks only; no poster-grid site listeners/timers/DOM styling. */
    fun repairSeekClick(x: Float, y: Float, interaction: Long = interactionSerial) {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0 || seekClickJs.isBlank()) return
        postForPage(120) {
            if (!canRepairSeek(interaction)) return@postForPage
            eval("($seekClickJs)(${x / width},${y / height})")
        }
    }

    /** Trusted native input: touch by default, mouse only for the poster-grid site pointer/player clicks. */
    fun sendTap(x: Float, y: Float, mouse: Boolean = false) {
        if (!pageAvailable() || !foreground) return
        val epoch = document.epoch
        val target: View = customView ?: webView
        DiagnosticLog.record(context, "tap_dispatched", JSONObject().put("x", x).put("y", y).put("w", webView.width).put("h", webView.height))
        // Diagnostic: capture the element stack at the tap point to see which element eats the click.
        // The probe runs in the top frame; for a cross-origin player frame the <iframe> is the top element.
        if (DiagnosticLog.isEnabled(context)) {
            try {
                val script = "window.__fb ? __fb.tapStack(${x},${y}) : 'none'"
                eval(script) { raw ->
                    try {
                        val text = jsString(raw)
                        if (!text.isNullOrEmpty() && text != "none") {
                            DiagnosticLog.record(context, "tap_stack", JSONObject(text))
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
            else finishPendingTap(cancel = epoch != document.epoch || target !== (customView ?: webView))
        }
        tapUp = up
        handler.postDelayed(up, 70L)
    }

    /** Every live DOWN terminates with UP or CANCEL; renderer loss clears without native calls. */
    fun finishPendingTap(cancel: Boolean, deliver: Boolean = true) {
        if (cancel) clearPressedKeys()
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
     * Lift the simulated mouse pointer out of the page: the browser parks it at the last hover
     * position and never tells the page it is gone, so pointer-aware players pin their controls.
     * Only while video plays — while browsing the parked pointer keeps hover menus open.
     */
    fun liftPointerIfPlaying() {
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
            DiagnosticLog.record(context, "pointer_lifted", JSONObject().put("x", cx).put("y", cy))
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

}
