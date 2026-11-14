package com.buccaneer.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Process-scoped, WebView-only IPv6 preference. Existing TLS/cookies/WebView request
 * interception remain in Chromium. No VPN, routing changes, consent, or DNS packets.
 */
object Ipv6Preference {
    private var proxy: Ipv6PreferenceProxy? = null
    private var enabled = false
    private val executor = Executor { Handler(Looper.getMainLooper()).post(it) }
    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** Call on UI thread. OFF restores native WebView networking, not forced IPv4. */
    fun apply(context: Context, requested: Boolean) {
        val app = context.applicationContext
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("ipv6Preference", false).apply()
            DiagnosticLog.record(app, "ipv6_preference_unavailable")
            return
        }
        enabled = requested
        if (requested) {
            try {
                val tunnel = proxy ?: Ipv6PreferenceProxy(onConnect = { host, address ->
                    val family = if (address is Inet6Address) "IPv6" else "IPv4"
                    if (seen.size < 256 && seen.add("$host/$family")) {
                        DiagnosticLog.record(app, "ipv6_preference_dial", JSONObject()
                            .put("host", host).put("family", family).put("peer", address.hostAddress))
                    }
                }).also { proxy = it }
                // SOCKS5 carries hostnames to the app resolver; Chromium's Happy Eyeballs
                // cannot race a public IPv4 socket against the selected IPv6 upstream.
                val config = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:${tunnel.port}").build()
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    if (enabled) DiagnosticLog.record(app, "ipv6_preference_applied", JSONObject()
                        .put("enabled", true).put("port", tunnel.port))
                }
            } catch (e: Exception) {
                enabled = false
                proxy?.close()
                proxy = null
                app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putBoolean("ipv6Preference", false).apply()
                ProxyController.getInstance().clearProxyOverride(executor) { }
                DiagnosticLog.record(app, "ipv6_preference_failed", JSONObject().put("error", e.javaClass.simpleName))
            }
        } else {
            ProxyController.getInstance().clearProxyOverride(executor) {
                if (!enabled) {
                    proxy?.close()
                    proxy = null
                    seen.clear()
                    DiagnosticLog.record(app, "ipv6_preference_applied", JSONObject().put("enabled", false))
                }
            }
        }
    }
}
