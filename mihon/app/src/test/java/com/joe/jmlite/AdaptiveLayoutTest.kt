package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guards the phone/tablet layout boundary. The tests pass the same window widths, content widths
 * and device-class flag the screens use, so the phone layout stays frozen while tablet windows
 * follow the responsive rules.
 */
class AdaptiveLayoutTest {
    private fun shelf(widthDp: Int, tablet: Boolean) = AdaptiveLayout.shelfColumns(
        widthDp,
        widthDp - AdaptiveLayout.SHELF_PAGE_PADDING_DP * 2,
        tablet,
    )

    private fun search(widthDp: Int, tablet: Boolean) = AdaptiveLayout.searchColumns(
        widthDp,
        widthDp - AdaptiveLayout.SEARCH_PAGE_PADDING_DP * 2,
        tablet,
    )

    @Test
    fun `phone devices keep the original grid at every window width`() {
        for (width in 320..3000) {
            assertEquals(
                maxOf(3, minOf(6, width / 120)),
                shelf(width, false),
                "phone shelf width $width must keep its original column count",
            )
            assertEquals(3, search(width, false), "phone search width $width")
        }
        assertEquals(3, shelf(360, false))
        assertEquals(3, shelf(411, false))
        assertEquals(4, shelf(480, false))
        assertEquals(6, shelf(800, false))
    }

    @Test
    fun `tablet windows below the breakpoint keep the phone grid`() {
        assertFalse(AdaptiveLayout.isTabletWidth(599))
        assertTrue(AdaptiveLayout.isTabletWidth(600))
        assertEquals(3, shelf(320, true))
        assertEquals(4, shelf(599, true))
        assertEquals(3, search(599, true))
    }

    @Test
    fun `tablet windows at or above the breakpoint get responsive columns`() {
        assertTrue(shelf(600, true) >= 4)
        assertTrue(search(600, true) >= 4)
        assertTrue(shelf(800, true) > shelf(600, true))
    }

    @Test
    fun `tablet shelf columns grow with the window and keep every cover in range`() {
        var previous = 0
        for (width in 600..3000) {
            val content = width - AdaptiveLayout.SHELF_PAGE_PADDING_DP * 2
            val columns = shelf(width, true)
            assertTrue(columns >= previous, "shelf columns must not shrink: width=$width")
            val cover = AdaptiveLayout.coverWidthDp(content, AdaptiveLayout.SHELF_SPACING_DP, columns)
            assertTrue(cover in 104..168, "width=$width columns=$columns cover=${cover}dp")
            previous = columns
        }
    }

    @Test
    fun `tablet search columns grow with the window and keep every cover in range`() {
        var previous = 0
        for (width in 600..3000) {
            val content = width - AdaptiveLayout.SEARCH_PAGE_PADDING_DP * 2
            val columns = search(width, true)
            assertTrue(columns >= previous, "search columns must not shrink: width=$width")
            val cover = AdaptiveLayout.coverWidthDp(content, AdaptiveLayout.SEARCH_SPACING_DP, columns)
            assertTrue(cover in 104..176, "width=$width columns=$columns cover=${cover}dp")
            previous = columns
        }
    }

    @Test
    fun `landscape and portrait tablet windows both fit more than the phone grid`() {
        val portrait = shelf(800, true)
        val landscape = shelf(1280, true)
        assertTrue(portrait > 3, "800dp tablet portrait should show more than the phone's 3 columns")
        assertTrue(landscape > portrait, "1280dp tablet landscape should show more columns")
        assertTrue(shelf(1024, true) >= portrait)
        assertTrue(search(1280, true) > search(800, true))
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
            assertTrue(shelf(width, true) >= 1)
            assertTrue(search(width, true) >= 1)
        }
    }
}
