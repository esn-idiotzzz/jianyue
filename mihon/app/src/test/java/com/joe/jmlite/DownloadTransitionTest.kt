package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DownloadTransitionTest {
    @Test fun `resting buttons retain their original geometry and opacity`() {
        assertEquals(0f, DownloadTransition.merge(0f))
        assertEquals(1f, DownloadTransition.buttonsAlpha(0f))
        assertEquals(1f, DownloadTransition.buttonScaleX(0f))
        assertEquals(0f, DownloadTransition.panelAlpha(0f))
    }
    @Test fun `both button centers converge to the same row center`() {
        for (fraction in listOf(0f, .1f, .2f, .42f, 1f)) {
            val merge = DownloadTransition.merge(fraction)
            val left = 70f + (150f - 70f) * merge
            val right = 230f + (150f - 230f) * merge
            assertTrue(left in 70f..150f)
            assertTrue(right in 150f..230f)
        }
        assertEquals(1f, DownloadTransition.merge(.42f))
    }
    @Test fun `progress expansion begins only near the end of the merge`() {
        assertEquals(0f, DownloadTransition.expand(.38f))
        assertTrue(DownloadTransition.expand(.5f) > 0f)
        assertEquals(0f, DownloadTransition.buttonsAlpha(.42f))
    }
    @Test fun `expanded progress takes the full width with buttons hidden`() {
        assertEquals(1f, DownloadTransition.expand(1f))
        assertEquals(1f, DownloadTransition.panelScaleX(1f))
        assertEquals(1f, DownloadTransition.panelAlpha(1f))
        assertEquals(0f, DownloadTransition.buttonsAlpha(1f))
    }
    @Test fun `phase boundaries have continuous geometry and opacity`() {
        for (point in listOf(.18f, .34f, .38f, .42f, .54f)) {
            val before = point - .00001f
            val after = point + .00001f
            assertEquals(DownloadTransition.merge(before), DownloadTransition.merge(after), .001f)
            assertEquals(DownloadTransition.expand(before), DownloadTransition.expand(after), .001f)
            assertEquals(DownloadTransition.buttonsAlpha(before), DownloadTransition.buttonsAlpha(after), .001f)
            assertEquals(DownloadTransition.panelAlpha(before), DownloadTransition.panelAlpha(after), .001f)
        }
    }
    @Test fun `transition remains bounded and expands monotonically in either direction`() {
        var previous = 0f
        for (step in 0..100) {
            val fraction = step / 100f
            val value = DownloadTransition.expand(fraction)
            assertTrue(value >= previous)
            assertTrue(value in 0f..1f)
            assertTrue(DownloadTransition.buttonsAlpha(fraction) in 0f..1f)
            previous = value
        }
        assertEquals(0f, DownloadTransition.expand(0f))
    }
    @Test fun `page count shows completed and total pages without inventing an unknown total`() {
        assertEquals("1/20", DownloadTransition.pages(1, 20))
        assertEquals("20/20", DownloadTransition.pages(20, 20))
        assertEquals("20/20", DownloadTransition.pages(21, 20))
        assertEquals("0/…", DownloadTransition.pages(0, 0))
        assertEquals("0/20", DownloadTransition.pages(-1, 20))
    }
}
