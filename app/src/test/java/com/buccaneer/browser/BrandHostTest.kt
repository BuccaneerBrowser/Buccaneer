package com.buccaneer.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand host matching: site-specific fixes follow the brand (sitename.*) — any TLD and any
 * subdomain — so a mirror on a rotated apex keeps the same behaviour. Look-alike domains
 * (extra labels before or after the brand zone) must never match.
 */
class BrandHostTest {
    private fun brand(h: String, b: String) = PopupGuard.isBrandHost(h, b)

    @Test fun apexAnyTldAndSubdomainsMatch() {
        for (tld in listOf("gd", "su", "xyz", "com", "net", "to", "st", "link", "sx", "pk"))
            assertTrue("$tld", brand("flixer.$tld", "flixer"))
        assertTrue(brand("www.flixer.gd", "flixer"))
        assertTrue(brand("sub.www.flixer.gd", "flixer"))
        assertTrue(brand("FLIXER.GD.", "flixer"))
    }

    @Test fun lookAlikesAndForeignZonesDoNotMatch() {
        assertFalse(brand("notflixer.gd", "flixer"))
        assertFalse(brand("flixerx.gd", "flixer"))
        assertFalse(brand("xflixer.gd", "flixer"))
        assertFalse(brand("flixer.gd.attacker.test", "flixer"))
        assertFalse(brand("flixer", "flixer"))
        assertFalse(brand("", "flixer"))
    }

    @Test fun fleetBrandsFollowTheSameRule() {
        assertTrue(brand("streamcorner.rewind", "streamcorner"))
        assertTrue(brand("reedstreams.link", "reedstreams"))
        assertTrue(brand("movy.sx", "movy"))
        assertTrue(brand("cinejoy.pk", "cinejoy"))
        assertTrue(brand("ad.ghostflix.st", "ghostflix"))
        assertFalse(brand("evil-movy.sx", "movy"))
        assertFalse(brand("shieldmovy.sx", "movy"))
        assertFalse(brand("movy.sx.attacker.test", "movy"))
    }
}
