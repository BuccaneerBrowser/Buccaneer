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
  @Implementation override fun evaluateJavascript(script:String,callback:ValueCallback<String>?){
   if(script.contains("__tvUi.edit(")){commands.add(script);callbacks.add(callback)}else callback?.onReceiveValue("null")
  }
  companion object{val commands=mutableListOf<String>();val callbacks=mutableListOf<ValueCallback<String>?>()}
 }
 private val controllers=mutableListOf<ActivityController<MainActivity>>()
 @Before fun reset(){EditorShadow.commands.clear();EditorShadow.callbacks.clear()}
 @After fun cleanup(){controllers.forEach{it.pause().stop().destroy()}}
 private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 private fun set(a:MainActivity,n:String,v:Any?){MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)}
 private fun call(a:MainActivity,n:String,vararg v:Any?)=MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==v.size}.apply{isAccessible=true}.invoke(a,*v)
 private fun make():MainActivity{val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();controllers.add(c);val a=c.get();(get(a,"startPage") as StartPage).hide();report(a,"one","");return a}
 private fun report(a:MainActivity,field:String,value:String){set(a,"lastTapAt",SystemClock.uptimeMillis());set(a,"lastUserInputAt",SystemClock.elapsedRealtime());a.Bridge().editableFocused("frame",JSONObject().put("ok",1).put("doc",a.Bridge().currentDocument()).put("field",field).put("focused",1).put("v",value).toString())}
 private fun ack(a:MainActivity,n:Int,value:String,status:String="applied",field:String="one"){
  EditorShadow.callbacks[n]?.onReceiveValue(JSONObject.quote(JSONObject().put("status",status).put("ok",1).put("doc",a.Bridge().currentDocument()).put("frame","frame").put("field",field).put("v",value).toString()))
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

 @Test fun qualitySettingsOpensChoicesAndPersistsPolicyAcrossNewActivity(){
  val a=make();call(a,"showSettings",5)
  fun find(root:android.view.View,predicate:(android.widget.TextView)->Boolean):android.widget.TextView? {
   if(root is android.widget.TextView && predicate(root))return root
   if(root is android.view.ViewGroup)for(i in 0 until root.childCount){val v=find(root.getChildAt(i),predicate);if(v!=null)return v}
   return null
  }
  val menu=get(a,"menu") as MenuPanel
  val entry=find(menu){it is android.widget.Button && it.text.toString().startsWith("Preferred video quality:")}
  assertNotNull(entry);entry!!.performClick()
  val choice=find(menu){it is android.widget.Button && it.text.toString().trim().removePrefix("✓ ")=="Prefer 720p"}
  assertNotNull(choice);choice!!.performClick()
  assertEquals(720,JSONObject(a.Bridge().qualityPreference()).getInt("height"))
  val revision=JSONObject(a.Bridge().qualityPreference()).getLong("revision");assertTrue(revision>0)
  val b=make();val policy=JSONObject(b.Bridge().qualityPreference());assertEquals(720,policy.getInt("height"));assertEquals(revision,policy.getLong("revision"))
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
