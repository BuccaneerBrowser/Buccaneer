package com.buccaneer.browser
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h720dp-mdpi",shadows=[Regression171Test.ScriptWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Api34IntegrationSmokeTest {
 class ReadProvider(val backup:File):ContentProvider(){
  override fun onCreate()=true
  override fun getType(uri:Uri)="application/json"
  override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
  override fun insert(uri:Uri,v:ContentValues?):Uri?=null
  override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
  override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
  override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(backup,ParcelFileDescriptor.MODE_READ_ONLY)
 }
 private fun get(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
 @Test fun api34StartupTransportAndPickerReturnImportWorkTogether(){
  val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get()
  try{
   assertTrue((get(a,"startPage") as StartPage).shown());assertNotNull(get(a,"webView") as WebView)
   (get(a,"startPage") as StartPage).hide();Regression171Test.ScriptWebView.mediaResult="ok";Regression171Test.ScriptWebView.scripts.clear()
   a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
   assertTrue(Regression171Test.ScriptWebView.scripts.any{it.contains("__fb.togglePlay()")})
   val file=File(a.cacheDir,"picker-fixture.json");file.writeText("{\"items\":[{\"u\":\"https://restored.test/\",\"t\":\"Restored\"}]}")
   val provider=ReadProvider(file);provider.attachInfo(a.applicationContext,ProviderInfo().apply{authority="backup-fixture";exported=true});ShadowContentResolver.registerProviderInternal("backup-fixture",provider)
   c.pause()
   MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}
    .invoke(a,1206,Activity.RESULT_OK,Intent().setData(Uri.parse("content://backup-fixture/bookmarks.json")))
   c.resume()
   val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(BookmarkJobs.isBusy()&&System.nanoTime()<deadline){Thread.sleep(5);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))}
   assertFalse(BookmarkJobs.isBusy());assertEquals("https://restored.test/",(get(a,"store") as BookmarkStore).all().single().url)
   assertTrue((get(a,"listPanel") as ListPanel).shown());assertFalse((get(a,"menu") as MenuPanel).shown());assertFalse((get(a,"keypad") as Keypad).shown())
  }finally{c.pause().stop().destroy()}
 }
}
