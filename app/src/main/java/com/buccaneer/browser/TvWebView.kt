package com.buccaneer.browser

import android.content.Context
import android.webkit.WebView

/** Keep Chromium's editor intact; MainActivity's window excludes the platform IME. */
class TvWebView(context: Context) : WebView(context) {
    // The app has its own remote-friendly keypad; the system IME must never be
    // requested for this WebView. The window flag alone does not suppress
    // FireTVIME on Android 7.1 — when the IME pops up it takes focus, and
    // dismissing it with Back blurs the field and kills typing. This is the
    // 1.5 behaviour, restored.
    override fun onCheckIsTextEditor(): Boolean = false
}
