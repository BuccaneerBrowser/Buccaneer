package com.buccaneer.browser

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AuditTest {
    private fun store(): BookmarkStore {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("bookmarks",0).edit().clear().commit()
        return BookmarkStore(app)
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun text(view: View): List<String> = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun arrow(view: View, next: Boolean): View? = descendants(view).firstOrNull {
        it.contentDescription?.toString()==if(next) "Next bookmark page" else "Previous bookmark page"
    }
    @Test fun bookmarksPageEightAtATimeAndReturnToFirst() {
        val app=RuntimeEnvironment.getApplication();val store=store()
        repeat(21){store.add("B${it+1}","https://example.com/${it+1}","")}
        val page=StartPage(app,Ui(app),store);page.show()
        assertTrue(text(page).contains("B1"));assertTrue(text(page).contains("B8"));assertFalse(text(page).contains("B9"))
        assertFalse(arrow(page,false)!!.isEnabled)
        fun layout() { page.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));page.layout(0,0,1280,720) }
        layout()
        val firstPagerTop=(arrow(page,true)!!.parent as View).top
        arrow(page,true)!!.performClick();assertTrue(text(page).contains("B9"));assertTrue(text(page).contains("B16"));assertFalse(text(page).contains("B17"))
        arrow(page,true)!!.performClick();assertTrue(text(page).contains("B17"));assertTrue(text(page).contains("B21"));assertFalse(arrow(page,true)!!.isEnabled)
        layout();assertEquals(firstPagerTop,(arrow(page,true)!!.parent as View).top)
        val laidOut=(page as ViewGroup).getChildAt(0) as ViewGroup
        assertTrue(laidOut.getChildAt(laidOut.childCount-1).bottom<=720)
        arrow(page,false)!!.performClick();arrow(page,false)!!.performClick();assertTrue(text(page).contains("B1"))
        assertTrue(text(page).any{it.startsWith("Where's me buccaneers?")})
        // The pager is before the bottom action row, not under it.
        val content=(page as ViewGroup).getChildAt(0) as ViewGroup
        val pagerParent=arrow(page,true)!!.parent as View
        val search=text(page).indexOf("Search / address");assertTrue(search>=0)
        val searchButton=descendants(page).filterIsInstance<TextView>().first{it.text.toString()=="Search / address"}
        assertTrue(content.indexOfChild(pagerParent)<content.indexOfChild(searchButton.parent as View))
    }
    @Test fun arrowsHiddenForZeroOrEightAndPageClampedAfterDeletion() {
        val app=RuntimeEnvironment.getApplication();val store=store();val page=StartPage(app,Ui(app),store)
        page.show();assertNull(arrow(page,true))
        repeat(8){store.add("B${it+1}","https://example.com/${it+1}","")};page.refresh();assertNull(arrow(page,true))
        repeat(9){store.add("B${it+9}","https://example.com/${it+9}","")};page.refresh()
        arrow(page,true)!!.performClick();arrow(page,true)!!.performClick()
        store.remove("https://example.com/17");page.refresh()
        assertTrue(text(page).contains("B9"));assertFalse(text(page).contains("B17"));assertFalse(arrow(page,true)!!.isEnabled)
    }
    @Test fun folderAndFolderContentsBothPageAndBackRestoresFolderPage() {
        val app=RuntimeEnvironment.getApplication();val store=store()
        repeat(9){store.add("B${it+1}","https://example.com/${it+1}","F${it+1}")}
        repeat(9){store.add("Extra${it+1}","https://example.com/extra${it+1}","F9")}
        val page=StartPage(app,Ui(app),store);page.show();arrow(page,true)!!.performClick()
        val folderLabel=descendants(page).filterIsInstance<TextView>().first{it.text.toString()=="F9"}
        (folderLabel.parent as View).performClick();assertTrue(text(page).contains("B9"));assertTrue(arrow(page,true)!!.isEnabled)
        arrow(page,true)!!.performClick();assertTrue(text(page).contains("Extra8"));assertTrue(text(page).contains("Extra9"))
        assertTrue(page.back());assertTrue(text(page).contains("F9"));assertTrue(text(page).contains("2 / 2"))
    }
    @Test fun bookmarkPathsKeepCaseAndMalformedImportDoesNotReplaceExistingList() {
        val store=store()
        assertTrue(BookmarkStore.sameUrl("HTTPS://EXAMPLE.COM:443", "https://example.com/"))
        assertFalse(BookmarkStore.sameUrl("https://example.com/Show", "https://example.com/show"))
        assertFalse(BookmarkStore.sameUrl("https://example.com/?q=A", "https://example.com/?q=a"))
        store.add("Original","https://example.com/Show","")
        assertFalse(store.importJson("{\"items\":[{\"u\":123}]}"));assertEquals("Original",store.all().single().title)
        assertFalse(store.importJson("{\"items\":[{\"u\":\"https://x.test/\",\"f\":123}]}"));assertEquals(1,store.all().size)
        assertTrue(store.importJson("{\"items\":[{\"u\":\"https://EXAMPLE.com/\"},{\"u\":\"https://example.com/\"}]}"));assertEquals(1,store.all().size)
    }
    @Test fun keypadGetsKeysEvenWhenFullscreenViewExists() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val activity=controller.get()
        MainActivity::class.java.getDeclaredField("customView").apply{isAccessible=true}.set(activity,View(activity))
        val keypad=MainActivity::class.java.getDeclaredField("keypad").apply{isAccessible=true}.get(activity) as Keypad
        keypad.open("Test","OK")
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
        val col=Keypad::class.java.getDeclaredField("selCol").apply{isAccessible=true}.getInt(keypad)
        assertEquals(1,col)
        controller.pause().stop().destroy()
    }
    @Test fun invalidProgressDoesNotReachDrawingCode() {
        val bar=BarView(RuntimeEnvironment.getApplication(),0,0)
        bar.fraction=Float.NaN;assertEquals(0f,bar.fraction,0f)
        bar.fraction=Float.POSITIVE_INFINITY;assertEquals(1f,bar.fraction,0f)
    }
    @Test fun nestedDiagnosticArraysRedactSecretsAndOversizePayloads() {
        val app=RuntimeEnvironment.getApplication();DiagnosticLog.initialize(app);DiagnosticLog.startNewSession(app,"audit")
        val data=JSONObject().put("rows",JSONArray().put(JSONObject().put("url","https://x.test/video?token=private-value")
            .put("password","private-value")).put(JSONArray().put("https://x.test/?key=private-value")))
        DiagnosticLog.record(app,"audit_array",data)
        DiagnosticLog.recordCritical(app,"audit_large",JSONObject().put("password","private-value").put("big","x".repeat(40000)))
        DiagnosticLog.stop(app)
        val bytes=ByteArrayOutputStream();val done=CountDownLatch(1);var failure:Throwable?=null
        DiagnosticLog.exportAsync(app,{bytes}){failure=it;done.countDown()};assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(failure)
        ZipInputStream(bytes.toByteArray().inputStream()).use{zip->while(true){zip.nextEntry?:break;val content=zip.readBytes().toString(Charsets.UTF_8);assertFalse(content.contains("private-value"))}}
        DiagnosticLog.clear(app)
    }
}
