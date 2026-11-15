package com.buccaneer.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.util.Locale

private val TRANSPARENT_PIXEL_PNG_1X1: ByteArray by lazy {
    android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=",
        android.util.Base64.DEFAULT
    )
}

/** Synchronous image fetch for the hero-downscale intercept; runs on Chromium's IO thread, so a bounded blocking GET is fine. Null on any failure → caller falls back to the original request. */
private fun fetchImageBytes(url: String, timeoutMs: Long): Pair<ByteArray, String>? {
    return try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = conn.responseCode
        if (code != 200) { conn.disconnect(); return null }
        val ct = (conn.contentType ?: "image/jpeg").lowercase(Locale.US)
        if (!ct.startsWith("image/")) { conn.disconnect(); return null }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (bytes.size < 1024) return null // too small to be a real image
        bytes to ct
    } catch (e: Throwable) {
        null
    }
}

/** IO-thread policy, isolated from input, video transport and view composition. */
class ImageFirewall(private val context: Context, private val guard: PopupGuard, private val lowQuality: () -> Boolean) {
    @Volatile var active=false; private set
    @Volatile private var allowlist: Set<String> = emptySet()
    val allowlistSize get() = allowlist.size
    fun onCommittedHost(host: String) {
        val isImageGuarded=isSupportedHost(host)
            if (isImageGuarded != active) {
                active = isImageGuarded
                if (!isImageGuarded) allowlist=emptySet()
                DiagnosticLog.record(context, "image_firewall", JSONObject().apply {
                    put("active", isImageGuarded)
                    put("host", host)
                    put("allowlistSize", allowlist.size)
                })
            }
    }
    fun updateAllowlist(urls: String?) {
            val list = urls ?: "[]"
            if (list.length > 131072 || !JsonInputBudget.allows(list)) return
            try {
                val arr = org.json.JSONArray(list)
                val newSet = mutableSetOf<String>()
                if (arr.length() > 256) return
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "")
                    if (u.isNotEmpty() && u.length <= 8192) newSet.add(u)
                }
                allowlist = newSet
                DiagnosticLog.record(context, "tmdb_allowlist_sync", JSONObject().apply {
                    put("count", newSet.size)
                })
            } catch (e: Exception) {
                DiagnosticLog.record(context, "tmdb_allowlist_error", JSONObject().put("error", e.message ?: ""))
            }
    }
    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
            val r = request
            if (r != null && DiagnosticLog.isEnabled(context)) {
                val u = r.url.toString()
                val p = (r.url.path ?: "").lowercase(Locale.US)
                if (p.endsWith(".m3u8") || p.endsWith(".mpd") || p.contains("/manifest")) {
                    DiagnosticLog.record(context, "media_manifest_request", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(u))
                        put("main", r.isForMainFrame)
                        put("method", r.method)
                    })
                }
            }
            // Trailer video: the detail page embeds a looping YouTube trailer in an iframe; the
            // per-frame decode+composite is a major GPU load that tips the PowerVR driver over
            // (KEGLReleaseRenderTarget). Intercept the embed and return a blank page — no DOM
            // reflow (removing the iframe destabilizes the compositor). Gated on a guarded host.
            if (r != null && active) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = (r.url.path ?: "").lowercase(Locale.US)
                if ((host == "youtube.com" || host.endsWith(".youtube.com")) && path.startsWith("/embed/")) {
                    DiagnosticLog.record(context, "trailer_video_blocked", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(r.url.toString()))
                    })
                    return android.webkit.WebResourceResponse(
                        "text/html", "utf-8",
                        java.io.ByteArrayInputStream("<html><body style=\"margin:0;background:#000\"></body></html>".toByteArray())
                    )
                }
            }
            // Hero downscale: the detail page loads a 4K TMDB backdrop (3840x2160, ~33MB GPU
            // texture) displayed at ~945x540 — the page's biggest GPU-memory waste and a major
            // driver of the PowerVR render-target churn crash under D-pad navigation
            // (KEGLReleaseRenderTarget). Intercept /t/p/original/ and serve the w780 variant
            // (~1.4MB) — no DOM/URL change, TMDB firewall below bypassed; fetch failure falls through to the original 4K.
            if (r != null && active) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = r.url.path ?: ""
                if ((host == "image.tmdb.org" || host.endsWith(".tmdb.org")) && path.contains("/t/p/original/")) {
                    // Only downscale images the firewall already allows (in the allowlist): the hero
                    // is allowlisted when requested, so this fires for it. Off-screen images are left alone.
                    if (allowlist.contains(r.url.toString())) {
                        val smallUrl = r.url.toString().replace("/t/p/original/", "/t/p/w780/")
                        val fetched = fetchImageBytes(smallUrl, 6000L)
                        if (fetched != null) {
                            DiagnosticLog.record(context, "hero_downscaled", JSONObject().apply {
                                put("url", DiagnosticLog.safeUrl(r.url.toString()))
                                put("small", DiagnosticLog.safeUrl(smallUrl))
                                put("bytes", fetched.first.size)
                            })
                            return android.webkit.WebResourceResponse(
                                fetched.second, "utf-8", java.io.ByteArrayInputStream(fetched.first)
                            )
                        }
                        // fetch failed: fall through to the firewall (allows the 4K original)
                    }
                }
            }
            // Episode-poster downscale: the episode list loads w500 TMDB posters (500x750, ~1.5MB
            // each) displayed at ~180x230; 18+ posters = ~27MB, which evicts the hero's texture
            // (black hero). Intercept /t/p/w500/ and serve w185 (~0.18MB) — 87% GPU-memory reduction, no visible loss.
            if (r != null && active) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                val path = r.url.path ?: ""
                if ((host == "image.tmdb.org" || host.endsWith(".tmdb.org")) && path.contains("/t/p/w500/")) {
                    if (allowlist.contains(r.url.toString())) {
                        val smallUrl = r.url.toString().replace("/t/p/w500/", "/t/p/w185/")
                        val fetched = fetchImageBytes(smallUrl, 6000L)
                        if (fetched != null) {
                            DiagnosticLog.record(context, "poster_downscaled", JSONObject().apply {
                                put("url", DiagnosticLog.safeUrl(r.url.toString()))
                                put("small", DiagnosticLog.safeUrl(smallUrl))
                                put("bytes", fetched.first.size)
                            })
                            return android.webkit.WebResourceResponse(
                                fetched.second, "utf-8", java.io.ByteArrayInputStream(fetched.first)
                            )
                        }
                        // fetch failed: fall through to the firewall
                    }
                }
            }
            // TMDB image firewall (see tmdbAllowlist): only allow allowlisted images;
            // everything else gets a 1x1 pixel to keep GPU texture count low.
            if (r != null && active) {
                val host = r.url.host?.lowercase(Locale.US) ?: ""
                if (host == "image.tmdb.org" || host.endsWith(".tmdb.org")) {
                    val url = r.url.toString()
                    if (allowlist.contains(url)) {
                        return null // allow real image
                    }
                    // Return the cached 1x1 transparent PNG; only the stream is per-request.
                    val resp = android.webkit.WebResourceResponse(
                        "image/png", "inline", java.io.ByteArrayInputStream(TRANSPARENT_PIXEL_PNG_1X1)
                    )
                    DiagnosticLog.record(context, "tmdb_image_blocked", JSONObject().apply {
                        put("url", DiagnosticLog.safeUrl(url))
                    })
                    return resp
                }
            }
            return null
    }
    fun isSupportedHost(host: String): Boolean {
        // Brand-scoped: any TLD/subdomain of flixer or cinejoy (mirrors rotate apexes).
        return PopupGuard.isBrandHost(host, "flixer") || PopupGuard.isBrandHost(host, "cinejoy")
        // a poster site removed: its in-view posters didn't match the allowlist, so
        // the native 1x1 firewall blanked the home-page grid (see the image guard script).
    }

}
