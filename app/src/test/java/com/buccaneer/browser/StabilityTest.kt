package com.buccaneer.browser

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StabilityTest {
    @Test fun twentyMinuteIdlePolicyProtectsPlaybackAndResetsOnInput() {
        val policy = IdleDimPolicy()
        policy.activity(100L)
        assertFalse(policy.shouldDim(100L + IdleDimPolicy.TIMEOUT_MS - 1L, false))
        assertTrue(policy.shouldDim(100L + IdleDimPolicy.TIMEOUT_MS, false))
        assertFalse(policy.shouldDim(100L + IdleDimPolicy.TIMEOUT_MS, true))
        assertFalse(policy.shouldDim(100L + 2L * IdleDimPolicy.TIMEOUT_MS - 1L, false))
        assertTrue(policy.shouldDim(100L + 2L * IdleDimPolicy.TIMEOUT_MS, false))
        policy.activity(100L + 2L * IdleDimPolicy.TIMEOUT_MS)
        assertFalse(policy.shouldDim(101L + 2L * IdleDimPolicy.TIMEOUT_MS, false))
    }

    @Test fun dimOverlayWakesWithoutActivatingFirstKey() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        val policy = MainActivity::class.java.getDeclaredField("idlePolicy").apply { isAccessible = true }.get(activity) as IdleDimPolicy
        policy.activity(android.os.SystemClock.elapsedRealtime() - IdleDimPolicy.TIMEOUT_MS)
        MainActivity::class.java.getDeclaredMethod("updateIdle", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        val overlay = MainActivity::class.java.getDeclaredField("dimOverlay").apply { isAccessible = true }.get(activity) as android.view.View
        assertEquals(android.view.View.VISIBLE, overlay.visibility)
        val menu = MainActivity::class.java.getDeclaredField("menu").apply { isAccessible = true }.get(activity) as MenuPanel
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MENU))
        assertEquals(android.view.View.GONE, overlay.visibility)
        assertFalse(menu.shown())
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MENU))
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MENU))
        assertTrue(menu.shown())
        controller.pause().stop().destroy()
    }

    @Test fun appQwertyNeverBlindlyInjectsTextIntoAFullscreenView() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        val keys = ArrayList<Int>()
        val target = object : android.view.View(activity) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) keys.add(event.keyCode)
                return true
            }
        }
        MainActivity::class.java.getDeclaredField("customView").apply { isAccessible = true }.set(activity, target)
        MainActivity::class.java.getDeclaredMethod("openPageKeypad").apply { isAccessible = true }.invoke(activity)
        MainActivity::class.java.getDeclaredMethod("typeText", String::class.java).apply { isAccessible = true }.invoke(activity, "abc")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("Text belongs to Chromium's focused InputConnection, not a fullscreen View", keys.isEmpty())
        assertEquals("", (MainActivity::class.java.getDeclaredField("buffer").apply { isAccessible = true }.get(activity) as StringBuilder).toString())
        assertFalse(TvWebView::class.java.declaredFields.any { it.name == "editor" })
        controller.pause().stop().destroy()
    }

    @Test fun chromiumEditorIsNotDisabledToHideSystemIme() {
        assertFalse(TvWebView::class.java.declaredMethods.any { it.name == "onCreateInputConnection" || it.name == "onCheckIsTextEditor" })
    }

    @Test fun retryBudgetDoesNotDependOnPageFinished() {
        val start = 1_000_000L
        assertEquals(1, CrashRecoveryPolicy.nextCount("a", null, 0, 0, start))
        assertEquals(2, CrashRecoveryPolicy.nextCount("a", "a", start, 1, start + 10_000))
        assertEquals(3, CrashRecoveryPolicy.nextCount("a", "a", start, 2, start + 20_000))
        assertEquals(3, CrashRecoveryPolicy.nextCount("b", "a", start, 2, start + 30_000))
        assertEquals(1, CrashRecoveryPolicy.nextCount("a", "a", start, 2, start + 600_001))
        assertTrue(CrashRecoveryPolicy.expired(start, start - 1))
        assertFalse(CrashRecoveryPolicy.expired(start, start + 600_000))
    }

    @Suppress("DEPRECATION") // Required framework callback fixture, never shipped in the APK.
    @Test fun rendererLossDestroysViewAndDoesNotResetRetryBudgetOnFinish() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        val viewField = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
        val view = viewField.get(activity) as WebView
        val client = Shadows.shadowOf(view).webViewClient
        val url = "https://example.com/test"
        client.onPageStarted(view, url, null)
        client.onPageFinished(view, url)
        client.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true
            override fun rendererPriorityAtExit(): Int = WebView.RENDERER_PRIORITY_IMPORTANT
        })
        val destroyed = MainActivity::class.java.getDeclaredField("webViewDestroyed").apply { isAccessible = true }
        assertEquals(true, destroyed.get(activity))
        assertNull(view.parent)
        val prefs = activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        assertEquals(1, prefs.getInt("crashReloadCount", 0))
        // Late callbacks from the destroyed WebView must be ignored.
        client.onPageFinished(view, url)
        assertEquals(1, prefs.getInt("crashReloadCount", 0))
        // Exercise lifecycle teardown before the queued recreate; must not touch the dead view.
        controller.pause().stop().destroy()
    }

    @Test fun crashHandlerInstallsOnlyOnce() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val app = RuntimeEnvironment.getApplication()
            CrashLog.install(app)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            CrashLog.install(app)
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun diagnosticQueueIsBoundedAndExportsRoutineAndCriticalData() {
        val app = RuntimeEnvironment.getApplication()
        DiagnosticLog.initialize(app)
        DiagnosticLog.startNewSession(app, "test")
        val executorField = DiagnosticLog::class.java.getDeclaredField("writer").apply { isAccessible = true }
        val executor = executorField.get(null) as ScheduledExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            repeat(1000) { DiagnosticLog.record(app, "load_$it", JSONObject().put("number", it)) }
            val queueField = DiagnosticLog::class.java.getDeclaredField("pending").apply { isAccessible = true }
            val queue = queueField.get(null) as ArrayBlockingQueue<*>
            assertTrue(queue.size <= 256)
            assertTrue(queue.size >= 240)
            // Crash persistence must not wait behind the deliberately blocked routine writer.
            DiagnosticLog.recordCritical(app, "test_crash", JSONObject().put("url", "https://example.com/video?token=secret#fragment"))
            val critical = java.io.File(app.filesDir, "diagnostic_critical.jsonl").readText()
            assertTrue(critical.contains("test_crash"))
            assertFalse(critical.contains("token=secret"))
        } finally { release.countDown() }
        val bytes = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        DiagnosticLog.stop(app)
        DiagnosticLog.exportAsync(app, { bytes }) { failure = it; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(failure)
        assertFalse(DiagnosticLog.isEnabled(app))
        val entries = HashMap<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.getValue("events.jsonl").contains("events_dropped"))
        assertTrue(entries.getValue("events.jsonl").contains("session_stop"))
        assertTrue(entries.getValue("critical.jsonl").contains("test_crash"))
        // Repeated resource errors are summarized rather than logged one-per-request.
        DiagnosticLog.startNewSession(app, "burst")
        repeat(20) { DiagnosticLog.record(app, "subresource_error", JSONObject().put("code", -2)) }
        DiagnosticLog.stop(app)
        val burstBytes = ByteArrayOutputStream(); val burstDone = CountDownLatch(1)
        DiagnosticLog.exportAsync(app, { burstBytes }) { failure = it; burstDone.countDown() }
        assertTrue(burstDone.await(10, TimeUnit.SECONDS)); assertNull(failure)
        ZipInputStream(burstBytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val text = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name == "events.jsonl") {
                    assertTrue(text.contains("event_burst")); assertTrue(text.contains("additionalCount"))
                    assertFalse(text.contains("load_999"))
                }
                if (entry.name == "critical.jsonl") assertFalse(text.contains("test_crash"))
            }
        }
        DiagnosticLog.clear(app)
    }
}
