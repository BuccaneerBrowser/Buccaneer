package com.buccaneer.browser

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-mdpi",shadows=[Regression171Test.ScriptWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DeepAuditTest {
    private fun field(a: MainActivity,n: String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun set(a: MainActivity,n: String,v: Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
    private fun call(a: MainActivity,n: String,vararg args: Any?){MainActivity::class.java.declaredMethods.single{it.name==n && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun browse(a: MainActivity){(field(a,"startPage") as StartPage).hide()}
    @Test fun autofocusAfterRemoteMovementDoesNotHijackThePlayKey() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
        call(a,"focusFromPage","{\"ok\":1,\"v\":\"\",\"pw\":0}")
        assertFalse("A site autofocus after D-pad movement must not open a keyboard",(field(a,"keypad") as Keypad).shown())
        c.pause().stop().destroy()
    }
    @Test fun unconfirmedNativeEditorDoesNotOpenKeyboardEvenAfterATap() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));set(a,"lastTapAt",SystemClock.uptimeMillis())
        call(a,"focusFromPage",null)
        assertFalse("A null native field state is not proof of a text field",(field(a,"keypad") as Keypad).shown())
        c.pause().stop().destroy()
    }
    @Test fun delayedTextFieldAfterAnActualTapStillOpensQwerty() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));set(a,"lastTapAt",SystemClock.uptimeMillis()-700L)
        call(a,"focusFromPage","{\"ok\":1,\"v\":\"\",\"pw\":0}")
        assertTrue((field(a,"keypad") as Keypad).shown());c.pause().stop().destroy()
    }
    @Test fun navigationClosesPageKeyboardInsteadOfLeavingItToConsumeMediaKeys() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        call(a,"openPageKeypad");assertTrue((field(a,"keypad") as Keypad).shown())
        val web=field(a,"webView") as WebView
        Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://example.test/next",null)
        assertFalse("New documents must not inherit old text-entry mode",(field(a,"keypad") as Keypad).shown())
        c.pause().stop().destroy()
    }
    @Test fun nestedScrollNormalizesNativePixelsToThePageViewport() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val web=field(a,"webView") as WebView
        for((name,value) in listOf("mLeft" to 0,"mTop" to 0,"mRight" to 1280,"mBottom" to 720))org.robolectric.util.ReflectionHelpers.setField(web,name,value)
        Regression171Test.ScriptWebView.scripts.clear();call(a,"scrollPage",0,180)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val js=Regression171Test.ScriptWebView.scripts.single{it.contains("__tvUi.scrollAt")}
        assertTrue("Native pixels cannot be passed directly to CSS hit-testing",js.contains("innerWidth/1280") && js.contains("innerHeight/720"))
        c.pause().stop().destroy()
    }
    @Test fun redirectsCannotRestartTheAutomaticCrashBudget() {
        val started=1_000_000L;var count=0;var previous:String?=null
        repeat(6){index->val url="https://example.test/redirect${index%2}";count=CrashRecoveryPolicy.nextCount(url,previous,started,count,started+index*1000);previous=url}
        assertEquals(3,count)
        assertEquals(3,CrashRecoveryPolicy.nextCount("a","a",started,Int.MAX_VALUE,started+1))
    }
    @Test fun navigationTerminatesTheOutstandingNativeTapWithCancel() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val events=mutableListOf<Int>();val target=object:android.view.View(a){override fun onTouchEvent(e:android.view.MotionEvent):Boolean{events.add(e.actionMasked);return true}}
        set(a,"customView",target);call(a,"sendTap",100f,100f,false)
        val web=field(a,"webView") as WebView
        Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://example.test/next",null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_CANCEL),events)
        c.pause().stop().destroy()
    }
    @Test fun overlappingRemoteTapsCannotInterleaveTwoDownsAndTwoUps() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val events=mutableListOf<Int>();val target=object:android.view.View(a){override fun onTouchEvent(e:android.view.MotionEvent):Boolean{events.add(e.actionMasked);return true}}
        set(a,"customView",target);call(a,"sendTap",100f,100f,false);call(a,"sendTap",200f,100f,false)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP),events)
        c.pause().stop().destroy()
    }
    @Test fun holdingFastForwardDoesNotFloodQualitySwitchRequests() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        Regression171Test.ScriptWebView.scripts.clear()
        repeat(16){rep->a.dispatchKeyEvent(KeyEvent(0,rep.toLong(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,rep))}
        assertEquals(0,Regression171Test.ScriptWebView.scripts.count{it.contains("__fb.listLevels()")})
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));c.pause().stop().destroy()
    }
    private fun promptResult(completed:MutableList<Boolean>):android.webkit.JsPromptResult {
        val receiverType=Class.forName("android.webkit.JsResult\$ResultReceiver")
        val receiver=java.lang.reflect.Proxy.newProxyInstance(receiverType.classLoader,arrayOf(receiverType)){_,method,args->
            if(method.name=="onJsResultComplete"){
                val result=args!![0]
                val accepted=android.webkit.JsResult::class.java.getDeclaredField("mResult").apply{isAccessible=true}.getBoolean(result)
                completed.add(accepted)
            }
            null
        }
        return android.webkit.JsPromptResult::class.java.getDeclaredConstructor(receiverType).apply{isAccessible=true}.newInstance(receiver)
    }
    @Test fun aWebPromptIsCancelledOnNavigationRatherThanAbandoned() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val completed=mutableListOf<Boolean>();val result=promptResult(completed);val web=field(a,"webView") as WebView
        assertTrue(Shadows.shadowOf(web).webChromeClient.onJsPrompt(web,"https://example.test/","Question","",result))
        assertTrue((field(a,"keypad") as Keypad).shown())
        Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://example.test/next",null)
        // Robolectric's ShadowJsResult.cancel() records cancellation but deliberately
        // does not call the Android receiver. Assert its documented cancellation flag.
        assertTrue(Shadows.shadowOf(result).wasCancelled())
        assertFalse((field(a,"keypad") as Keypad).shown());assertNull(field(a,"pendingJsPrompt"))
        val cancel=MainActivity::class.java.getDeclaredMethod("cancelPendingJsPrompt").apply{isAccessible=true}
        assertEquals(false,cancel.invoke(a));c.pause().stop().destroy()
    }
    @Test fun aWebPromptCanConfirmAnEmptyOrWhitespaceValue() {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
        val web=field(a,"webView") as WebView
        for(value in listOf("","  ")){
            val completed=mutableListOf<Boolean>();val result=promptResult(completed)
            Shadows.shadowOf(web).webChromeClient.onJsPrompt(web,"https://example.test/","Question",value,result)
            call(a,"typeGo");assertEquals(listOf(true),completed)
            val returned=android.webkit.JsPromptResult::class.java.getDeclaredField("mStringResult").apply{isAccessible=true}.get(result)
            assertEquals(value,returned);assertNull(field(a,"pendingJsPrompt"))
        }
        c.pause().stop().destroy()
    }

}
