package com.buccaneer.browser
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Button
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25,28],qualifiers="w960dp-h540dp-xhdpi",shadows=[Structural1910Test.ControlledWeb::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Structural1910Test {
 @Implements(WebView::class) class ControlledWeb:ShadowWebView(){
  @Implementation override fun evaluateJavascript(script:String,callback:ValueCallback<String>?){
   scripts.add(script)
   if(script.contains("var media=document.querySelectorAll") && holdCleanup){cleanup.add(callback);return};callback?.onReceiveValue("null")
  }
  companion object{val scripts=mutableListOf<String>();var holdCleanup=false;val cleanup=mutableListOf<ValueCallback<String>?>()}
 }
 private val controllers=mutableListOf<ActivityController<MainActivity>>()
 @After fun clean(){ControlledWeb.holdCleanup=false;ControlledWeb.cleanup.clear();ControlledWeb.scripts.clear();controllers.forEach{it.pause().stop().destroy()}}
 private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 private fun set(a:MainActivity,n:String,v:Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
 private fun call(a:MainActivity,n:String,vararg args:Any?)=MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)
 private fun make():MainActivity {val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();controllers.add(c);return c.get().also{(get(it,"startPage") as StartPage).hide();(get(it,"webView") as WebView).loadUrl("https://fixture.invalid/one")}}
 private fun button(v:View,label:String):Button? {if(v is Button && v.text.toString()==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount){val x=button(v.getChildAt(i),label);if(x!=null)return x};return null}
 @Test fun showingMenuCancelsPendingPointerSelectInsteadOfClickingBehindIt(){
  val a=make();val now=SystemClock.uptimeMillis();a.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0));assertEquals(true,get(a,"selectPending"))
  call(a,"showMainMenu");a.dispatchKeyEvent(KeyEvent(now,now+100,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0))
  assertNull("The Select release must not dispatch a page tap behind a menu",get(a,"pendingTap"))
 }
 @Test fun menuReloadInvalidatesOldEditorAndPlayerWorkBeforeWaitingForCleanup(){
  val a=make();val before=get(a,"pageEpoch");val oldPlayer=get(a,"playerIntent") as Long;val oldEditor=get(a,"editorSession") as Long
  call(a,"showMainMenu");ControlledWeb.holdCleanup=true;button(get(a,"menu") as View,"Reload")!!.performClick()
  assertNotEquals(before,get(a,"pageEpoch"));assertTrue((get(a,"playerIntent") as Long)>oldPlayer);assertTrue((get(a,"editorSession") as Long)>oldEditor)
  assertTrue((get(a,"navigationHandoff") as NavigationHandoff).isPending)
 }
 @Test fun pausingWithNativeCleanupPendingMustNotNavigateInBackground(){
  val a=make();ControlledWeb.holdCleanup=true;call(a,"openUrl","https://fixture.invalid/two");assertTrue(ControlledWeb.cleanup.isNotEmpty());controllers.last().pause();ControlledWeb.cleanup.last()?.onReceiveValue("\"stopped\"")
  assertEquals("https://fixture.invalid/one",Shadows.shadowOf(get(a,"webView") as WebView).lastLoadedUrl)
 }
 @Test fun latePageStartCannotCancelDeferredNavigationAndResumeCommitsIt(){
  val a=make();val web=get(a,"webView") as WebView;ControlledWeb.holdCleanup=true
  call(a,"openUrl","https://fixture.invalid/two");controllers.last().pause()
  ControlledWeb.cleanup.last()?.onReceiveValue("\"stopped\"")
  assertNotNull(get(a,"deferredNavigation"))
  Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://fixture.invalid/late",null)
  assertNotNull("A stale callback cannot discard the deferred destination",get(a,"deferredNavigation"))
  controllers.last().resume()
  assertEquals("https://fixture.invalid/two",Shadows.shadowOf(web).lastLoadedUrl)
  assertNull(get(a,"deferredNavigation"))
 }
 @Test fun newerNavigationSupersedesDeferredOldCommand(){
  val a=make();ControlledWeb.holdCleanup=true;call(a,"openUrl","https://fixture.invalid/two");controllers.last().pause()
  ControlledWeb.cleanup.last()?.onReceiveValue("\"stopped\"");call(a,"openUrl","https://fixture.invalid/three")
  ControlledWeb.cleanup.last()?.onReceiveValue("\"stopped\"");controllers.last().resume()
  assertEquals("https://fixture.invalid/three",Shadows.shadowOf(get(a,"webView") as WebView).lastLoadedUrl)
 }
 private fun key(a:MainActivity,code:Int,repeat:Int=0){
  val now=SystemClock.uptimeMillis();a.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,repeat));a.dispatchKeyEvent(KeyEvent(now,now+20,KeyEvent.ACTION_UP,code,0))
 }
 @Test fun bookmarkMoveModeReordersRepeatedlyWithoutOptionsAndPersists(){
  val a=make();val store=get(a,"store") as BookmarkStore
  repeat(8){store.add("Item $it","https://move.invalid/$it","")}
  val b=store.find("https://move.invalid/4")!!;call(a,"showBookmarkOptions",b,null,4)
  button(get(a,"menu") as View,"Move up / down...")!!.performClick()
  repeat(3){key(a,KeyEvent.KEYCODE_DPAD_UP)}
  assertEquals(b.url,store.all()[1].url);assertEquals(b.url,get(a,"movingBookmarkUrl"))
  assertFalse((get(a,"menu") as MenuPanel).shown());assertTrue((get(a,"listPanel") as ListPanel).shown())
  repeat(2){key(a,KeyEvent.KEYCODE_DPAD_DOWN)};assertEquals(b.url,store.all()[3].url)
  val url=Shadows.shadowOf(get(a,"webView") as WebView).lastLoadedUrl
  key(a,KeyEvent.KEYCODE_DPAD_CENTER);assertFalse("Finishing move must not swallow the NEXT button's release",(get(a,"swallowedUp") as Set<*>).contains(KeyEvent.KEYCODE_DPAD_CENTER));assertNull(get(a,"movingBookmarkUrl"));assertEquals(url,Shadows.shadowOf(get(a,"webView") as WebView).lastLoadedUrl)
  assertEquals(store.all(),BookmarkStore(a).all())
 }
 @Test fun bookmarkMoveCrossesManagerPagesAndStopsAtFolderBoundaries(){
  val a=make();val store=get(a,"store") as BookmarkStore
  repeat(55){store.add("A $it","https://move.invalid/a/$it","A")};store.add("Other","https://move.invalid/b","B")
  val b=store.find("https://move.invalid/a/49")!!;call(a,"showBookmarks","A",49,b.url)
  key(a,KeyEvent.KEYCODE_DPAD_DOWN);key(a,KeyEvent.KEYCODE_DPAD_DOWN)
  assertEquals(b.url,store.inFolder("A")[51].url);assertEquals(b.url,get(a,"movingBookmarkUrl"))
  repeat(10){key(a,KeyEvent.KEYCODE_DPAD_DOWN)};assertEquals(b.url,store.inFolder("A").last().url);assertEquals("B",store.find("https://move.invalid/b")!!.folder)
  key(a,KeyEvent.KEYCODE_BACK);assertNull(get(a,"movingBookmarkUrl"));assertTrue((get(a,"listPanel") as ListPanel).shown())
 }
 @Test fun navigationDiscardsBookmarkMoveMode(){
  val a=make();val store=get(a,"store") as BookmarkStore;store.add("A","https://move.invalid/a","")
  call(a,"showBookmarks",null,0,"https://move.invalid/a");assertNotNull(get(a,"movingBookmarkUrl"))
  call(a,"openUrl","https://fixture.invalid/two");assertNull(get(a,"movingBookmarkUrl"));assertFalse((get(a,"listPanel") as ListPanel).shown())
 }
 @Test fun diagnosticDrainReturnsToIdleAndCanRestartAfterClear(){
  val app=RuntimeEnvironment.getApplication();DiagnosticLog.initialize(app)
  val flag=DiagnosticLog::class.java.getDeclaredField("flushScheduled").apply{isAccessible=true}
  fun awaitIdle(){val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(flag.getBoolean(DiagnosticLog)&&System.nanoTime()<until)Thread.sleep(10);assertFalse("No recurring idle drain",flag.getBoolean(DiagnosticLog))}
  DiagnosticLog.startNewSession(app,"first");repeat(300){DiagnosticLog.record(app,"event$it")};DiagnosticLog.stop(app);awaitIdle()
  DiagnosticLog.clear(app);DiagnosticLog.startNewSession(app,"second");DiagnosticLog.record(app,"retained_event");DiagnosticLog.stop(app);awaitIdle()
  val bytes=java.io.ByteArrayOutputStream();val done=java.util.concurrent.CountDownLatch(1);var error:Throwable?=null
  DiagnosticLog.exportAsync(app,{bytes}){error=it;done.countDown()};assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(error)
  var events="";java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use{zip->var entry=zip.nextEntry;while(entry!=null){if(entry.name=="events.jsonl")events=String(zip.readBytes(),Charsets.UTF_8);entry=zip.nextEntry}}
  assertTrue(events.contains("retained_event"));assertFalse(events.contains("\"reason\":\"first\""));awaitIdle()
 }
 @Test fun historyInvalidatesCommandsButPreservesSameDocumentBridgeAndMediaElements(){
  val a=make();val web=get(a,"webView") as WebView;val shadow=Shadows.shadowOf(web)
  shadow.pushEntryToHistory("https://fixture.invalid/one");shadow.pushEntryToHistory("https://fixture.invalid/one#search")
  val token=get(a,"documentToken");val epoch=get(a,"pageEpoch");val player=get(a,"playerIntent") as Long
  call(a,"navigateHistory",-1)
  assertNotEquals(epoch,get(a,"pageEpoch"));assertTrue((get(a,"playerIntent") as Long)>player)
  assertEquals(token,get(a,"documentToken"));assertEquals(1,shadow.goBackInvocations)
  val cleanup=ControlledWeb.scripts.last{it.contains("var media=document.querySelectorAll")}
  assertTrue(cleanup.contains("media[i].pause()"));assertFalse(cleanup.contains("media[i].remove()"));assertFalse(cleanup.contains("media[i].src=''") )
  shadow.webViewClient.onPageStarted(web,"https://fixture.invalid/other",null)
  assertNotEquals("Actual full-document navigation does rotate the bridge",token,get(a,"documentToken"))
 }
 @Test fun consumedBackAfterGoingHomeDoesNotExitFromAnEarlierBackPress(){
  // Browsing and home exit gestures must not share a stale double-Back window.
  val a=make();set(a,"lastBackAt",SystemClock.uptimeMillis());call(a,"showStartPage",false)
  assertEquals(0L,get(a,"lastBackAt"))
 }
}
