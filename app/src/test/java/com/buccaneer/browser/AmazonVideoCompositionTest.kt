package com.buccaneer.browser

import org.junit.Assert.*
import org.junit.Test

/** JVM policy contract. Reflection lets the missing feature fail as an assertion, not compile noise. */
class AmazonVideoCompositionTest {
    private fun configure(beforeNative: Boolean, has: () -> Boolean, remove: () -> Unit, restore: () -> Unit): String {
        val method = AmazonVideoComposition::class.java.methods.firstOrNull { it.name == "configure" }
        assertNotNull("Missing fail-closed command-line configuration seam", method)
        return method!!.invoke(AmazonVideoComposition, beforeNative, has, remove, restore) as String
    }

    @Test fun removesOnlyTheForcedOverlaySwitchAndVerifiesReadBack() {
        var forced = true
        var removes = 0
        val result = configure(true, { forced }, { forced = false; removes++ }, { forced = true })
        assertEquals("removed", result)
        assertFalse(forced)
        assertEquals(1, removes)
    }

    @Test fun lateStartupNeverMutatesNativeCommandLine() {
        assertEquals("skipped", configure(false, { true }, { fail("late remove") }, { fail("late restore") }))
    }

    @Test fun absentSwitchIsIdempotentWithoutAnyMutation() {
        assertEquals("skipped", configure(true, { false }, { fail("absent remove") }, { fail("absent restore") }))
    }

    @Test fun failedRemovalRestoresTheOriginalFlag() {
        var forced = true
        var restores = 0
        val result = configure(true, { forced }, { forced = false; throw IllegalStateException("reflection failure") },
            { forced = true; restores++ })
        assertEquals("failed", result)
        assertTrue(forced)
        assertEquals(1, restores)
    }

    @Test fun unavailableReadBackFailsClosedWithoutMutation() {
        assertEquals("failed", configure(true, { throw IllegalStateException("unavailable") },
            { fail("remove after failed read") }, { fail("restore before mutation") }))
    }

    @Test fun unsupportedProviderDevicesAreNoOp() {
        val model = "AFTMM"
        val provider = "com.amazon.webview.chromium"
        val version = "118.amazon-webview-v118-5993-tv.5993.155.160"
        for (other in listOf("AFTM", "AFTSSS", "Pixel", "aftmm", ""))
            assertFalse(AmazonVideoComposition.eligible(other, 25, provider, 61446232, version))
        for (api in listOf(22, 24, 26, 34))
            assertFalse(AmazonVideoComposition.eligible(model, api, provider, 61446232, version))
        assertFalse(AmazonVideoComposition.eligible(model, 25, "com.google.android.webview", 61446232, version))
        assertFalse(AmazonVideoComposition.eligible(model, 25, provider, 61446233, version))
        assertFalse(AmazonVideoComposition.eligible(model, 25, provider, 61446232, version + ".new"))
    }

    @Test fun startupRunsBeforeFirstWebViewConstruction() {
        val source = sequenceOf(java.io.File("src/main/java/com/buccaneer/browser/MainActivity.kt"),
            java.io.File("app/src/main/java/com/buccaneer/browser/MainActivity.kt")).first { it.isFile }.readText()
        val applyAt = source.indexOf("AmazonVideoComposition.prepare()")
        assertTrue("Provider override must precede diagnostics' getDefaultUserAgent native initializer",
            applyAt >= 0 && applyAt < source.indexOf("DiagnosticLog.initialize(this)"))
        assertTrue("Provider override must run before first WebView constructor",
            applyAt >= 0 && applyAt < source.indexOf("webView = TvWebView(this)"))
    }

    @Test fun nativeCommandLineMayExistBeforeBrowserStartupButStartedBrowserIsRejected() {
        val method = AmazonVideoComposition::class.java.methods.firstOrNull { it.name == "beforeBrowserStartup" }
        assertNotNull("Guard must use vendor AwInit lifecycle, not JavaCommandLine identity", method)
        assertEquals(true, method!!.invoke(AmazonVideoComposition, 0))
        for (state in listOf(-1, 1, 2, 3, Int.MAX_VALUE))
            assertEquals(false, method.invoke(AmazonVideoComposition, state))
    }

    @Test fun exactProviderDeviceCanOptOutOfForcedOverlaysBeforeNativeStartup() {
        val cls = try { Class.forName("com.buccaneer.browser.AmazonVideoComposition") }
            catch (_: ClassNotFoundException) { fail("Missing bounded provider composition policy"); return }
        val instance = cls.getField("INSTANCE").get(null)
        val eligible = cls.getMethod("eligible", String::class.java, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, String::class.java)
        assertEquals(true, eligible.invoke(instance, "AFTMM", 25, "com.amazon.webview.chromium", 61446232,
            "118.amazon-webview-v118-5993-tv.5993.155.160"))
    }
}
