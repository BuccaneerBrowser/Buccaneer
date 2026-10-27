package com.buccaneer.browser

import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1280dp-h720dp-mdpi", shadows=[Regression171Test.ScriptWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Release18Test {
    private fun field(a: MainActivity,n: String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun set(a: MainActivity,n: String,v: Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
    private fun call(a: MainActivity,n: String,vararg args: Any?){MainActivity::class.java.declaredMethods.single{it.name==n && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun browse(a: MainActivity){(field(a,"startPage") as StartPage).hide()}
    @Before fun reset(){Regression171Test.ScriptWebView.scripts.clear();Regression171Test.ScriptWebView.mediaResult="ok"}
    @Test fun holdPolicyUsesTimeAndDoesNotFloodAtFastRemoteRepeatRates(){
        val p=SeekRepeatPolicy();assertEquals(10,p.step(1,0,1000,1000))
        for(t in 1001L..1899L step 10)assertEquals(0,p.step(1,1,1000,t))
        assertEquals(30,p.step(1,90,1000,1900));assertEquals(0,p.step(1,91,1000,2000))
        assertEquals(30,p.step(1,92,1000,2800));assertEquals(60,p.step(1,93,1000,3700))
        assertEquals(0,p.step(2,1,1000,4600));assertEquals(10,p.step(2,0,4700,4700))
        p.reset();assertEquals(0,p.step(2,1,4700,5700))
    }
    @Test fun bothTransportDirectionsUse10Then30Then60WithoutQualityChanges(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        for((code,sign) in listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to 1,KeyEvent.KEYCODE_MEDIA_REWIND to -1)){
            Regression171Test.ScriptWebView.scripts.clear()
            for((i,t) in listOf(1000L,1900L,2800L,3700L).withIndex())a.dispatchKeyEvent(KeyEvent(1000,t,KeyEvent.ACTION_DOWN,code,i))
            a.dispatchKeyEvent(KeyEvent(1000,3800,KeyEvent.ACTION_UP,code,0))
            val js=Regression171Test.ScriptWebView.scripts
            assertTrue(js.any{it.contains("__fb.seek(${10*sign})")});assertEquals(2,js.count{it.contains("__fb.seek(${30*sign})")})
            assertTrue(js.any{it.contains("__fb.seek(${60*sign})")});assertFalse(js.any{it.contains("listLevels")||it.contains("setQuality")})
        };c.pause().stop().destroy()
    }
    @Test fun fullscreenArrowsHaveTheSameLongSeekBehavior(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a);set(a,"customView",View(a))
        for((code,sign) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1,KeyEvent.KEYCODE_DPAD_LEFT to -1)){
            call(a,"onFullscreenKey",KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,code,0))
            call(a,"onFullscreenKey",KeyEvent(1000,4000,KeyEvent.ACTION_DOWN,code,3))
            assertTrue(Regression171Test.ScriptWebView.scripts.any{it.contains("__fb.seek(${60*sign})")})
        };c.pause().stop().destroy()
    }
    @Test fun opaquePlayersGetSixSeparateSeekKeyPairsForSixtySeconds(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val keys=mutableListOf<Pair<Int,Int>>();val target=object:View(a){override fun dispatchKeyEvent(e:KeyEvent):Boolean{keys.add(e.action to e.keyCode);return true}}
        set(a,"customView",target);Regression171Test.ScriptWebView.mediaResult="frame";call(a,"videoSeek",60)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(6,keys.count{it==KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_DPAD_RIGHT})
        assertEquals(6,keys.count{it==KeyEvent.ACTION_UP to KeyEvent.KEYCODE_DPAD_RIGHT})
        c.pause().stop().destroy()
    }
    @Test fun pauseCancelsTheRemainingNativeSeekBurst(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val keys=mutableListOf<Int>();val target=object:View(a){override fun dispatchKeyEvent(e:KeyEvent):Boolean{if(e.action==0)keys.add(e.keyCode);return true}}
        set(a,"customView",target);Regression171Test.ScriptWebView.mediaResult="frame";call(a,"videoSeek",60)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        call(a,"videoCommand","pause()",KeyEvent.KEYCODE_MEDIA_PAUSE,false)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1,keys.count{it==KeyEvent.KEYCODE_DPAD_RIGHT});assertTrue(keys.contains(KeyEvent.KEYCODE_MEDIA_PAUSE))
        c.pause().stop().destroy()
    }
    @Test fun navigationCancelsNativeSeekBurst(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val keys=mutableListOf<Int>();val target=object:View(a){override fun dispatchKeyEvent(e:KeyEvent):Boolean{keys.add(e.keyCode);return true}}
        set(a,"customView",target);Regression171Test.ScriptWebView.mediaResult="frame";call(a,"videoSeek",60)
        val web=field(a,"webView") as WebView;Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://example.test/next",null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));assertTrue(keys.isEmpty());c.pause().stop().destroy()
    }
    @Test fun openingKeyboardStopsNativeSeekKeysFromMovingTheTextCaret(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val keys=mutableListOf<Int>();val target=object:View(a){override fun dispatchKeyEvent(e:KeyEvent):Boolean{keys.add(e.keyCode);return true}}
        set(a,"customView",target);Regression171Test.ScriptWebView.mediaResult="frame";call(a,"videoSeek",60)
        call(a,"promptUrl");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(keys.isEmpty());c.pause().stop().destroy()
    }
    @Test fun nativeSeekBarHasBoundedHitTestingAndHiddenBarIsNotClickable(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();val osd=field(a,"osd") as Osd
        osd.show("PAUSED",0.5f,"1:00 / 2:00","Volume",0.5f,"")
        osd.measure(View.MeasureSpec.makeMeasureSpec(620,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.AT_MOST));osd.layout(300,350,920,350+osd.measuredHeight)
        val bar=Osd::class.java.getDeclaredField("seekBar").apply{isAccessible=true}.get(osd) as View
        val loc=IntArray(2);bar.getLocationOnScreen(loc);val origin=IntArray(2);osd.rootView.getLocationOnScreen(origin)
        val x=(loc[0]-origin[0]).toFloat();val y=(loc[1]-origin[1]).toFloat()
        assertEquals(0.5f,osd.seekFractionAt(x+bar.width/2f,y+bar.height/2f)!!,0.001f)
        assertNull(osd.seekFractionAt(x-10,y));assertNull(osd.seekFractionAt(x,y-50));assertNull(osd.seekFractionAt(Float.NaN,y))
        osd.hideNow();assertNull(osd.seekFractionAt(x,y));c.pause().stop().destroy()
    }
    @Test fun selectingNativePositionBarDoesNotClickTheUnderlyingWebsite(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a);val osd=field(a,"osd") as Osd
        osd.show("PAUSED",0.5f,"1:00 / 2:00","Volume",0.5f,"")
        osd.measure(View.MeasureSpec.makeMeasureSpec(620,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.AT_MOST));osd.layout(300,350,920,350+osd.measuredHeight)
        val bar=Osd::class.java.getDeclaredField("seekBar").apply{isAccessible=true}.get(osd) as View
        val loc=IntArray(2);bar.getLocationOnScreen(loc);val origin=IntArray(2);osd.rootView.getLocationOnScreen(origin)
        set(a,"cx",(loc[0]-origin[0])+bar.width/2f);set(a,"cy",(loc[1]-origin[1])+bar.height/2f)
        val touches=mutableListOf<Int>();(field(a,"webView") as WebView).setOnTouchListener{_,e->touches.add(e.actionMasked);true}
        Regression171Test.ScriptWebView.scripts.clear();call(a,"tapAtCursor")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(touches.isEmpty());assertTrue(Regression171Test.ScriptWebView.scripts.any{it.contains("var t=s.d*0.5")})
        c.pause().stop().destroy()
    }
    @Test fun unknownJavascriptPlaybackStateDoesNotDimTheScreen(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(21))
        (field(a,"idleCheck") as Runnable).run()
        assertEquals(false,field(a,"dimmed"));assertTrue(Regression171Test.ScriptWebView.scripts.any{it.contains("s.fr===1?-1:0")})
        c.pause().stop().destroy()
    }
}
