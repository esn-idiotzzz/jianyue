package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guards the phone/tablet layout boundary. The tests use window widths in dp only, matching the
 * way the app decides between the original phone rules and the responsive tablet grid.
 */
class AdaptiveLayoutTest {
    @Test
    fun `phone shelf keeps the original three to six column rule`() {
        for (width in 320..599) {
            assertEquals(
                maxOf(3, minOf(6, width / 120)),
                AdaptiveLayout.shelfColumns(width),
                "phone width $width must keep its original column count",
            )
        }
        assertEquals(3, AdaptiveLayout.shelfColumns(360))
        assertEquals(3, AdaptiveLayout.shelfColumns(411))
        assertEquals(4, AdaptiveLayout.shelfColumns(480))
        assertEquals(4, AdaptiveLayout.shelfColumns(599))
    }

    @Test
    fun `tablet rules start only at the medium window breakpoint`() {
        assertFalse(AdaptiveLayout.isTabletWidth(599))
        assertTrue(AdaptiveLayout.isTabletWidth(600))
        assertEquals(3, AdaptiveLayout.searchColumns(320))
        assertEquals(3, AdaptiveLayout.searchColumns(599))
        assertTrue(AdaptiveLayout.shelfColumns(600) >= 4)
        assertTrue(AdaptiveLayout.searchColumns(600) >= 4)
    }

    @Test
    fun `shelf columns grow with the window and keep every cover inside the phone range`() {
        var previous = 0
        for (width in 600..3000) {
            val columns = AdaptiveLayout.shelfColumns(width)
            assertTrue(columns >= previous, "shelf columns must not shrink: width=$width")
            val cover = AdaptiveLayout.coverWidthDp(
                width - AdaptiveLayout.SHELF_PAGE_PADDING_DP * 2,
                AdaptiveLayout.SHELF_SPACING_DP,
                columns,
            )
            assertTrue(cover in 104..168, "width=$width columns=$columns cover=${cover}dp")
            previous = columns
        }
    }

    @Test
    fun `search columns grow with the window and keep every cover inside the search range`() {
        var previous = 0
        for (width in 600..3000) {
            val columns = AdaptiveLayout.searchColumns(width)
            assertTrue(columns >= previous, "search columns must not shrink: width=$width")
            val cover = AdaptiveLayout.coverWidthDp(
                width - AdaptiveLayout.SEARCH_PAGE_PADDING_DP * 2,
                AdaptiveLayout.SEARCH_SPACING_DP,
                columns,
            )
            assertTrue(cover in 104..176, "width=$width columns=$columns cover=${cover}dp")
            previous = columns
        }
    }

    @Test
    fun `landscape and portrait tablet windows both fit more than the phone grid`() {
        // Common 10-11 inch windows in portrait/landscape and typical small tablets.
        val portrait = AdaptiveLayout.shelfColumns(800)
        val landscape = AdaptiveLayout.shelfColumns(1280)
        assertTrue(portrait > 3, "800dp tablet portrait should show more than the phone's 3 columns")
        assertTrue(landscape > portrait, "1280dp tablet landscape should show more columns")
        assertTrue(AdaptiveLayout.shelfColumns(1024) >= portrait)
    }

    @Test
    fun `cover ratios match the original phone tiles and never collapse to zero`() {
        assertEquals(425, AdaptiveLayout.shelfCoverHeightPx(304))
        assertEquals(272, AdaptiveLayout.searchCoverHeightPx(200))
        assertEquals(1, AdaptiveLayout.shelfCoverHeightPx(0))
        assertEquals(1, AdaptiveLayout.searchCoverHeightPx(-5))
        assertTrue(AdaptiveLayout.shelfCoverHeightPx(120) / 120f in 1.39f..1.41f)
        assertTrue(AdaptiveLayout.searchCoverHeightPx(120) / 120f in 1.35f..1.37f)
    }

    @Test
    fun `centred content only insets windows wider than the content cap`() {
        assertEquals(0, AdaptiveLayout.centeredInsetPx(1080, 60, 2160))
        assertEquals(0, AdaptiveLayout.centeredInsetPx(2160, 60, 2160))
        assertEquals(500, AdaptiveLayout.centeredInsetPx(2560, 60, 1440))
        assertEquals(0, AdaptiveLayout.centeredInsetPx(0, 0, 100))
    }

    @Test
    fun `degenerate widths still return a usable grid`() {
        assertEquals(1, AdaptiveLayout.columnsFor(0, 10, 132, 104, 168))
        assertEquals(1, AdaptiveLayout.columnsFor(-100, 10, 132, 104, 168))
        assertEquals(0, AdaptiveLayout.coverWidthDp(0, 10, 1))
        for (width in listOf(1, 60, 120, 240, 360, 600, 900)) {
            assertTrue(AdaptiveLayout.shelfColumns(width) >= 1)
            assertTrue(AdaptiveLayout.searchColumns(width) >= 1)
        }
    }
}
