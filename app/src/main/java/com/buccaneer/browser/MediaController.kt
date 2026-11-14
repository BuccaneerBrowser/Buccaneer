package com.buccaneer.browser

import android.media.AudioManager
import android.os.Handler
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import org.json.JSONObject

interface MediaHost {
    val webView: WebView
    val customView: View?
    val cssFullscreenActive: Boolean
    val onFlixerPage: Boolean
    val nativeSeekAllowed: Boolean
    val nativeMuteAllowed: Boolean
    val audio: AudioManager
    val osd: Osd
    val cursor: CursorView
    val handler: Handler
    fun pageAvailable(): Boolean
    fun eval(script: String, done: ((String?) -> Unit)? = null)
    fun postForPage(delayMs: Long, action: () -> Unit)
    fun sendKeyToPage(keyCode: Int)
    fun sendTap(x: Float, y: Float, mouse: Boolean = false)
    fun toast(msg: String)
    fun toastOnce(msg: String)
}

/** Video intent, fullscreen requests, seek queue and OSD cancellation. No editor capability. */
class MediaController(private val host: MediaHost, private val document: DocumentIdentity): MediaHost by host {
    private var acceptingCommands=false
    private var interactionSerial=0L
    fun interactionChanged() { interactionSerial++ }
    fun setForeground(active: Boolean) { acceptingCommands=active }
    private val seekRepeat = SeekRepeatPolicy()
    private var seekGeneration = 0L
    private var playerIntent = 0L
    private var pendingFullscreen = false
    private var seekInFlight = false
    private var queuedSeek = 0
    private var osdRefresh: Runnable? = null
    private var osdRequest = 0L
    fun cancelOsdRefresh() {
        osdRefresh?.let { handler.removeCallbacks(it) }
        osdRefresh = null
        osdRequest++
    }

    fun cancelPendingFullscreen() {
        if (!pendingFullscreen) return
        pendingFullscreen = false
        if (pageAvailable()) eval("window.__fb && __fb.cancelFullscreen && __fb.cancelFullscreen()")
    }

    fun cancelPlayerWork() {
        cancelPendingFullscreen()
        playerIntent++
        seekGeneration++
        seekInFlight = false
        queuedSeek = 0
        seekRepeat.reset()
    }

    /**
     * Runs a page_helpers.js video command. Answers "ok", "frame" (the player's own frame ran it —
     * except the Flixer, which keeps the trusted-tap path), or "none" (send the key).
     */
    /** Unmute and its fallback probe share the transport's own cancellation stamps. */
    fun unmute() {
        val interaction = interactionSerial
        val intent = playerIntent
        val target: View = customView ?: webView
        fun current() = acceptingCommands && interaction == interactionSerial && intent == playerIntent && target === (customView ?: webView) && nativeMuteAllowed
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

    fun videoCommand(call: String, fallbackKey: Int, tapFrame: Boolean = false) {
        cancelPlayerWork()
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.$call : 'none'") { raw ->
            if (!acceptingCommands || intent != playerIntent || target !== (customView ?: webView)) return@eval
            when (jsString(raw)) {
                "ok" -> if (tapFrame) postForPage(350) { if (intent == playerIntent && target === (customView ?: webView)) retryIfPlayRefused() }
                "frame" -> if (tapFrame && fallbackKey == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && onFlixerPage) {
                    // the poster-grid site's player frame is not scripted: a real tap starts and toggles it.
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
        if (!acceptingCommands) return
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.playBlocked() : 0") { raw ->
            if (acceptingCommands && intent == playerIntent && target === (customView ?: webView) && jsString(raw) == "1") tapPlayerFrame()
        }
    }

    /** The player is inside a frame from another site, so we tap its middle, as a finger would. */
    fun tapPlayerFrame(mouse: Boolean = onFlixerPage) {
        if (!acceptingCommands) return
        val intent = playerIntent
        val target: View = customView ?: webView
        eval("window.__fb ? __fb.frameCenter() : ''") { raw ->
            if (!acceptingCommands || intent != playerIntent || target !== (customView ?: webView)) return@eval
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

    fun seekFromKey(event: KeyEvent, direction: Int) {
        val seconds = seekRepeat.step(event.keyCode, event.repeatCount, event.downTime, event.eventTime)
        if (seconds != 0) videoSeek(seconds * direction)
    }

    fun videoSeek(seconds: Int) {
        if (!pageAvailable() || !acceptingCommands || seconds == 0) return
        if (pendingFullscreen) { cancelPendingFullscreen(); playerIntent++ }
        if (seekInFlight) { queuedSeek = (queuedSeek + seconds).coerceIn(-120, 120); return }
        seekInFlight = true
        val generation = ++seekGeneration
        val intent = playerIntent
        val target: View = customView ?: webView
        fun valid() = acceptingCommands && generation == seekGeneration && intent == playerIntent && target === (customView ?: webView)
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
                        if (valid() && nativeSeekAllowed) sendKeyToPage(key)
                    }
                    postForPage(count * 80L + 40L) { if (valid()) { refreshOsd(75, "Native seek steps depend on this player"); finish() } }
                }
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        if (!fraction.isFinite() || !pageAvailable() || !acceptingCommands) return
        cancelPlayerWork()
        val intent = playerIntent
        val amount = fraction.coerceIn(0f, 1f)
        // Passive, one-shot API only: no new player hooks, observers or site styling.
        eval("(function(){try{if(!window.__fb)return 'none';var s=JSON.parse(__fb.state());" +
            "if(s.f!==1||typeof s.d!=='number'||typeof s.t!=='number'||!isFinite(s.d)||s.d<=0||!isFinite(s.t))return 'none';" +
            "var t=s.d*$amount;var r=__fb.seek(t-s.t);if(r!=='ok')return r;" +
            "var after=JSON.parse(__fb.state());return Math.abs(after.t-t)<=2?'ok':'blocked';" +
            "}catch(e){return 'blocked';}})()") { raw ->
            if (!acceptingCommands || intent != playerIntent) return@eval
            if (jsString(raw) != "ok") toastOnce("This player did not accept the position; use FF/Rewind")
            refreshOsd(150, "Select position • Hold FF/Rewind for larger skips")
        }
    }

    fun adjustVolume(dir: Int) {
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

    fun refreshOsd(delayMs: Long, hint: String) {
        if (!pageAvailable() || !acceptingCommands) return
        cancelOsdRefresh()
        val request = osdRequest
        val epoch = document.epoch
        val refresh = Runnable {
            osdRefresh = null
            if (!pageAvailable() || !acceptingCommands || epoch != document.epoch || request != osdRequest) return@Runnable
            eval("window.__fb ? __fb.state() : ''") { raw ->
                if (!acceptingCommands || request != osdRequest) return@eval
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
    fun fullscreenVideo() {
        cancelPlayerWork()
        val intent = playerIntent
        val interaction = interactionSerial
        val target: View = customView ?: webView
        pendingFullscreen = true
        eval("window.__fb ? __fb.prepareFullscreen() : 'none'") { raw ->
            if (!acceptingCommands || interaction != interactionSerial || intent != playerIntent || target !== (customView ?: webView)) return@eval
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

}
