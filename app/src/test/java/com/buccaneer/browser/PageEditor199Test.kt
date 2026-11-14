package com.buccaneer.browser

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowWebView
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[22,25,28],qualifiers="w960dp-h540dp-xhdpi",shadows=[PageEditor199Test.EditorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PageEditor199Test {
 @Implements(WebView::class) class EditorShadow:ShadowWebView(){
  @Implementation override fun evaluateJavascript(script:String, callback:ValueCallback<String>?){
   if(script.contains("__fb.unmute()")){unmuteCallbacks+=callback;return}
   if(script.contains("__fb.muteButton()")){muteProbes++;return}
   if(script.contains("__tvUi.edit(")){commands.add(script);callbacks.add(callback)}else callback?.onReceiveValue("null")
  }
  companion object{val commands=mutableListOf<String>();val callbacks=mutableListOf<ValueCallback<String>?>();val unmuteCallbacks=mutableListOf<ValueCallback<String>?>();var muteProbes=0}
 }
 private val controllers=mutableListOf<ActivityController<MainActivity>>()
 @Before fun reset(){EditorShadow.commands.clear();EditorShadow.callbacks.clear();EditorShadow.unmuteCallbacks.clear();EditorShadow.muteProbes=0}
 @After fun cleanup(){controllers.forEach{it.pause().stop().destroy()}}
 private fun get(a:MainActivity,n:String)=TestAccess.get(a,n)
 private fun set(a:MainActivity,n:String,v:Any?){TestAccess.set(a,n,v)}
 private fun call(a:MainActivity,n:String,vararg v:Any?)=MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==v.size}.apply{isAccessible=true}.invoke(a,*v)
 private fun make():MainActivity{val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();controllers.add(c);val a=c.get();(get(a,"startPage") as StartPage).hide();report(a,"one","");return a}
 private fun report(a:MainActivity,field:String,value:String){set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime());a.Bridge().editableFocused("frame",JSONObject().put("ok",1).put("doc",a.Bridge().currentDocument()).put("field",field).put("focused",1).put("v",value).toString())}
 private fun ack(a:MainActivity,n:Int,value:String,status:String="applied",field:String="one"){
  EditorShadow.callbacks[n]?.onReceiveValue(JSONObject.quote(JSONObject().put("status",status).put("ok",1).put("doc",a.Bridge().currentDocument()).put("frame","frame").put("field",field).put("v",value).toString()))
 }
 @Test fun bothMuteRoutesRejectProbeAfterKeyboardTakesFocus(){
  for(route in listOf("onBrowseKey","onFullscreenKey")){
   val a=make();call(a,"closeKeypad");EditorShadow.unmuteCallbacks.clear();EditorShadow.muteProbes=0
   call(a,route,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_MUTE))
   assertEquals(1,EditorShadow.unmuteCallbacks.size);call(a,"promptUrl")
   EditorShadow.unmuteCallbacks.single()?.onReceiveValue(JSONObject.quote("none"))
   assertEquals(0,EditorShadow.muteProbes)
  }
 }
 @Test fun bothMuteRoutesTreatFrameAsSuccessWithoutAnotherProbe(){
  for(route in listOf("onBrowseKey","onFullscreenKey")){
   val a=make();call(a,"closeKeypad");EditorShadow.unmuteCallbacks.clear();EditorShadow.muteProbes=0
   call(a,route,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_MUTE))
   assertEquals(1,EditorShadow.unmuteCallbacks.size)
   EditorShadow.unmuteCallbacks.single()?.onReceiveValue(JSONObject.quote("frame"))
   assertEquals(0,EditorShadow.muteProbes)
  }
 }
 @Test fun keyboardSubmitDoesNotChangeVideoOwnershipOrTransportIntent(){
  val a=make();val owner=a.Bridge().currentDocument()+":video";a.Bridge().mediaStarted(owner)
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(owner,a.Bridge().activeOwner());val intent=get(a,"playerIntent")
  call(a,"typeText","x");call(a,"typeGo");ack(a,0,"x");ack(a,1,"x","submitted")
  assertEquals(owner,a.Bridge().activeOwner());assertEquals(intent,get(a,"playerIntent"))
 }
 @Test fun mediaReportsCannotMutateAnActiveEditorQueue(){
  val a=make();call(a,"typeText","pending")
  val session=get(a,"editorSession");val frame=get(a,"inputFrame");val pending=get(a,"pageEdits")
  val owner=a.Bridge().currentDocument()+":video";a.Bridge().mediaStarted(owner);a.Bridge().mediaStopped(owner)
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(session,get(a,"editorSession"));assertEquals(frame,get(a,"inputFrame"));assertSame(pending,get(a,"pageEdits"))
  assertEquals(1,EditorShadow.commands.size);ack(a,0,"pending");assertEquals("pending",(get(a,"buffer") as StringBuilder).toString())
 }
 @Test fun typingDeletionAndSubmitAreOrderedAndDoNotCloseBeforeCommit(){
  val a=make();call(a,"typeText","a");call(a,"typeText","b");call(a,"typeBackspace");call(a,"typeGo")
  assertEquals(1,EditorShadow.commands.size);assertTrue((get(a,"keypad") as Keypad).shown())
  ack(a,0,"a");assertEquals(2,EditorShadow.commands.size);assertTrue(EditorShadow.commands[1].contains("\"insert\",\"b\""))
  ack(a,1,"ab");assertEquals(3,EditorShadow.commands.size);assertTrue(EditorShadow.commands[2].contains("\"delete\""))
  ack(a,2,"a");assertEquals(4,EditorShadow.commands.size);assertTrue(EditorShadow.commands[3].contains("\"submit\""));assertTrue((get(a,"keypad") as Keypad).shown())
  ack(a,3,"a","submitted");assertFalse((get(a,"keypad") as Keypad).shown())
 }
 @Test fun timeoutNeverReplaysAnUncertainEditOrSubmitsTheWrongText(){
  val a=make();call(a,"typeText","lost");call(a,"typeGo");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2200))
  assertEquals(1,EditorShadow.commands.size);assertTrue((get(a,"keypad") as Keypad).shown());assertTrue((get(a,"pageEdits") as java.util.ArrayDeque<*>).isEmpty())
 }
 @Test fun replacementFieldInvalidatesOldQueueAndOldAcknowledgement(){
  val a=make();call(a,"typeText","A");call(a,"typeGo");report(a,"two","B");ack(a,0,"stale A")
  assertEquals("two",get(a,"inputFieldId"));assertEquals(1,EditorShadow.commands.size);assertEquals("B",JSONObject(get(a,"inputFieldState") as String).getString("v"))
  call(a,"typeText","C");assertEquals(2,EditorShadow.commands.size);assertTrue(EditorShadow.commands[1].contains("\"two\""))
 }
 @Test fun rejectedEditDoesNotTurnPreviewBufferIntoAClaimOfTypedText(){
  val a=make();call(a,"typeText","not typed");call(a,"typeGo");ack(a,0,"","blocked")
  assertEquals("",(get(a,"buffer") as StringBuilder).toString());assertEquals(1,EditorShadow.commands.size);assertTrue((get(a,"keypad") as Keypad).shown())
 }
 @Test fun pauseCancelsPendingTypingAndSubmission(){val a=make();call(a,"typeText","a");call(a,"typeGo");controllers.last().pause();ack(a,0,"a");assertEquals(1,EditorShadow.commands.size);controllers.last().resume();assertTrue((get(a,"keypad") as Keypad).shown())}

 @Test fun searchHandlerBlurClosesOnlyAnAlreadyDispatchedSubmit(){
  val a=make();call(a,"typeText","query");call(a,"typeGo");ack(a,0,"query")
  assertEquals(2,EditorShadow.commands.size)
  a.Bridge().editableBlurred("frame",a.Bridge().currentDocument(),"one")
  assertFalse((get(a,"keypad") as Keypad).shown());ack(a,1,"query","submitted")
  assertFalse((get(a,"keypad") as Keypad).shown())
 }
 @Test fun blurDuringTypingDoesNotSubmitOrApplyRemainingQueue(){
  val a=make();call(a,"typeText","query");call(a,"typeGo")
  a.Bridge().editableBlurred("frame",a.Bridge().currentDocument(),"one");ack(a,0,"query")
  assertEquals(1,EditorShadow.commands.size);assertTrue((get(a,"keypad") as Keypad).shown())
 }

 @Test fun qualityPreferenceIsPageLocalAndHasNoNativeSettingsEntry(){
  // The former test described an unimplemented native quality menu/Bridge method.
  // Actual quality policy is page-local JS; retain coverage for that contract instead.
  val a=make()
  val script=a.assets.open("page_helpers.js").bufferedReader().use { it.readText() }
  assertTrue(script.contains("var qualityPreference = [480, 720, 1080]"))
  assertTrue(script.contains("qualityPreference = Array.isArray(order) ? order.slice() : [480, 720, 1080]"))
  assertFalse(a.Bridge().javaClass.methods.any { it.name == "qualityPreference" })
 }

 private class NativeWeb(context:Context):WebView(context){
  val editor=EditText(context)
  var requestedAction=-1
  init{editor.inputType=InputType.TYPE_CLASS_TEXT;editor.setSingleLine(true);editor.imeOptions=EditorInfo.IME_ACTION_SEARCH;editor.setOnEditorActionListener{_,action,_->requestedAction=action;true}}
  override fun onCreateInputConnection(info:EditorInfo):InputConnection?=editor.onCreateInputConnection(info)
 }
 @Test fun nativeConnectionEditsRealEditableAndPreservesSelection(){
  val context=RuntimeEnvironment.getApplication();val web=NativeWeb(context);web.editor.setText("hello world");web.editor.setSelection(6,11)
  assertTrue(NativePageEditor.apply(web,"insert","search"));assertEquals("hello search",web.editor.text.toString())
  assertTrue(NativePageEditor.apply(web,"delete",""));assertEquals("hello searc",web.editor.text.toString())
  assertTrue(NativePageEditor.apply(web,"clear",""));assertEquals("",web.editor.text.toString());web.destroy()
 }
 @Test fun nativeDeleteRemovesSelectionOrWholeUnicodeCharacter(){
  val web=NativeWeb(RuntimeEnvironment.getApplication());web.editor.setText("A😀");web.editor.setSelection(web.editor.length())
  assertTrue(NativePageEditor.apply(web,"delete",""));assertEquals("A",web.editor.text.toString());web.editor.setText("selected");web.editor.selectAll();assertTrue(NativePageEditor.apply(web,"delete",""));assertEquals("",web.editor.text.toString());web.destroy()
 }
 @Test fun nativeSubmitUsesDeclaredSearchActionNotBlindViewKeyInjection(){
  val web=NativeWeb(RuntimeEnvironment.getApplication());assertTrue(NativePageEditor.apply(web,"submit",""));assertEquals(EditorInfo.IME_ACTION_SEARCH,web.requestedAction);web.destroy()
 }
 @Test fun nativeDispatchIsAsynchronousAndCancellationPreventsQueuedTyping(){
  val web=NativeWeb(RuntimeEnvironment.getApplication());var result:Boolean?=null
  val cancel=NativePageEditor.dispatch(web,"insert","cancelled"){result=it}
  assertEquals("",web.editor.text.toString());cancel();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals("",web.editor.text.toString());assertNull(result)
  NativePageEditor.dispatch(web,"insert","accepted"){result=it}
  Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("accepted",web.editor.text.toString());assertEquals(true,result);web.destroy()
 }
 @Test @Config(sdk=[25,28]) fun nativeDispatchUsesInputConnectionsRequestedHandler(){
  val thread=android.os.HandlerThread("test-input-owner");thread.start()
  try {
   val handler=android.os.Handler(thread.looper);val called=java.util.concurrent.CountDownLatch(1)
   var owner:Thread?=null;var accepted:Boolean?=null
   val connection=java.lang.reflect.Proxy.newProxyInstance(InputConnection::class.java.classLoader,arrayOf(InputConnection::class.java)){_,method,_->
    when(method.name){"getHandler"->handler;"commitText"->{owner=Thread.currentThread();called.countDown();true};else->if(method.returnType==Boolean::class.javaPrimitiveType)false else null}
   } as InputConnection
   val web=object:WebView(RuntimeEnvironment.getApplication()){
    override fun onCreateInputConnection(info:EditorInfo):InputConnection { info.inputType=InputType.TYPE_CLASS_TEXT;return connection }
   }
   NativePageEditor.dispatch(web,"insert","threaded"){accepted=it}
   Shadows.shadowOf(thread.looper).idle()
   assertTrue(called.await(2,java.util.concurrent.TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle()
   assertSame(thread,owner);assertEquals(true,accepted);web.destroy()
  }finally{thread.quitSafely()}
 }

}
