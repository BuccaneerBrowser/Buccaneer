package com.buccaneer.browser
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowWebView
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-mdpi",shadows=[IntegrationPassTest.DelayedWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class IntegrationPassTest {
 @Implements(WebView::class) class DelayedWebView:ShadowWebView(){
  @Implementation override fun evaluateJavascript(script:String,cb:ValueCallback<String>?){
   scripts.add(script)
   val kind=when{script.endsWith(",'probe')")->"probe";script.contains("__fb.prepareFullscreen()")->"fullscreen";script.contains("__fb.field()")->"field";script.contains("__fb.diag()")->"diag";else->"other"}
   if(kind==hold){callbacks.add(cb);return};cb?.onReceiveValue("null")
  }
  companion object{var hold="";val callbacks=mutableListOf<ValueCallback<String>?>();val scripts=mutableListOf<String>()}
 }
 private val controllers=mutableListOf<ActivityController<MainActivity>>()
 @Before fun reset(){DelayedWebView.hold="";DelayedWebView.callbacks.clear();DelayedWebView.scripts.clear()}
 @After fun cleanup(){controllers.forEach{it.pause().stop().destroy()}}
 private fun make():MainActivity {val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();controllers.add(c);val a=c.get();(get(a,"startPage") as StartPage).hide();return a}
 private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 private fun set(a:MainActivity,n:String,v:Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
 private fun call(a:MainActivity,n:String,vararg args:Any?){MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
 private fun finish(raw:String){DelayedWebView.hold="";val c=DelayedWebView.callbacks.toList();DelayedWebView.callbacks.clear();c.forEach{it?.onReceiveValue(raw)}}
 @Test fun delayedSmartKeyboardCannotReplaceANewerAddressPrompt(){
  val a=make();DelayedWebView.hold="field";call(a,"openKeypadSmart");call(a,"promptUrl");call(a,"typeText","https://keep.test/")
  finish(JSONObject.quote("{\"ok\":1,\"v\":\"stale field\"}"));assertEquals("PROMPT",get(a,"textMode")!!.toString());assertEquals("https://keep.test/",get(a,"buffer")!!.toString())
 }
 @Test fun delayedDiagnosticsCannotCoverANewerKeyboard(){
  val a=make();DelayedWebView.hold="diag";call(a,"showDiagnostics");call(a,"promptUrl")
  finish(JSONObject.quote("{\"videos\":[],\"frames\":0}"));assertFalse((get(a,"textPanel") as TextPanel).shown());assertTrue((get(a,"keypad") as Keypad).shown())
 }
 @Test fun legacyFocusNotificationDoesNotEraseQualifiedPasswordState(){
  val a=make();set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime())
  val doc=a.Bridge().currentDocument();val state=JSONObject().put("ok",1).put("doc",doc).put("field","1").put("v","").put("pw",1).put("length",6).toString()
  a.Bridge().editableFocused("frame",state);assertEquals("PAGE",get(a,"textMode")!!.toString())
  a.Bridge().editableClickedForDocument(doc);assertEquals(state,get(a,"inputFieldState"))
 }
 @Test fun delayedFlixerProbeCannotTapANewFullscreenTarget(){
  val a=make();set(a,"onFlixerPage",true);val web=get(a,"webView") as WebView
  ReflectionHelpers.setField(web,"mLeft",0);ReflectionHelpers.setField(web,"mTop",0);ReflectionHelpers.setField(web,"mRight",1280);ReflectionHelpers.setField(web,"mBottom",720)
  DelayedWebView.hold="probe";call(a,"tapAtCursor");assertEquals(1,DelayedWebView.callbacks.size)
  val touches=mutableListOf<Int>();val newer=object:View(a){override fun onTouchEvent(e:MotionEvent):Boolean{touches.add(e.actionMasked);return true}}
  call(a,"enterFullscreen",newer,null);finish("\"frame\"");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(touches.isEmpty())
 }
 @Test fun bookmarkCompletionCannotDiscardANewerAddressBeingTyped(){
  val a=make();val started=CountDownLatch(1);val release=CountDownLatch(1)
  val work:()->BookmarkJobs.Result={started.countDown();release.await(5,TimeUnit.SECONDS);BookmarkJobs.Result("Imported",true)}
  call(a,"runFileOperation",work);assertTrue(started.await(5,TimeUnit.SECONDS));call(a,"promptUrl");call(a,"typeText","https://keep.test/");release.countDown()
  val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(BookmarkJobs.isBusy()&&System.nanoTime()<deadline){Thread.sleep(5);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))}
  assertFalse(BookmarkJobs.isBusy());assertTrue((get(a,"keypad") as Keypad).shown());assertEquals("https://keep.test/",get(a,"buffer")!!.toString());assertFalse((get(a,"listPanel") as ListPanel).shown())
 }
 @Test fun normalizedBackupSizeCannotExceedItsOwnImporterLimit(){
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("bookmarks",0).edit().clear().commit();val store=BookmarkStore(app);store.add("Old","https://old.test/","")
  val arr=JSONArray();repeat(18000){arr.put(JSONObject().put("u","https://b.test/path/$it"))};val raw=JSONObject().put("items",arr).toString()
  assertTrue(raw.toByteArray().size<1024*1024);assertFalse("Reject before commit if normalization makes the backup unimportable",store.importJson(raw));assertEquals("Old",store.all().single().title)
 }
 @Test fun failedFullscreenPreparationCannotTapThePage(){
  val a=make();val touches=mutableListOf<Int>();val web=get(a,"webView") as WebView
  web.setOnTouchListener{_,e->touches.add(e.actionMasked);true};call(a,"fullscreenVideo")
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertTrue(touches.isEmpty());assertEquals(false,get(a,"pendingFullscreen"))
 }
 @Test fun newUserInputCancelsAnAcknowledgedButNotYetTappedFullscreenRequest(){
  val a=make();val touches=mutableListOf<Int>();(get(a,"webView") as WebView).setOnTouchListener{_,e->touches.add(e.actionMasked);true}
  DelayedWebView.hold="fullscreen";call(a,"fullscreenVideo");finish("\"ok\"");assertEquals(true,get(a,"pendingFullscreen"))
  call(a,"noteUserInput");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
  assertTrue(touches.isEmpty());assertEquals(false,get(a,"pendingFullscreen"));assertTrue(DelayedWebView.scripts.any{it.contains("__fb.cancelFullscreen()")})
 }
 @Test fun oversizedOrdinaryFieldsCannotProduceAPretendPortableBackup(){
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("bookmarks",0).edit().clear().commit();val store=BookmarkStore(app)
  store.add("Old","https://old.test/","");store.rename("https://old.test/","x".repeat(2049))
  try{store.exportPortableJson();fail("Oversized backup must report failure before opening a destination")}catch(e:java.io.IOException){assertTrue(e.message!!.contains("oversized"))}
  assertEquals(2049,store.all().single().title.length)
 }
 @Test fun delayedSmartKeyboardCannotOpenWhileActivityIsPaused(){
  val a=make();DelayedWebView.hold="field";call(a,"openKeypadSmart");controllers.last().pause()
  finish(JSONObject.quote("{\"ok\":1,\"v\":\"stale\"}"));assertFalse((get(a,"keypad") as Keypad).shown())
 }

 @Test fun pickerResultSubmittedBeforeResumeRetainsItsCompletionUiOwner(){
  val a=make();val c=controllers.last();c.pause();val started=CountDownLatch(1);val release=CountDownLatch(1)
  val work:()->BookmarkJobs.Result={started.countDown();release.await(5,TimeUnit.SECONDS);BookmarkJobs.Result("Imported",true)}
  call(a,"runFileOperation",work);assertTrue(started.await(5,TimeUnit.SECONDS));c.resume();release.countDown()
  val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(BookmarkJobs.isBusy()&&System.nanoTime()<deadline){Thread.sleep(5);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))}
  assertFalse(BookmarkJobs.isBusy());assertTrue((get(a,"listPanel") as ListPanel).shown())
 }

}
