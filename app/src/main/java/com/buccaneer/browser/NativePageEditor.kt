package com.buccaneer.browser

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.WebView

/** No system IME. The real Chromium InputConnection handles otherwise inaccessible fields. */
internal object NativePageEditor {
    /** Acquire on the view thread; execute on Chromium's requested input thread.
     * A cancelled/timed-out command is never replayed. Chromium owns the connection.
     */
    fun dispatch(view: WebView, action: String, text: String, result: (Boolean) -> Unit): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        val info = EditorInfo()
        val connection = try { view.onCreateInputConnection(info) } catch (_: Exception) { null }
        val timeout = Runnable { if (done.compareAndSet(false, true)) result(false) }
        val target = try {
            if (Build.VERSION.SDK_INT >= 24) connection?.handler ?: main else main
        } catch (_: Exception) { main }
        val task = Runnable {
            if (!done.get()) {
                val accepted = connection != null && info.inputType != InputType.TYPE_NULL &&
                    perform(connection, info, action, text)
                main.post { if (done.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    result(accepted)
                } }
            }
        }
        main.postDelayed(timeout, 1800L)
        if (!target.post(task)) main.post { if (done.compareAndSet(false, true)) {
            main.removeCallbacks(timeout)
            result(false)
        } }
        return { done.set(true); target.removeCallbacks(task); main.removeCallbacks(timeout) }
    }

    // Synchronous helper for connection-level tests on the owning thread only.
    internal fun apply(view: WebView, action: String, text: String): Boolean {
        val info = EditorInfo()
        val connection = try { view.onCreateInputConnection(info) } catch (_: Exception) { null } ?: return false
        return info.inputType != InputType.TYPE_NULL && perform(connection, info, action, text)
    }

    private fun perform(connection: InputConnection, info: EditorInfo, action: String, text: String): Boolean {
        return try {
            when (action) {
                "insert" -> connection.commitText(text, 1)
                "delete" -> {
                    val selected = connection.getSelectedText(0)
                    if (!selected.isNullOrEmpty()) connection.commitText("", 1)
                    else if (Build.VERSION.SDK_INT >= 24) connection.deleteSurroundingTextInCodePoints(1, 0)
                    else {
                        val before = connection.getTextBeforeCursor(2, 0)
                        val units = if (before != null && before.length >= 2 &&
                            Character.isSurrogatePair(before[before.length - 2], before[before.length - 1])) 2 else 1
                        connection.deleteSurroundingText(units, 0)
                    }
                }
                "clear" -> connection.performContextMenuAction(android.R.id.selectAll) && connection.commitText("", 1)
                "submit" -> {
                    val editorAction = if (info.actionId != 0) info.actionId else info.imeOptions and EditorInfo.IME_MASK_ACTION
                    if (editorAction != EditorInfo.IME_ACTION_UNSPECIFIED && editorAction != EditorInfo.IME_ACTION_NONE)
                        connection.performEditorAction(editorAction)
                    else {
                        val now = SystemClock.uptimeMillis()
                        val down = connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                        val up = connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
                        down && up
                    }
                }
                else -> false
            }
        } catch (_: Exception) { false }
    }
}
