package com.buccaneer.browser

import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class BridgeStability196Test {
    private val controllers=mutableListOf<ActivityController<MainActivity>>()
    @After fun cleanup(){controllers.forEach{it.pause().stop().destroy()}}
    private fun make():MainActivity {val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();controllers.add(c);return c.get()}
    private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun call(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredMethod(n,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,true)
    private fun drain(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    @Test fun popupStormPreservesCountAndClampsOverflow(){val a=make();repeat(10000){a.Bridge().blocked("test")};assertEquals(0,get(a,"blockedCount"));drain();assertEquals(10000,get(a,"blockedCount"));MainActivity::class.java.getDeclaredField("blockedCount").apply{isAccessible=true}.setInt(a,Int.MAX_VALUE-1);repeat(10){a.Bridge().blocked("test")};drain();assertEquals(Int.MAX_VALUE,get(a,"blockedCount"))}
    @Test fun pendingPopupBurstCannotLeakAcrossNavigation(){val a=make();a.Bridge().blocked("old");call(a,"resetDocumentWork");a.Bridge().blocked("new");drain();assertEquals(1,get(a,"blockedCount"))}
    @Test fun pendingHeartbeatCannotRefreshTheNewDocument(){val a=make();a.Bridge().playbackActive(a.Bridge().currentDocument());call(a,"resetDocumentWork");drain();assertEquals(0L,get(a,"playbackHeartbeatAt"));a.Bridge().playbackActive(a.Bridge().currentDocument());drain();assertEquals(SystemClock.elapsedRealtime(),get(a,"playbackHeartbeatAt"))}
    @Test fun backgroundHeartbeatIsNotConsumedAsActivePlayback(){val a=make();a.Bridge().playbackActive(a.Bridge().currentDocument());controllers.last().pause();drain();assertEquals(0L,get(a,"playbackHeartbeatAt"));controllers.last().resume()}
    @Test fun rendererRecoveryDoesNotLeaveCoalescersStranded(){
        val a=make();val w=get(a,"webView") as WebView;a.Bridge().blocked("old")
        Shadows.shadowOf(w).webViewClient.onPageStarted(w,"https://example.invalid/",null)
        a.Bridge().blocked("still old");Shadows.shadowOf(w).webViewClient.onRenderProcessGone(w,null);drain()
        assertTrue(get(a,"webViewDestroyed") as Boolean)
        // Production recreates the Activity; it does not revive the destroyed instance.
        val replacement=make();replacement.Bridge().blocked("new");drain()
        assertFalse(get(replacement,"recoveringRenderer") as Boolean);assertEquals(1,get(replacement,"blockedCount"))
    }
    @Test fun deeplyNestedBridgePayloadsCannotReachRecursiveParsers(){val a=make();val deep="[".repeat(4000)+"0"+"]".repeat(4000);a.Bridge().tmdbAllowlist("[\"https://image.tmdb.org/valid\"]");val old=get(a,"tmdbAllowlist");a.Bridge().tmdbAllowlist(deep);assertSame(old,get(a,"tmdbAllowlist"));a.Bridge().editableFocused("frame","{\"ok\":1,\"doc\":\"${a.Bridge().currentDocument()}\",\"extra\":$deep}");assertEquals("",get(a,"inputFrame"))}
    @Test fun hostileBackupDepthCannotReplaceOrDestroyExistingBookmarks(){val a=make();val st=BookmarkStore.shared(a);st.add("Keep","https://keep.invalid/","");val old=st.exportJson();assertFalse(st.importJson("{\"items\":[],\"unused\":"+"[".repeat(4000)+"0"+"]".repeat(4000)+"}"));assertEquals(old,st.exportJson())}
    @Test fun pathologicalDiagnosticsAreOmittedRatherThanParsed(){val a=make();DiagnosticLog.startNewSession(a);a.Bridge().diagnosticEvent("hostile","{\"extra\":"+"[".repeat(4000)+"0"+"]".repeat(4000)+"}");assertTrue(DiagnosticLog.hasData(a));DiagnosticLog.stop(a)}
}
