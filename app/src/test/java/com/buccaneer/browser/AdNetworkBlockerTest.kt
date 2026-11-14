package com.buccaneer.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class AdNetworkBlockerTest {
    private fun policy(): Pair<Class<*>, Any> {
        val cls = Class.forName("com.buccaneer.browser.AdNetworkBlocker")
        return cls to cls.getField("INSTANCE").get(null)!!
    }
    private fun blocked(host: String?): Boolean {
        val (cls, obj) = policy()
        return cls.getMethod("blocksHost", String::class.java).invoke(obj, host) as Boolean
    }
    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    @Test fun verifiedAdInfrastructureIsBlocked() {
        for (host in listOf("srv.flashb.id",
            "www.flashb.id",
            "cdn.flashtalking.com",
            "pulsedesk.top",
            "greenkite.top",
            "2vkmbk6br1.ulg5n21zpek6wmic8u1.rest",
            "ads.pubmatic.com",
            "st.pubmatic.com",
            "image2.pubmatic.com",
            "simage2.pubmatic.com",
            "s0.2mdn.net",
            "tpc.googlesyndication.com",
            "ad.doubleclick.net",
            "cm.g.doubleclick.net",
            "securepubads.g.doubleclick.net",
            "googleads.g.doubleclick.net",
            "www.googletagservices.com",
            "ep2.adtrafficquality.google",
            "eb2.3lift.com",
            "hbx.media.net",
            "eus.rubiconproject.com",
            "acdn.adnxs.com",
            "ib.adnxs.com",
            "s.amazon-adsystem.com",
            "onetag-sys.com",
            "ssum-sec.casalemedia.com",
            "de.tynt.com",
            "hde.tynt.com",
            "sync.sparteo.com",
            "ms-cookie-sync.presage.io",
            "gum.criteo.com",
            "ssp-sync.criteo.com",
            "cdn.deepintent.com",
            "u-sjc03.e-planning.net",
            "ads.us.e-planning.net",
            "match.sharethrough.com",
            "sync.plusoneads.com",
            "cs-tam.yellowblue.io",
            "pbs-cs.yellowblue.io",
            "cs-server-s2s.yellowblue.io",
            "ap.lijit.com",
            "ce.lijit.com",
            "u.openx.net",
            "eu-u.openx.net",
            "us-u.openx.net",
            "prebid.a-mo.net",
            "s.seedtag.com",
            "cs.seedtag.com",
            "cs.krushmedia.com",
            "sync.go.sonobi.com",
            "ad.360yield.com",
            "rtb.gumgum.com",
            "sync.connectad.io",
            "user-sync.fwmrm.net",
            "usync.4dex.io",
            "cs-rtb.minutemedia-prebid.com",
            "cs.minutemedia-prebid.com",
            "cs-tam.minutemedia-prebid.com",
            "et-c-ash.33across.com",
            "ssbsync.smartadserver.com",
            "pmp.mxptint.net",
            "e1.ssp.cadent.com",
            "bh.contextweb.com",
            "s.ad.smaato.net",
            "sync.bfmio.com",
            "cs.startappnetwork.com",
            "measureadv.com",
            "us-east-sync.richaudience.com",
            "sync.richaudience.com",
            "crb.kargo.com",
            "imasdk.googleapis.com",
            "push-sdk.com",
            "sdk.push-sdk.com",
            "jp.hillscodger.qpon")) assertTrue(host, blocked(host))
    }
    @Test fun verifiedLegitimateMovieCdnsAreNeverBlocked() {
        // The 2.4.1 rejection: these two were misclassified as takeover hosts and broke Movy playback.
        for (host in listOf("moon.goldenkite.top",
            "goldenkite.top",
            "stormhive.top",
            "vd.stormhive.top")) assertFalse(host, blocked(host))
    }
    @Test fun legitimateContentAndStreamingEdgesRemainAllowed() {
        for (host in listOf("m.media-amazon.com",
            "challenges.cloudflare.com",
            "storage.googleapis.com",
            "media.w3.org",
            "movy.sx",
            "www.movy.sx",
            "w3schools.com",
            "www.w3schools.com",
            "dms.licdn.com",
            "media.licdn.com",
            "image.tmdb.org",
            "vidbolt.xyz",
            "vidcore.io",
            "imdb-video.media-imdb.com",
            "ghostflix.st",
            "novahd.cc",
            "nova-edge-189637577b5c.round-butterfly-69dd.workers.dev",
            "nova-edge.cf3-726.workers.dev",
            "some.other.workers.dev",
            "archive.org",
            "ia801001.us.archive.org")) assertFalse(host, blocked(host))
        assertFalse(blocked(null)); assertFalse(blocked(""))
    }
    @Test fun rotatingRandomCfdMalvertisingSignatureIsBlocked() {
        assertTrue(blocked("r2x1z7ur1l6zmznlvvy.7tnqeentnq56j4jfrq.cfd"))
        assertTrue(blocked("AB12CD34EF56GH78IJ90KL12.MNOPQRSTUVWXyz1234.CFD"))
        // Normal short-label .cfd domains are NOT the signature.
        for (host in listOf("example.cfd", "abc.def.cfd", "shorty.medium-length.cfd"))
            assertFalse(host, blocked(host))
    }
    @Test fun suffixBoundariesCaseAndTrailingRootDot() {
        assertTrue(blocked("NEW.SRV.FLASHB.ID."))
        assertTrue(blocked("pubmatic.com"))
        for (host in listOf("notflashb.id", "flashb.id.evil.test", "pubmatic.com.evil.test",
                "evilpubmatic.com", "imasdk.googleapis.com.evil.test", "other.googleapis.com"))
            assertFalse(host, blocked(host))
    }
    @Test fun exactHostsAreNotBroadenedToSharedPlatforms() {
        assertFalse(blocked("other.rest")); assertFalse(blocked("foo.imasdk.googleapis.com"))
        assertFalse(blocked("other.a-mo.net")); assertFalse(blocked("amazon.com"))
        assertFalse(blocked("other.qpon")); assertFalse(blocked("hillscodger.qpon.evil.test"))
        assertFalse(blocked("mypush-sdk.com")); assertFalse(blocked("push-sdk.com.evil.test"))
    }
    @Test fun exemptionsWinOverAnyFutureSuffixDrift() {
        // Mechanical guard: even if a conflicting suffix rule were ever added,
        // verified movie CDNs stay allowed (the 2.4.1 failure mode, made impossible).
        val cls = Class.forName("com.buccaneer.browser.AdNetworkBlocker")
        val obj = cls.getField("INSTANCE").get(null)!!
        val m = cls.getMethod("blocksHost", String::class.java)
        assertFalse(m.invoke(obj, "moon.goldenkite.top") as Boolean)
        assertFalse(m.invoke(obj, "seg.stormhive.top") as Boolean)
    }
    @Test fun interceptedAdHasEmpty403NoStoreAndAllowedRequestFallsThrough() {
        val (cls, obj) = policy()
        val method = cls.getMethod("intercept", Context::class.java, WebResourceRequest::class.java)
        val ctx = RuntimeEnvironment.getApplication()
        val response = method.invoke(obj, ctx, request("https://srv.flashb.id/ad?secret=123")) as WebResourceResponse
        assertEquals(403, response.statusCode)
        assertEquals(-1, response.data.read())
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertNull(method.invoke(obj, ctx, request("https://challenges.cloudflare.com/turnstile/")))
        assertNull(method.invoke(obj, ctx, request("https://media.w3.org/2010/05/bunny/movie.mp4")))
        assertNull(method.invoke(obj, ctx, request("https://moon.goldenkite.top/index-s1080p-v1-a1.m3u8")))
        assertNull(method.invoke(obj, ctx, null))
    }
    @Test fun adInterceptPrecedesUnchangedImageFirewall() {
        val source = sequenceOf(java.io.File("src/main/java/com/buccaneer/browser/MainActivity.kt"),
            java.io.File("app/src/main/java/com/buccaneer/browser/MainActivity.kt")).first { it.isFile }.readText()
        assertTrue(source.contains("AdNetworkBlocker.intercept(this@MainActivity, r) ?: imageFirewall.intercept(r)"))
    }
}
