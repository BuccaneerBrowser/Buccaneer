package com.buccaneer.browser

import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], qualifiers = "w1280dp-h720dp-mdpi", shadows = [NativeDialogGuardTest.JsResultShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeDialogGuardTest {
    @Implements(JsResult::class)
    class JsResultShadow {
        @RealObject private lateinit var realResult: JsResult

        @Implementation
        protected fun cancel() {
            JsResult::class.java.getDeclaredField("mResult").apply { isAccessible = true }
                .setBoolean(realResult, false)
            val receiver = JsResult::class.java.getDeclaredField("mReceiver").apply { isAccessible = true }
                .get(realResult)
            receiver.javaClass.interfaces.single { it.name == "android.webkit.JsResult\$ResultReceiver" }
                .getMethod("onJsResultComplete", JsResult::class.java)
                .invoke(receiver, realResult)
        }
    }

    private data class Completion(
        var callbackActive: Boolean = false,
        var count: Int = 0,
        var accepted: Boolean? = null,
        var synchronous: Boolean = false
    )

    @Before
    fun enableGuardPreference() {
        val guard = PopupGuard(org.robolectric.RuntimeEnvironment.getApplication())
        if (!guard.enabled) guard.toggle()
    }

    private fun result(completion: Completion): JsResult {
        val receiverType = Class.forName("android.webkit.JsResult\$ResultReceiver")
        val receiver = Proxy.newProxyInstance(receiverType.classLoader, arrayOf(receiverType)) { _, method, args ->
            if (method.name == "onJsResultComplete") {
                val completed = args!![0] as JsResult
                completion.count++
                completion.accepted = JsResult::class.java.getDeclaredField("mResult")
                    .apply { isAccessible = true }.getBoolean(completed)
                completion.synchronous = completion.callbackActive
            }
            null
        }
        return JsResult::class.java.getDeclaredConstructor(receiverType)
            .apply { isAccessible = true }.newInstance(receiver)
    }

    private fun promptResult(completion: Completion): JsPromptResult {
        val receiverType = Class.forName("android.webkit.JsResult\$ResultReceiver")
        val receiver = Proxy.newProxyInstance(receiverType.classLoader, arrayOf(receiverType)) { _, method, _ ->
            if (method.name == "onJsResultComplete") completion.count++
            null
        }
        return JsPromptResult::class.java.getDeclaredConstructor(receiverType)
            .apply { isAccessible = true }.newInstance(receiver)
    }

    private fun fixture(block: (MainActivity, WebView) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val web = TestAccess.get(activity, "webView") as WebView
            (TestAccess.get(activity, "startPage") as StartPage).hide()
            block(activity, web)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun guardedAlertConfirmsExactlyOnceSynchronously() = fixture { _, web ->
        val completion = Completion(callbackActive = true)
        val handled = Shadows.shadowOf(web).webChromeClient.onJsAlert(
            web, "https://example.test/", "message must not matter", result(completion)
        )
        completion.callbackActive = false

        assertTrue(handled)
        assertEquals(1, completion.count)
        assertEquals(true, completion.accepted)
        assertTrue(completion.synchronous)
    }

    @Test
    fun guardedConfirmCancelsExactlyOnceSynchronously() = fixture { _, web ->
        val completion = Completion(callbackActive = true)
        val handled = Shadows.shadowOf(web).webChromeClient.onJsConfirm(
            web, "https://example.test/", "message must not matter", result(completion)
        )
        completion.callbackActive = false

        assertTrue(handled)
        assertEquals(1, completion.count)
        assertEquals(false, completion.accepted)
        assertTrue(completion.synchronous)
    }

    @Test
    fun guardOffLeavesAlertAndConfirmToFramework() = fixture { activity, web ->
        val guard = TestAccess.get(activity, "guard") as PopupGuard
        if (guard.enabled) guard.toggle()
        val alert = Completion(callbackActive = true)
        val confirm = Completion(callbackActive = true)

        assertFalse(Shadows.shadowOf(web).webChromeClient.onJsAlert(web, "https://example.test/", "alert", result(alert)))
        assertFalse(Shadows.shadowOf(web).webChromeClient.onJsConfirm(web, "https://example.test/", "confirm", result(confirm)))
        alert.callbackActive = false
        confirm.callbackActive = false
        assertEquals(0, alert.count)
        assertEquals(0, confirm.count)
    }

    @Test
    fun nullResultsRemainFrameworkHandled() = fixture { _, web ->
        assertFalse(Shadows.shadowOf(web).webChromeClient.onJsAlert(web, "https://example.test/", "alert", null))
        assertFalse(Shadows.shadowOf(web).webChromeClient.onJsConfirm(web, "https://example.test/", "confirm", null))
    }

    @Test
    fun promptAndBeforeUnloadBehaviorIsPreserved() = fixture { activity, web ->
        val prompt = Completion()
        assertTrue(
            Shadows.shadowOf(web).webChromeClient.onJsPrompt(
                web, "https://example.test/", "Question", "default", promptResult(prompt)
            )
        )
        assertTrue((TestAccess.get(activity, "keypad") as Keypad).shown())
        assertEquals(0, prompt.count)

        val beforeUnload = Completion(callbackActive = true)
        assertFalse(
            Shadows.shadowOf(web).webChromeClient.onJsBeforeUnload(
                web, "https://example.test/", "Leave?", result(beforeUnload)
            )
        )
        beforeUnload.callbackActive = false
        assertEquals(0, beforeUnload.count)
    }
}
