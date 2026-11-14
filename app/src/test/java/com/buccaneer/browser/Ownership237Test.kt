package com.buccaneer.browser

import org.junit.Assert.*
import org.junit.Test

/** State owners are deliberately independent; editor/video get only page identity. */
class Ownership237Test {
    @Test fun documentRotationInvalidatesBothEpochAndToken() {
        val page = PageIdentity()
        val before = page.snapshot()
        page.advance()
        assertNotEquals(before, page.snapshot())
        assertFalse(page.matches(before))
    }
    @Test fun mediaOwnershipCannotBeStolenByStaleOrBackgroundFrame() {
        val page=PageIdentity(); val media=MediaOwnership(page)
        media.setForeground(true)
        val owner=page.token+":player"
        assertTrue(media.started(owner));assertEquals(owner,media.owner)
        assertFalse(media.started("old:player"));assertEquals(owner,media.owner)
        media.setForeground(false)
        assertFalse(media.started(page.token+":other"));assertEquals(owner,media.owner)
        media.stopped(page.token+":other");assertEquals(owner,media.owner)
        media.reset();assertEquals("",media.owner)
    }
    @Test fun bridgeSurfaceIsExactlyTheCheckedContract() {
        val expected = BrowserBridgeContract::class.java.methods.map { it.name to it.parameterTypes.toList() }.toSet()
        val actual = MainActivity.Bridge::class.java.methods.filter { it.isAnnotationPresent(android.webkit.JavascriptInterface::class.java) }
        assertEquals(21,actual.size)
        assertEquals(expected, actual.map { it.name to it.parameterTypes.toList() }.toSet())
    }
}
