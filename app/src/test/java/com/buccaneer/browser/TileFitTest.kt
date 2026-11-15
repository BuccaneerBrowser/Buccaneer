package com.buccaneer.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure tile-fit scale math (no Android classes involved).
 *
 * Reference case (measured on-device 2026-11-15, Reedstreams tile): title 152 px +
 * rule 10 px + subtitle 41 px = 203 px against a 183 px content budget, with a
 * 2 px safety margin -> title budget 130 px, scale 130/152 ~= 0.855.
 */
class TileFitTest {
    @Test
    fun measuredOverflowCaseShrinksTitle() {
        val scale = tileFitScale(152, 10, 41, 183)
        assertEquals(130f / 152f, scale, 1e-6f)
        assertTrue(scale < 1f)
    }

    @Test
    fun fittingContentIsUntouched() {
        // One-line tile: 81 + 10 + 41 = 132 <= 183 - 2 -> no shrink.
        assertEquals(1f, tileFitScale(81, 10, 41, 183), 0f)
    }

    @Test
    fun exactFitNeedsNoShrink() {
        // total == availH - margin exactly still fits.
        assertEquals(1f, tileFitScale(130, 10, 41, 183), 0f)
    }

    @Test
    fun overByOnePixelShrinks() {
        // total == availH - margin + 1 must shrink; budget = 183 - 2 - 10 - 41 = 130.
        val scale = tileFitScale(131, 10, 41, 183)
        assertTrue(scale < 1f)
        assertEquals(130f / 131f, scale, 1e-6f)
    }

    @Test
    fun degenerateBudgetReturnsZero() {
        // rule + subtitle alone eat the whole budget -> nothing can be shown.
        assertEquals(0f, tileFitScale(100, 100, 100, 183), 0f)
    }

    @Test
    fun marginIsHonouredExplicitly() {
        // With margin 0 the same numbers leave 2 px more budget for the title.
        assertEquals(132f / 152f, tileFitScale(152, 10, 41, 183, margin = 0), 1e-6f)
    }
}
