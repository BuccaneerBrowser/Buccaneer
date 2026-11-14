package com.buccaneer.browser

import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Actual policy/callback regressions. No live network/TV; not playback proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25], qualifiers="w1280dp-h720dp-mdpi", shadows=[Regression171Test.ScriptWebView::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Navigation238Test {
    private fun guard() = PopupGuard(RuntimeEnvironment.getApplication())
    private fun blocked(from: String?, to: String) = guard().blocksNavigation(from, Uri.parse(to))
    @Test fun requiredMigrationIsDirectedAndExactNotGlobalTrust() {
        guard().protect("nippleflix.com") // Existing bookmarked/persisted protection reproduced.
        guard().protect("cinejoy.pk")
        guard().protect("ghostflix.st")
        assertFalse(blocked("https://nippleflix.com/", "https://ghostflix.st/"))
        assertFalse(blocked("https://www.nippleflix.com/", "https://www.ghostflix.st/watch/one"))
        assertTrue(blocked("https://nippleflix.com/", "http://ghostflix.st/"))
        assertTrue(blocked("https://nippleflix.com/", "https://ghostflix.st.attacker.test/"))
        assertTrue(blocked("https://nippleflix.com/", "https://ad.ghostflix.st/"))
        assertTrue(blocked("https://ghostflix.st/", "https://nippleflix.com/"))
        assertTrue(blocked("https://cinejoy.pk/", "https://ghostflix.st/"))
    }
    @Test fun unprotectedSitesNavigateFreelyByDefault() {
        // No hardcoded list: a site the user has not protected navigates freely.
        for (source in listOf("https://cinejoy.pk/", "https://unlisted.test/", "https://www.bbc.com/")) {
            for (target in listOf("https://geratelogs.qpon/", "https://plewchreginae.cyou/", "https://de.rimergombo.qpon/"))
                assertFalse("$source -> $target", blocked(source,target))
        }
        assertFalse(blocked("https://cinejoy.pk/", "https://bbc.com/"))
    }
    @Test fun userProtectedSitesRemainGuarded() {
        guard().protect("cinejoy.pk")
        guard().protect("unlisted.test")
        for (source in listOf("https://cinejoy.pk/", "https://unlisted.test/")) {
            for (target in listOf("https://geratelogs.qpon/", "https://plewchreginae.cyou/", "https://de.rimergombo.qpon/"))
                assertTrue("$source -> $target", blocked(source,target))
        }
        assertTrue(blocked("https://cinejoy.pk/", "https://bbc.com/"))
        // Subdomain of a protected site stays allowed.
        assertFalse(blocked("https://cinejoy.pk/", "https://watch.cinejoy.pk/x"))
    }
    @Test fun missingOrInvalidInitiatorIsFailClosed() {
        assertTrue(blocked(null,"https://bbc.com/"))
        assertTrue(blocked("about:blank","https://bbc.com/"))
        assertTrue(blocked("not a URL","https://bbc.com/"))
    }
    @Test fun sameSiteCanonicalizationDoesNotTrustCommonPublicSuffix() {
        guard().protect("cinejoy.pk")
        guard().protect("site.co.uk")
        guard().protect("wikiflix.toolforge.org")
        assertFalse(blocked("https://www.cinejoy.pk/", "https://cinejoy.pk/search?q=one"))
        assertFalse(blocked("https://CINEJOY.PK./", "https://www.cinejoy.pk./show/one"))
        assertFalse(blocked("https://cinejoy.pk/", "https://watch.cinejoy.pk/"))
        assertTrue(blocked("https://cinejoy.pk/", "https://evilcinejoy.pk/"))
        assertTrue(blocked("https://site.co.uk/", "https://other.co.uk/"))
        assertTrue(blocked("https://wikiflix.toolforge.org/", "https://attacker.toolforge.org/"))
    }
    @Test fun nonHttpMainFrameTargetsCannotEscapePolicy() {
        for (url in listOf("data:text/html,hello", "file:///sdcard/evil.html", "intent://bad", "javascript:alert(1)", "about:blank", "blob:https://cinejoy.pk/one"))
            assertTrue(url,blocked("https://cinejoy.pk/",url))
    }
    @Test fun explicitMasterOffStillOptsOut() {
        val g=guard(); if(g.enabled) g.toggle()
        assertFalse(g.blocksNavigation("https://cinejoy.pk/", Uri.parse("https://other.test/")))
    }
    private fun get(a:MainActivity,n:String)=TestAccess.get(a,n)
    private fun set(a:MainActivity,n:String,v:Any?)=TestAccess.set(a,n,v)
    private fun call(a:MainActivity,n:String,vararg v:Any?):Any? = MainActivity::class.java.declaredMethods.single{it.name==n&&it.parameterCount==v.size}.apply{isAccessible=true}.invoke(a,*v)
    private fun fixture(block: (MainActivity,WebView)->Unit) {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=c.get()
        try {
            val web=get(a,"webView") as WebView
            (get(a,"startPage") as StartPage).hide()
            set(a,"anchorUrl","https://cinejoy.pk/")
            set(a,"lastPageUrl","https://cinejoy.pk/watch/one")
            set(a,"cachedPageUrl","https://cinejoy.pk/watch/one")
            // Fixture scenario: the user is watching on a site they have protected.
            (get(a,"guard") as PopupGuard).protect("cinejoy.pk")
            block(a,web)
        } finally { c.pause().stop().destroy() }
    }
    private class Request(private val target:String,private val main:Boolean=true,private val gesture:Boolean=false,private val redirect:Boolean=false):WebResourceRequest {
        override fun getUrl()=Uri.parse(target)
        override fun isForMainFrame()=main
        override fun hasGesture()=gesture
        override fun isRedirect()=redirect
        override fun getMethod()="GET"
        override fun getRequestHeaders()=emptyMap<String,String>()
    }
    @Test fun nativeGuardNeverUsesWebViewsAlreadyChangedUrlAsAuthority() = fixture { _,web ->
        val client=Shadows.shadowOf(web).webViewClient
        // Real providers can expose target in view.url by the time a callback is delivered.
        web.loadUrl("https://bbc.com/")
        assertTrue(client.shouldOverrideUrlLoading(web,Request("https://bbc.com/")))
    }
    @Test fun elapsedTimeAndInputDoNotAuthorizeHijacks() = fixture { a,web ->
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        call(a,"advanceInteraction")
        assertTrue(Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,Request("https://bbc.com/",gesture=true,redirect=true)))
        assertFalse((get(a,"menu") as MenuPanel).shown())
    }
    @Test fun requiredMigrationSurvivesNativeOverride() = fixture { a,web ->
        set(a,"anchorUrl","https://nippleflix.com/")
        (get(a,"guard") as PopupGuard).protect("nippleflix.com")
        web.loadUrl("https://nippleflix.com/")
        assertFalse(Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,Request("https://ghostflix.st/",redirect=true)))
    }
    @Test fun subframeProvidersAreNotMistakenForMainFrameHijacks() = fixture { _,web ->
        assertFalse(Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,Request("https://vidcore.io/embed/one",main=false)))
    }
    @Test fun rejectedPageStartCannotPoisonCacheRecoveryOrCrashPersistence() = fixture { a,web ->
        val client=Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web,"https://bbc.com/",null)
        assertEquals("https://cinejoy.pk/watch/one",get(a,"lastPageUrl"))
        assertNotEquals("https://bbc.com/",get(a,"cachedPageUrl"))
        assertNotEquals("https://bbc.com/",(get(a,"prefs") as android.content.SharedPreferences).getString("lastCrashUrl",null))
        client.onPageCommitVisible(web,"https://bbc.com/")
        client.onPageFinished(web,"https://bbc.com/")
        assertNotEquals("https://bbc.com/",get(a,"cachedPageUrl"))
    }
    @Test fun automaticNativeRestoreSeedsAuthorityBeforeLoad() = fixture { a,_ ->
        call(a,"navigateUrl","https://archive.org/details/moviesandfilms",false,true)
        assertEquals("https://archive.org/details/moviesandfilms",get(a,"anchorUrl"))
    }
    private fun buttons(a:MainActivity):List<android.widget.Button> {
        val panel=get(a,"menu") as MenuPanel
        val list=MenuPanel::class.java.getDeclaredField("list").apply{isAccessible=true}.get(panel) as android.widget.LinearLayout
        return (0 until list.childCount).map{list.getChildAt(it) as android.widget.Button}
    }
    private fun requestReview(a:MainActivity,web:WebView) {
        assertTrue(Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,Request("https://archive.org/details/moviesandfilms",gesture=true)))
        assertFalse((get(a,"menu") as MenuPanel).shown()) // Never show a popup because an ad has a gesture.
        call(a,"showMainMenu")
        buttons(a).single{it.text.toString()=="Review blocked link"}.performClick()
        assertEquals("Stay on this site",buttons(a).first().text.toString())
    }
    @Test fun crossSiteGestureRequiresTwoExplicitNativeChoices() = fixture { a,web ->
        requestReview(a,web)
        buttons(a)[1].performClick()
        assertEquals("https://archive.org/details/moviesandfilms",get(a,"anchorUrl"))
    }
    @Test fun staleReviewCannotAuthorizeANewDocument() = fixture { a,web ->
        requestReview(a,web)
        (get(a,"pageIdentity") as PageIdentity).advance()
        buttons(a)[1].performClick()
        assertEquals("https://cinejoy.pk/",get(a,"anchorUrl"))
    }
    @Test fun reviewStayDoesNotNavigateOrRetainPermission() = fixture { a,web ->
        requestReview(a,web)
        buttons(a).first().performClick()
        assertEquals("https://cinejoy.pk/",get(a,"anchorUrl"))
        assertNull(get(a,"pendingSiteLink"))
    }
    @Test fun repeatedFallbackIsBoundedAndEndsOnHome() = fixture { a,web ->
        val client=Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web,"https://bbc.com/",null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        client.onPageStarted(web,"https://bbc.com/",null)
        assertTrue((get(a,"startPage") as StartPage).shown())
        assertEquals("https://cinejoy.pk/watch/one",get(a,"lastPageUrl"))
    }
    @Test fun migrationAdvancesAnchorSoReverseRedirectIsNotTrusted() = fixture { a,web ->
        set(a,"anchorUrl","https://nippleflix.com/")
        (get(a,"guard") as PopupGuard).protect("ghostflix.st")
        Shadows.shadowOf(web).webViewClient.onPageStarted(web,"https://ghostflix.st/watch/one",null)
        assertEquals("https://ghostflix.st/watch/one",get(a,"anchorUrl"))
        assertTrue(Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,Request("https://nippleflix.com/",redirect=true)))
    }

    @Test fun recoveryRetainsApexAuthorityAcrossAcceptedSubdomain() = fixture { a,web ->
        // The user legitimately moves to an accepted subdomain; the anchor stays at the apex.
        val client = Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web, "https://watch.cinejoy.pk/player", null)
        assertEquals("https://watch.cinejoy.pk/player", get(a, "lastPageUrl"))
        // A hijack arrives: recovery reloads the validated subdomain without narrowing the anchor.
        client.onPageStarted(web, "https://bbc.com/", null)
        assertEquals("https://cinejoy.pk/", get(a, "anchorUrl"))
        // Later legitimate same-site navigation is still allowed under the retained anchor.
        assertFalse(client.shouldOverrideUrlLoading(web, Request("https://cinejoy.pk/next")))
    }

    @Test fun migrationAdvancementIsCaseInsensitiveOnScheme() = fixture { a,web ->
        set(a,"anchorUrl","https://nippleflix.com/")
        Shadows.shadowOf(web).webViewClient.onPageStarted(web,"HTTPS://ghostflix.st/watch/one",null)
        assertEquals("HTTPS://ghostflix.st/watch/one",get(a,"anchorUrl"))
    }
    @Test @Config(sdk=[22]) fun legacyCallbackCannotBypassAnchorUsingChangedViewUrl() = fixture { _,web ->
        web.loadUrl("https://bbc.com/")
        @Suppress("DEPRECATION")
        val denied=Shadows.shadowOf(web).webViewClient.shouldOverrideUrlLoading(web,"https://bbc.com/")
        assertTrue(denied)
    }
    @Test fun popupsCannotCreateSecondWebViewEvenWithGesture() = fixture { _,web ->
        val chrome=Shadows.shadowOf(web).webChromeClient
        assertFalse(chrome.onCreateWindow(web,false,true,android.os.Message.obtain()))
        assertFalse(chrome.onCreateWindow(web,true,false,android.os.Message.obtain()))
    }
}
