package com.buccaneer.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the UA string logic extracted from MainActivity (2026-11-14 untangle).
 * WebSettings-dependent wiring (initialize/apply) is verified on-device, since
 * WebSettings is not instantiable in the JVM test environment.
 */
class UserAgentPolicyTest {
    private val DESKTOP_118 =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    @Test
    fun buildDesktopUaDerivesVersionFromNativeUa() {
        val native =
            "Mozilla/5.0 (Linux; Android 7.1.2; AFTMM Build/NS6711; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/118.0.0.0 Mobile Safari/537.36"
        assertEquals(DESKTOP_118, UserAgentPolicy.buildDesktopUa(native))
    }

    @Test
    fun buildDesktopUaKeepsArbitraryVersions() {
        val native = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.60 Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.60 Safari/537.36",
            UserAgentPolicy.buildDesktopUa(native)
        )
    }

    @Test
    fun buildDesktopUaFallsBackTo120() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            UserAgentPolicy.buildDesktopUa("no chrome version token")
        )
    }
}
