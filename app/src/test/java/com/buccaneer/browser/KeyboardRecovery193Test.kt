package com.buccaneer.browser
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowWebView
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25,28],qualifiers="w1280dp-h720dp-mdpi",shadows=[KeyboardRecovery193Test.EditorWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardRecovery193Test {
 @Implements(WebView::class) class EditorWebView:ShadowWebView(){
  @Implementation override fun evaluateJavascript(script:String,cb:ValueCallback<String>?){cb?.onReceiveValue(if(script.contains("__tvUi.fieldState"))JSONObject.quote(fresh) else "null")}
  companion object {var fresh=""}
 }
 private val cs=mutableListOf<ActivityController<MainActivity>>()
 @After fun clean(){cs.forEach{it.pause().stop().destroy()};EditorWebView.fresh=""}
 private fun make():MainActivity {val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();cs.add(c);val a=c.get();(get(a,"startPage") as StartPage).hide();return a}
 private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 private fun set(a:MainActivity,n:String,v:Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
 private fun call(a:MainActivity,n:String,vararg args:Any?){MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
 private fun layout(a:MainActivity){val r=get(a,"root") as FrameLayout;repeat(3){r.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));r.layout(0,0,1280,720);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))}}
 @Test fun windowExcludesSystemImeWithoutDisablingTheEditor(){val a=make();assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM!=0)}
 @Test fun chromiumEditorConnectionIsNotReplacedWithNull(){assertFalse(TvWebView::class.java.declaredMethods.any{it.name=="onCreateInputConnection"||it.name=="onCheckIsTextEditor"})}
 @Test fun keyboardReservesUncoveredViewportAndRestoresMatchParent(){val a=make();layout(a);call(a,"openPageKeypad");layout(a);val w=get(a,"webView") as WebView;val k=get(a,"keypad") as Keypad;assertTrue("keyboard visible",k.shown());
  // Chromium has no layout provider under Robolectric: assert the actual native
  // layout request against the measured keypad, not its shadow's zero WebView height.
  assertTrue("Viewport ${w.layoutParams.height}, keyboard top ${k.top}",w.layoutParams.height in 80..(k.top-8));call(a,"closeKeypad");layout(a);assertEquals(-1,w.layoutParams.height)}
 @Test fun freshQualifiedReadWinsOverCachedPreview(){val a=make();set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime());val doc=a.Bridge().currentDocument();a.Bridge().editableFocused("frame","{\"ok\":1,\"doc\":\"$doc\",\"field\":\"one\",\"v\":\"old\"}");EditorWebView.fresh="{\"ok\":1,\"doc\":\"$doc\",\"frame\":\"frame\",\"field\":\"one\",\"v\":\"new\"}";call(a,"syncField");val k=get(a,"keypad") as Keypad;val p=Keypad::class.java.getDeclaredField("previewView").apply{isAccessible=true}.get(k) as TextView;assertEquals("new|",p.text.toString())}
 @Test fun explicitSameSiteRetryResetsOnlyItsAutomaticRecoveryBudget(){val a=make();val p=a.getSharedPreferences("settings",0);p.edit().putInt("crashReloadCount",3).putString("lastCrashUrl","https://crashed.test/").putLong("crashWindowStartedAt",System.currentTimeMillis()).commit();set(a,"crashReloadCount",3);call(a,"openUrl","https://crashed.test/");assertEquals(0,p.getInt("crashReloadCount",-1));assertEquals(0,get(a,"crashReloadCount"));assertEquals("https://crashed.test/",Shadows.shadowOf(get(a,"webView") as WebView).lastLoadedUrl)}
 @Test fun automaticStartupRetryDoesNotResetBudget(){val app=org.robolectric.RuntimeEnvironment.getApplication();app.getSharedPreferences("settings",0).edit().putInt("crashReloadCount",2).putString("lastCrashUrl","https://crashed.test/").putLong("crashWindowStartedAt",System.currentTimeMillis()).commit();val a=make();assertEquals(2,get(a,"crashReloadCount"));assertEquals(2,a.getSharedPreferences("settings",0).getInt("crashReloadCount",-1))}
 // Framework renderer-death callback was introduced at API26; do not invoke the absent API25 base method.
 @Test @Config(sdk=[28]) fun pausedRendererDeathStillDisposesDeadBrowser(){val a=make();val w=get(a,"webView") as WebView;val client=Shadows.shadowOf(w).webViewClient;cs.last().pause();client.onRenderProcessGone(w,null);assertEquals(true,get(a,"webViewDestroyed"));assertNull(w.parent)}
 @Test fun promptDoesNotLeavePageViewportShrunk(){val a=make();layout(a);call(a,"openPageKeypad");layout(a);call(a,"promptUrl");layout(a);assertEquals(-1,(get(a,"webView") as WebView).layoutParams.height)}
 @Test fun allPanelCloseRestoresViewport(){val a=make();layout(a);call(a,"openPageKeypad");layout(a);call(a,"closeAllPanels");layout(a);assertEquals(-1,(get(a,"webView") as WebView).layoutParams.height)}
 // Framework renderer-death callback was introduced at API26; do not invoke the absent API25 base method.
 @Test @Config(sdk=[28]) fun crashBeforePageStartedStillRemembersRequestedUrl(){val a=make();call(a,"openUrl","https://immediate-crash.test/");val w=get(a,"webView") as WebView;Shadows.shadowOf(w).webViewClient.onRenderProcessGone(w,null);assertEquals("https://immediate-crash.test/",a.getSharedPreferences("settings",0).getString("lastCrashUrl",""));assertEquals(1,get(a,"crashReloadCount"))}
 // Framework renderer-death callback was introduced at API26; do not invoke the absent API25 base method.
 @Test @Config(sdk=[28]) fun foreignRendererCallbackCannotDisposeCurrentBrowser(){val a=make();val w=get(a,"webView") as WebView;val foreign=WebView(a);Shadows.shadowOf(w).webViewClient.onRenderProcessGone(foreign,null);assertEquals(false,get(a,"webViewDestroyed"));assertNotNull(w.parent);foreign.destroy()}
 // Framework renderer-death callback was introduced at API26; do not invoke the absent API25 base method.
 @Test @Config(sdk=[28]) fun pausedRecoveryWaitsForResume(){val a=make();val w=get(a,"webView") as WebView;cs.last().pause();Shadows.shadowOf(w).webViewClient.onRenderProcessGone(w,null);assertEquals(false,get(a,"rendererRecreatePosted"));call(a,"requestRendererRecovery");assertEquals(false,get(a,"rendererRecreatePosted"));cs.last().resume();assertEquals(true,get(a,"rendererRecreatePosted"))}
 @Test fun automaticNavigationDoesNotResetRedirectCrashBudget(){val a=make();set(a,"crashReloadCount",2);val p=a.getSharedPreferences("settings",0);p.edit().putInt("crashReloadCount",2).commit();call(a,"navigateUrl","https://redirect.test/",false);assertEquals(2,get(a,"crashReloadCount"));assertEquals(2,p.getInt("crashReloadCount",0))}
 @Test fun wrongDocumentFreshReadCannotOverwriteQualifiedSnapshot(){val a=make();set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime());val doc=a.Bridge().currentDocument();a.Bridge().editableFocused("frame","{\"ok\":1,\"doc\":\"$doc\",\"field\":\"one\",\"v\":\"correct\"}");EditorWebView.fresh="{\"ok\":1,\"doc\":\"old-document\",\"frame\":\"frame\",\"field\":\"one\",\"v\":\"wrong\"}";call(a,"syncField");val p=Keypad::class.java.getDeclaredField("previewView").apply{isAccessible=true}.get(get(a,"keypad")) as TextView;assertEquals("correct|",p.text.toString())}

}
