package com.buccaneer.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.FrameLayout

/** Stateless WebView-only dispatch, shared UI mechanism (never editor/media state). */
class PageKeyDispatcher(
    private val web: () -> WebView,
    private val rootView: () -> FrameLayout,
    private val pageAvailable: () -> Boolean,
    private val isForeground: () -> Boolean,
    private val panelShown: () -> Boolean
) {
    private val webView get() = web()
    private val root get() = rootView()
    private val nativePanelShown get() = panelShown()
    fun dispatchToPage(action: Int, keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            now, now, action, keyCode, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        // The page always lives in the WebView. In full screen the custom view is only the
        // video's rendering surface — keys sent to it never reach the page.
        webView.dispatchKeyEvent(ev)
    }

    fun pressKey(keyCode: Int, meta: Int) {
        dispatchToPage(KeyEvent.ACTION_DOWN, keyCode, meta)
        dispatchToPage(KeyEvent.ACTION_UP, keyCode, meta)
    }

    fun sendKeyToPage(keyCode: Int) {
        if (!pageAvailable() || !isForeground()) return
        val previousFocus = root.findFocus()
        webView.requestFocus()
        pressKey(keyCode, 0)
        if (previousFocus != null && previousFocus !== webView && previousFocus.isAttachedToWindow &&
            nativePanelShown) previousFocus.requestFocus()
    }

}
