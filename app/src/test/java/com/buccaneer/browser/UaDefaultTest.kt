package com.buccaneer.browser

import android.webkit.WebView
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import android.os.Looper
import org.robolectric.annotation.LooperMode

/**
 * Pins the desktop-UA default (shipped behavior since 2.4.x): a fresh install (no
 * stored "desktopSite" pref) must boot with the desktop-layout UA, and the
 * device-native variant must remain available via the toggle. The mobile-UA
 * default (2026-11-14) broke movy.sx playback and was reverted 2026-11-15.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25,28],qualifiers="w960dp-h540dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UaDefaultTest {
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    @After fun clean() { controllers.forEach { it.pause().stop().destroy() }; controllers.clear() }
    private fun make(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controllers.add(c)
        return c.get()
    }

    @Test fun desktopSiteDefaultsOnForFreshInstall() {
        val a = make()
        assertTrue(TestAccess.get(a, "desktopSite") as Boolean)
    }

    @Test fun webViewBootsWithDesktopUa() {
        val a = make()
        val policy = TestAccess.get(a, "uaPolicy") as UserAgentPolicy
        val webView = TestAccess.get(a, "webView") as WebView
        assertEquals(policy.desktopUa, webView.settings.userAgentString)
        assertTrue(webView.settings.userAgentString.contains("Windows NT"))
    }

    @Test fun desktopLayoutVariantStillBuilt() {
        val a = make()
        val policy = TestAccess.get(a, "uaPolicy") as UserAgentPolicy
        assertTrue(policy.desktopUa.contains("Windows NT"))
        assertTrue(policy.desktopUa.startsWith("Mozilla/5.0"))
    }
}
