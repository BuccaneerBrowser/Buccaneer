package com.buccaneer.browser

/** WebView ABI. Nullable strings are untrusted JavaBridge input; implementations validate budgets and identity.
 * Synchronous queries run on JavaBridge; mutators dispatch to the UI thread and recheck identity. */
interface BrowserBridgeContract {
    fun isDiagnosticsEnabled(): Boolean
    fun diagnosticEvent(event: String?, details: String?): Unit
    fun isGuardEnabled(): Boolean
    fun isDarkMode(): Boolean
    fun pageIsForeground(): Boolean
    fun cssFullscreen(active: Boolean): Unit
    fun playbackActive(token: String?): Unit
    fun activeInputFrame(): String
    fun currentDocument(): String
    fun canRetryDeadVideo(): Boolean
    fun editableFocused(frame: String?, state: String?): Unit
    fun editableBlurred(frame: String?, token: String?, field: String?): Unit
    fun blocked(what: String?): Unit
    fun editableClicked(): Unit
    fun editableClickedForDocument(token: String?): Unit
    fun exclusiveEnabled(): Boolean
    fun lowQualityEnabled(): Boolean
    fun mediaStarted(id: String?): Unit
    fun mediaStopped(id: String?): Unit
    fun activeOwner(): String
    fun tmdbAllowlist(urls: String?): Unit
}
