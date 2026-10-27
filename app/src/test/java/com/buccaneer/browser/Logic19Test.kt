package com.buccaneer.browser
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowWebView
import java.time.Duration

/** Regression contracts for independently reproduced interaction failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-mdpi",shadows=[Logic19Test.LogicWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Logic19Test {
 @Implements(WebView::class) class LogicWebView:ShadowWebView(){
  @Implementation override fun evaluateJavascript(script:String,cb:ValueCallback<String>?){
   scripts.add(script)
   if(script.contains("__tvUi.scrollAt") && failScroll) throw IllegalStateException("injected evaluation failure")
   if(script.contains("__tvUi.scrollAt") && holdScroll)return
   if(script.contains("__fb.seek(") && holdSeek){seekCallbacks.add(cb);return}
   val result=when {
    script.contains("__fb.playBlocked()") -> "1"
    script.contains("__fb.frameCenter()") -> "\"{\\\"ok\\\":1,\\\"x\\\":0.5,\\\"y\\\":0.5}\""
    script.contains("__fb.play()") || script.contains("__fb.pause()") -> "\"ok\""
    else -> "null"
   };cb?.onReceiveValue(result)
  }
  companion object{val scripts=mutableListOf<String>();var failScroll=false;var holdScroll=false;var holdSeek=false;val seekCallbacks=mutableListOf<ValueCallback<String>?>()}
 }
 @Before fun reset(){LogicWebView.scripts.clear();LogicWebView.failScroll=false;LogicWebView.holdScroll=false;LogicWebView.holdSeek=false;LogicWebView.seekCallbacks.clear()}
 fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 fun set(a:MainActivity,n:String,v:Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
 fun call(a:MainActivity,n:String,vararg v:Any?){MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==v.size}.apply{isAccessible=true}.invoke(a,*v)}
 fun browse(a:MainActivity){(get(a,"startPage") as StartPage).hide()}
 @Test fun pauseCancelsEveryEarlierPlayRepair(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  val actions=mutableListOf<Int>();set(a,"customView",object:View(a){override fun onTouchEvent(e:MotionEvent):Boolean{actions.add(e.actionMasked);return true}})
  call(a,"videoCommand","play()",KeyEvent.KEYCODE_MEDIA_PLAY,true)
  call(a,"videoCommand","pause()",KeyEvent.KEYCODE_MEDIA_PAUSE,false)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
  assertTrue("Pause invalidates delayed Play repair",actions.isEmpty())
  c.pause().stop().destroy()
 }
 @Test fun backgroundingCancelsPlayRepair(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  val actions=mutableListOf<Int>();set(a,"customView",object:View(a){override fun onTouchEvent(e:MotionEvent):Boolean{actions.add(e.actionMasked);return true}})
  call(a,"videoCommand","play()",KeyEvent.KEYCODE_MEDIA_PLAY,true);c.pause()
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
  assertFalse(get(a,"foreground") as Boolean);assertTrue("No repair tap while backgrounded",actions.isEmpty())
  c.stop().destroy()
 }
 @Test fun evaluationExceptionReleasesScrollQueue(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  LogicWebView.failScroll=true;call(a,"scrollPage",0,100)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
  LogicWebView.failScroll=false;val before=LogicWebView.scripts.count{it.contains("__tvUi.scrollAt")}
  call(a,"scrollPage",0,100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
  assertEquals(false,get(a,"scrollPending"));assertTrue(LogicWebView.scripts.count{it.contains("__tvUi.scrollAt")}>before)
  c.pause().stop().destroy()
 }
 @Test fun navigationOwnsFullscreenTeardown(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  val oldView=View(a);set(a,"customView",oldView)
  val web=get(a,"webView") as WebView;Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://new.test/",null)
  assertNull("Navigation releases old fullscreen target",get(a,"customView"))
  c.pause().stop().destroy()
 }
 @Test fun unqualifiedOldFrameCannotReplaceKeyboardTarget(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
  val web=get(a,"webView") as WebView;Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://new.test/",null)
  set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime())
  call(a,"openPageKeypad");set(a,"inputFrame","new-frame")
  a.Bridge().editableFocused("old-frame","{\"ok\":1,\"v\":\"wrong field\",\"pw\":0}")
  assertEquals("new-frame",get(a,"inputFrame"));c.pause().stop().destroy()
 }
 @Test fun missingEvaluationCallbackTimesOutAndUnblocksTheNextScroll(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  LogicWebView.holdScroll=true;call(a,"scrollPage",0,100);assertEquals(true,get(a,"scrollPending"))
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100));assertEquals(false,get(a,"scrollPending"))
  LogicWebView.holdScroll=false;val before=LogicWebView.scripts.size;call(a,"scrollPage",0,100)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
  assertTrue(LogicWebView.scripts.size>before);assertEquals(false,get(a,"scrollPending"));c.pause().stop().destroy()
 }
 @Test fun seekQueueIsBoundedAndLateCompletionAfterPauseCannotDrainIt(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  LogicWebView.holdSeek=true;call(a,"videoSeek",60);repeat(100){call(a,"videoSeek",60)}
  assertEquals(1,LogicWebView.seekCallbacks.size);assertEquals(120,get(a,"queuedSeek"))
  call(a,"videoCommand","pause()",KeyEvent.KEYCODE_MEDIA_PAUSE,false)
  LogicWebView.seekCallbacks.single()?.onReceiveValue("\"ok\"")
  assertEquals(1,LogicWebView.seekCallbacks.size);assertEquals(0,get(a,"queuedSeek"));assertEquals(false,get(a,"seekInFlight"))
  c.pause().stop().destroy()
 }
 @Test fun currentDocumentReportsCannotRedirectAPinnedField(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime())
  val token=a.Bridge().currentDocument()
  a.Bridge().editableFocused("current-frame","{\"ok\":1,\"doc\":\"$token\",\"field\":\"first\",\"v\":\"original\"}")
  assertEquals("current-frame",get(a,"inputFrame"));assertEquals("first",get(a,"inputFieldId"))
  a.Bridge().editableFocused("current-frame","{\"ok\":1,\"doc\":\"$token\",\"field\":\"other\",\"v\":\"wrong\"}")
  assertEquals("first",get(a,"inputFieldId"));assertFalse((get(a,"inputFieldState") as String).contains("wrong"));c.pause().stop().destroy()
 }
 @Test fun importRejectsUnsafeUrlsAndMalformedFoldersAndSupportsUndo(){
  val app=org.robolectric.RuntimeEnvironment.getApplication();app.getSharedPreferences("bookmarks",0).edit().clear().commit()
  val store=BookmarkStore(app);store.add("Old","https://old.test/","")
  assertFalse(store.importJson("{\"items\":[{\"u\":\"javascript:alert(1)\"}]}"))
  assertFalse(store.importJson("{\"folders\":[42],\"items\":[]}"));assertEquals("Old",store.all().single().title)
  assertTrue(store.importJson("{\"items\":[{\"u\":\"https://new.test/\"}]}"));assertTrue(store.hasPreviousImport())
  assertTrue(store.undoImport());assertEquals("https://old.test/",store.all().single().url)
 }
 @Test fun transferResultWaitsForTheReplacementScreenInsteadOfDisappearing(){
  val release=java.util.concurrent.CountDownLatch(1);val begun=java.util.concurrent.CountDownLatch(1)
  var oldCalled=false;var newCalled=false
  val old=object:BookmarkJobs.Listener{override fun onBookmarkJob(result:BookmarkJobs.Result):Boolean{oldCalled=true;return false}}
  val newer=object:BookmarkJobs.Listener{override fun onBookmarkJob(result:BookmarkJobs.Result):Boolean{newCalled=true;return true}}
  BookmarkJobs.attach(old);assertTrue(BookmarkJobs.submit{begun.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);BookmarkJobs.Result("done")})
  assertTrue(begun.await(5,java.util.concurrent.TimeUnit.SECONDS));BookmarkJobs.detach(old);release.countDown()
  val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
  while(BookmarkJobs.isBusy()&&System.nanoTime()<deadline){Thread.sleep(5);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))}
  assertFalse(BookmarkJobs.isBusy());assertFalse(oldCalled);BookmarkJobs.attach(newer);assertTrue(newCalled);BookmarkJobs.detach(newer)
 }

 @Test fun oldDocumentCannotClaimMediaOwnershipOrExtendPlaybackHeartbeat(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();browse(a)
  val old=a.Bridge().currentDocument();a.Bridge().mediaStarted(old+":frame");assertEquals(old+":frame",get(a,"mediaOwner"))
  val web=get(a,"webView") as WebView;Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://new.test/",null)
  a.Bridge().mediaStarted(old+":ad");a.Bridge().playbackActive(old)
  assertEquals("",get(a,"mediaOwner"));assertEquals(0L,get(a,"playbackHeartbeatAt"))
  val current=a.Bridge().currentDocument();a.Bridge().playbackActive(current);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue((get(a,"playbackHeartbeatAt") as Long)>0)
  c.pause();a.Bridge().mediaStarted(current+":background");assertEquals("",get(a,"mediaOwner"));c.stop().destroy()
 }
 @Test fun resizedRootClampsPointerAndNativePanelWidthsWithoutRecreatingWebView(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get();val web=get(a,"webView")
  val root=get(a,"root") as android.view.ViewGroup;set(a,"cx",1270f);set(a,"cy",710f)
  root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(450,View.MeasureSpec.EXACTLY));root.layout(0,0,800,450)
  assertTrue((get(a,"cx") as Float)<800);assertTrue((get(a,"cy") as Float)<450)
  assertTrue((get(a,"keypad") as View).layoutParams.width<=800);assertTrue((get(a,"osd") as View).layoutParams.width<=800)
  assertSame(web,get(a,"webView"));c.pause().stop().destroy()
 }

 @Test fun largeBookmarkListCreatesOnlyOneManagementPageOfRows(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get()
  val arr=org.json.JSONArray();repeat(10000){arr.put(org.json.JSONObject().put("t","mark-$it").put("u","https://b.test/$it"))}
  assertTrue((get(a,"store") as BookmarkStore).importJson(org.json.JSONObject().put("items",arr).toString()))
  fun labels(v:View):List<String>{val result=mutableListOf<String>();if(v is android.widget.TextView)result.add(v.text.toString());if(v is android.view.ViewGroup)for(i in 0 until v.childCount)result.addAll(labels(v.getChildAt(i)));return result}
  call(a,"showBookmarks",null,0);val first=labels(get(a,"listPanel") as View)
  assertEquals(50,first.count{it.startsWith("mark-")});assertTrue(first.contains("mark-0"));assertFalse(first.contains("mark-50"))
  call(a,"showBookmarks",null,9950);val last=labels(get(a,"listPanel") as View)
  assertEquals(50,last.count{it.startsWith("mark-")});assertTrue(last.contains("mark-9999"));assertFalse(last.contains("mark-0"));c.pause().stop().destroy()
 }

}
