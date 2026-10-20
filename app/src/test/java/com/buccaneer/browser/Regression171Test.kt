package com.buccaneer.browser

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWebView
import java.time.Duration

/** Exercise real Activity dispatchKeyEvent/panel transitions, not just helper methods. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1280dp-h720dp-mdpi", shadows=[Regression171Test.ScriptWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Regression171Test {
    @Implements(WebView::class)
    class ScriptWebView : ShadowWebView() {
        @Implementation override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            scripts.add(script)
            val result=when {
                script.contains("'probe'") -> "\"$seekResult\""
                script.contains("__fb.frameCenter()") -> "\"{\\\"ok\\\":1,\\\"x\\\":0.5,\\\"y\\\":0.5}\""
                script.contains("__fb.togglePlay()") || script.contains("__fb.play()") || script.contains("__fb.pause()") || script.contains("__fb.seek(") -> "\"$mediaResult\""
                else -> "null"
            }
            callback?.onReceiveValue(result)
        }
        companion object {
            val scripts=mutableListOf<String>()
            var mediaResult="ok"
            var seekResult="none"
        }
    }
    @Before fun reset() { ScriptWebView.scripts.clear();ScriptWebView.mediaResult="ok";ScriptWebView.seekResult="none" }
    private fun field(a: MainActivity,name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun set(a: MainActivity,name: String,value: Any?) {MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(a,value)}
    private fun call(a: MainActivity,name: String,vararg args: Any?) {
        val method=MainActivity::class.java.declaredMethods.single{it.name==name && it.parameterCount==args.size};method.isAccessible=true;method.invoke(a,*args)
    }
    private fun descendants(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
    private fun key(a: MainActivity,code: Int) {a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))}
    private fun browse(a: MainActivity) {(field(a,"startPage") as StartPage).hide();ScriptWebView.scripts.clear()}
    private fun mediaScripts()=ScriptWebView.scripts.filter{it.contains("__fb.togglePlay()")||it.contains("__fb.play()")||it.contains("__fb.pause()")||it.contains("__fb.seek(")}

    @Test fun hardwarePlayWorksInBrowseMenuBookmarkListAndFullscreen() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        key(a,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);assertEquals(1,mediaScripts().size)
        call(a,"showMainMenu");key(a,KeyEvent.KEYCODE_MEDIA_PLAY);assertEquals(2,mediaScripts().size)
        call(a,"showBookmarks",null,0);key(a,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);assertEquals(3,mediaScripts().size)
        call(a,"closeAllPanels");set(a,"customView",View(a));key(a,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);assertEquals(4,mediaScripts().size)
        val count=mediaScripts().size
        a.dispatchKeyEvent(KeyEvent(0,1,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,1));assertEquals(count,mediaScripts().size)
        c.pause().stop().destroy()
    }
    @Test fun keyboardRetainsPlayAsEnterInsteadOfStartingVideo() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        // Address prompt is genuine text entry: Play remains its Go/Enter shortcut.
        call(a,"promptUrl");ScriptWebView.scripts.clear();key(a,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(mediaScripts().isEmpty());c.pause().stop().destroy()
    }
    @Test fun missingHelperFallsBackToOneNativeKeyPairAndPauseNeverTapsPlayer() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val keys=mutableListOf<Pair<Int,Int>>();val touches=mutableListOf<Int>()
        val target=object:View(a){
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {keys.add(e.action to e.keyCode);return true}
            override fun onTouchEvent(e: MotionEvent): Boolean {touches.add(e.actionMasked);return true}
        };set(a,"customView",target)
        ScriptWebView.mediaResult="none";key(a,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),keys)
        keys.clear();ScriptWebView.mediaResult="frame";key(a,KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PAUSE),keys)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));assertTrue(touches.isEmpty())
        c.pause().stop().destroy()
    }
    private fun awaitBookmarkJob() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (BookmarkJobs.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(5)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
        }
        assertFalse("Bookmark job completed", BookmarkJobs.isBusy())
    }
    @Test fun importAppliesImmediatelyAndInvalidImportKeepsExistingDataWithoutConfirmation() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get()
        val store=field(a,"store") as BookmarkStore
        store.importJson("{\"items\":[{\"u\":\"https://old.test/\",\"t\":\"Old\"}]}")
        call(a,"showBookmarks",null,0)
        call(a,"importBookmarkText","{\"items\":[{\"u\":\"https://new.test/\",\"t\":\"New\"}]}")
        awaitBookmarkJob()
        assertEquals("https://new.test/",store.all().single().url)
        assertFalse((field(a,"menu") as MenuPanel).shown());assertTrue((field(a,"listPanel") as ListPanel).shown())
        assertFalse(descendants(field(a,"root") as View).filterIsInstance<TextView>().any{it.text.toString().contains("Replace current bookmarks?")})
        call(a,"importBookmarkText","{\"items\":[{\"u\":42}]}")
        awaitBookmarkJob()
        assertEquals("https://new.test/",store.all().single().url);c.pause().stop().destroy()
    }
    @Test fun importExportMenusReplaceBookmarkPanelAndAreAboveFullscreen() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        call(a,"showBookmarks",null,0)
        call(a,"showMenu","Import bookmarks",listOf(MenuItem("Choose a JSON file"){}),null)
        val root=field(a,"root") as ViewGroup;val menu=field(a,"menu") as MenuPanel
        assertTrue(menu.shown());assertFalse((field(a,"listPanel") as ListPanel).shown())
        assertTrue(root.indexOfChild(menu)>root.indexOfChild(field(a,"fullscreenBox") as View))
        call(a,"showBookmarks",null,0);assertFalse(menu.shown());assertTrue((field(a,"listPanel") as ListPanel).shown())
        c.pause().stop().destroy()
    }
    @Test fun flixerSelectSendsMouseDownUpButOtherSitesStillGetTouch() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val events=mutableListOf<MotionEvent>();val web=field(a,"webView") as WebView
        web.setOnTouchListener{_,e->events.add(MotionEvent.obtain(e));true}
        // Robolectric's fake WebView provider does not lay out the native frame.
        // Supply its viewport explicitly; the test still dispatches through the real Activity.
        org.robolectric.util.ReflectionHelpers.setField(web,"mLeft",0)
        org.robolectric.util.ReflectionHelpers.setField(web,"mTop",0)
        org.robolectric.util.ReflectionHelpers.setField(web,"mRight",1280)
        org.robolectric.util.ReflectionHelpers.setField(web,"mBottom",720)
        assertEquals("Pointer fixture needs a laid out WebView",1280,web.width)
        assertEquals(720,web.height)
        assertNull(field(a,"customView"))
        ScriptWebView.seekResult="track"
        set(a,"onFlixerPage",true);key(a,KeyEvent.KEYCODE_DPAD_CENTER)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),events.map{it.actionMasked})
        assertTrue("Events="+events.map{ "${it.source}/${it.getToolType(0)}" }+" scripts="+ScriptWebView.scripts.map{it.takeLast(100)},events.all{it.source==InputDevice.SOURCE_MOUSE && it.getToolType(0)==MotionEvent.TOOL_TYPE_MOUSE})
        assertEquals(MotionEvent.BUTTON_PRIMARY,events.first().buttonState);assertEquals(0,events.last().buttonState)
        events.forEach{it.recycle()};events.clear()
        ScriptWebView.seekResult="none" // A working Flixer button retains touch, not mouse.
        key(a,KeyEvent.KEYCODE_DPAD_CENTER)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertEquals(2,events.size);assertTrue(events.all{it.source==InputDevice.SOURCE_TOUCHSCREEN})
        events.forEach{it.recycle()};events.clear()
        set(a,"onFlixerPage",false);key(a,KeyEvent.KEYCODE_DPAD_CENTER)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertEquals(2,events.size);assertTrue(events.all{it.source==InputDevice.SOURCE_TOUCHSCREEN})
        events.forEach{it.recycle()};c.pause().stop().destroy()
    }
    @Test fun pointerBuilderSetsCoordinatesToolButtonsPressureAndTiming() {
        for(mouse in listOf(false,true))for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=PointerTap.event(100,170,action,300f,400f,mouse)
            assertEquals(action,e.actionMasked);assertEquals(100L,e.downTime);assertEquals(170L,e.eventTime)
            assertEquals(300f,e.x,0f);assertEquals(400f,e.y,0f)
            assertEquals(if(action==MotionEvent.ACTION_UP)0f else 1f,e.pressure,0f)
            assertEquals(if(mouse)MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER,e.getToolType(0));e.recycle()
        }
    }
}
