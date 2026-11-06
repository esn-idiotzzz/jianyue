package com.joe.jmlite

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MainNavigationTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun root(tab: Int): MainActivity {
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.onBackPressed() } answers { callOriginal() }
        MainActivity::class.java.getDeclaredField("tab").apply { isAccessible = true }.setInt(activity, tab)
        MainActivity::class.java.getDeclaredField("folderId").apply { isAccessible = true }.set(activity, "")
        return activity
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `each root tab moves task to background without finishing`(tab: Int) {
        val activity = root(tab)
        activity.onBackPressed()
        verify(exactly = 1) { activity.moveTaskToBack(true) }
        verify(exactly = 0) { activity.finish() }
    }

    @Test fun `expanded shelf search closes before returning to desktop`() {
        val activity = root(1)
        val search = mockk<HeaderSearch>(relaxed = true)
        every { search.isExpanded } returnsMany listOf(true, false)
        MainActivity::class.java.getDeclaredField("shelfSearch").apply { isAccessible = true }.set(activity, search)
        activity.onBackPressed()
        verify(exactly = 1) { search.collapse() }
        verify(exactly = 0) { activity.moveTaskToBack(any()) }
        activity.onBackPressed()
        verify(exactly = 1) { activity.moveTaskToBack(true) }
    }
}
