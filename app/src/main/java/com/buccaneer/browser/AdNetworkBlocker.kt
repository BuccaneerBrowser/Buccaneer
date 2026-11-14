package com.buccaneer.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Network-only ad-host containment (2.4.4).
 *
 * Classification rule: a host is blocked ONLY with positive evidence it serves ads
 * (auction/tracker/beacon/creative). Unverified hosts are allowed.
 * Exemptions below are mechanical: they are evaluated BEFORE any block rule, so a future list
 * edit cannot silently re-block verified movie CDNs. An earlier block-list revision
 * misclassified two legitimate movie CDNs (HLS manifest/subtitle/segment hosts) and broke
 * playback; they remain exempt here.
 *
 * Runs on WebView's IO thread. No WebView/DOM/UI access, no outgoing network IO.
 * Do not broaden shared platforms (workers.dev, googleapis, Cloudflare, Amazon media) to parents.
 */
object AdNetworkBlocker {

    /** Verified-legitimate hosts, exempt from ALL block rules (evaluated first). */
    private val exemptHosts = setOf(
            "goldenkite.top",   // movie HLS/subtitles/thumbnails CDN
            "stormhive.top"     // movie #EXT-X-MAP segment host
    )

    /** Verified ad infrastructure (suffix match, domain-boundary enforced). */
    private val suffixHosts = setOf(
            "flashb.id",
            "pubmatic.com",
            "doubleclick.net",
            "googlesyndication.com",
            "googletagservices.com",
            "2mdn.net",
            "adnxs.com",
            "3lift.com",
            "media.net",
            "rubiconproject.com",
            "casalemedia.com",
            "tynt.com",
            "criteo.com",
            "deepintent.com",
            "e-planning.net",
            "yellowblue.io",
            "seedtag.com",
            "krushmedia.com",
            "sonobi.com",
            "minutemedia-prebid.com",
            "openx.net",
            "presage.io",
            "sparteo.com",
            "richaudience.com",
            "smaato.net",
            "amazon-adsystem.com",
            "adtrafficquality.google",
            "flashtalking.com",
            "push-sdk.com",      // push-adware SDK (detected via console errors)
            "hillscodger.qpon"   // adware popup apex (detected via blocked-popup diagnostics)
    )

    /** Verified ad hosts, exact only (never broadened to sibling/shared domains). */
    private val exactHosts = setOf(
            "pulsedesk.top",
            "greenkite.top",    // beacon/tracker: 17x root-path churn, 0 media manifests (2026-11-10 + 2026-11-12)
            "2vkmbk6br1.ulg5n21zpek6wmic8u1.rest",
            "onetag-sys.com",
            "match.sharethrough.com",
            "sync.plusoneads.com",
            "ap.lijit.com",
            "ce.lijit.com",
            "prebid.a-mo.net",
            "ad.360yield.com",
            "rtb.gumgum.com",
            "sync.connectad.io",
            "user-sync.fwmrm.net",
            "usync.4dex.io",
            "et-c-ash.33across.com",
            "ssbsync.smartadserver.com",
            "pmp.mxptint.net",
            "e1.ssp.cadent.com",
            "bh.contextweb.com",
            "sync.bfmio.com",
            "cs.startappnetwork.com",
            "measureadv.com",
            "crb.kargo.com",
            "imasdk.googleapis.com"
    )

    /**
     * Rotating malvertising signature observed 2026-11-12:
     * r2x1z7ur1l6zmznlvvy.7tnqeentnq56j4jfrq.cfd (random 22-char label + random 16-char apex on .cfd).
     * Such domains rotate too fast for static lists; the shape itself is the signature.
     */
    private val rotatingCfd = Regex("^[a-z0-9]{16,}\\.[a-z0-9]{12,}\\.cfd$")

    fun blocksHost(host: String?): Boolean {
        val normalized = host?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isEmpty()) return false
        // Exemptions win over everything: verified movie CDNs can never be re-blocked by drift.
        if (exemptHosts.any { normalized == it || normalized.endsWith(".$it") }) return false
        if (normalized in exactHosts) return true
        if (rotatingCfd.matches(normalized)) return true
        return suffixHosts.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun intercept(context: Context, request: WebResourceRequest?): WebResourceResponse? {
        val r = request ?: return null
        val host = r.url.host ?: return null
        if (!blocksHost(host)) return null
        DiagnosticLog.record(context, "ad_request_blocked", JSONObject().apply {
            put("host", host)
            put("url", DiagnosticLog.safeUrl(r.url.toString()))
            put("mainFrame", r.isForMainFrame)
            put("method", r.method)
        })
        // Null means "fetch normally" in WebViewClient; an actual empty error response prevents IO.
        // Fresh stream per request, no retained creative bytes, no cached failure across upgrades.
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked ad request",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }
}
