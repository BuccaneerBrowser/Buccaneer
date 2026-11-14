package com.buccaneer.browser

/** Historical integration tests can inspect state after ownership moves, without duplicating routing. */
object TestAccess {
    private fun target(a: MainActivity, name: String): Any {
        if (name == "pageEpoch" || name == "documentToken")
            return MainActivity::class.java.getDeclaredField("pageIdentity").apply { isAccessible=true }.get(a)!!
        return a
    }
    private fun editor(a: MainActivity): EditorController = a.javaClass.getDeclaredMethod("getEditor").apply { isAccessible=true }.invoke(a) as EditorController
    private val editorNames = setOf("inputFrame", "inputFieldState", "inputFieldId", "inputFocusConfirmed", "pageEdits", "pageEditActive", "nativeEditCancel", "keyboardDismissedUntil", "editorSession", "textMode", "buffer", "bufferPassword", "pendingJsPrompt", "promptAllowsEmpty", "promptDone", "promptCancel", "syncRunnable")
    private val mediaNames = setOf("seekRepeat", "seekGeneration", "playerIntent", "pendingFullscreen", "seekInFlight", "queuedSeek", "osdRefresh", "osdRequest")
    private fun media(a: MainActivity): MediaController = a.javaClass.getDeclaredMethod("getMedia").apply { isAccessible=true }.invoke(a) as MediaController
    private val settingsNames=setOf("darkMode","desktopSite","autoplay","lowQuality","exclusiveVideo","softwareRendering")
    private fun settings(a: MainActivity): BrowserSettings = a.javaClass.getDeclaredMethod("getSettings").apply { isAccessible=true }.invoke(a) as BrowserSettings
    private val pointerNames=setOf("scrollXPending","scrollYPending","scrollPending","cx","cy","lastTapAt","pendingTap","tapUp","hoverActive","lastHoverAt","hideCursorRunnable")
    private fun pointer(a: MainActivity): PointerController = a.javaClass.getDeclaredMethod("getPointer").apply { isAccessible=true }.invoke(a) as PointerController
    fun get(a: MainActivity, name: String): Any? {
        if(name in pointerNames) return PointerController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(pointer(a))
        if(name in settingsNames) return BrowserSettings::class.java.getDeclaredField(name).apply { isAccessible=true }.get(settings(a))
        if(name=="bookmarksFolder" || name=="movingBookmarkUrl") {
            val owner=a.javaClass.getDeclaredMethod("getBookmarkUi").apply { isAccessible=true }.invoke(a)
            return BookmarkUiController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(owner)
        }
        if(name=="foreground") return (a.javaClass.getDeclaredField("foregroundState").apply { isAccessible=true }.get(a) as ForegroundState).foreground
        if(name == "tmdbAllowlist") {
            val owner=a.javaClass.getDeclaredMethod("getImageFirewall").apply { isAccessible=true }.invoke(a)
            return ImageFirewall::class.java.getDeclaredField("allowlist").apply { isAccessible=true }.get(owner)
        }
        if(name in mediaNames) return MediaController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(media(a))
        if(name in editorNames) return EditorController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(editor(a))
        if(name == "pageEpoch") return (target(a,name) as PageIdentity).epoch
        if(name == "documentToken") return (target(a,name) as PageIdentity).token
        if(name == "mediaOwner" || name == "videoActive") {
            val media=MainActivity::class.java.getDeclaredField("mediaOwnership").apply { isAccessible=true }.get(a) as MediaOwnership
            return if(name == "mediaOwner") media.owner else media.videoActive
        }
        return a.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(a)
    }
    fun set(a: MainActivity, name: String, value: Any?) {
        if(name=="foreground") {
            val owner=a.javaClass.getDeclaredField("foregroundState").apply { isAccessible=true }.get(a) as ForegroundState
            if(value==true) owner.resume() else owner.pause()
            return
        }
        val target: Any = when(name) { in editorNames -> editor(a); in mediaNames -> media(a); in settingsNames -> settings(a); in pointerNames -> pointer(a); else -> a }
        target.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(target,value)
    }
}
