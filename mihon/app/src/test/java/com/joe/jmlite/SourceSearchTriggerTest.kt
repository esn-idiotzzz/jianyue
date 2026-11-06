package com.joe.jmlite

import android.text.Editable
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchTriggerTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun field(activity: OnlineSearchActivity, name: String, value: Any) {
        OnlineSearchActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun page(activity: OnlineSearchActivity): Int =
        OnlineSearchActivity::class.java.getDeclaredField("currentPage").apply { isAccessible = true }.getInt(activity)

    private data class Browser(
        val activity: OnlineSearchActivity,
        val session: SourceBrowser.Session,
        val results: ArrayList<OnlineRepository.Item>,
    ) {
        fun load(reset: Boolean = true, retry: Boolean = false) {
            OnlineSearchActivity::class.java.getDeclaredMethod(
                "loadSource", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(activity, reset, retry)
        }
    }

    private fun browser(scope: CoroutineScope, loaded: Int = 0, activeQuery: String = ""): Browser {
        val activity = spyk(mockk<OnlineSearchActivity>(relaxed = true), recordPrivateCalls = true)
        val input = mockk<EditText>(relaxed = true)
        val text = mockk<Editable>()
        every { input.text } returns text
        every { text.toString() } returns ""
        val session = mockk<SourceBrowser.Session>()
        coEvery { session.page(any(), any()) } returns SourceBrowser.Page(emptyList(), false)
        val results = ArrayList<OnlineRepository.Item>()
        field(activity, "scope", scope)
        field(activity, "queryInput", input)
        field(activity, "status", mockk<TextView>(relaxed = true))
        field(activity, "grid", mockk<GridView>(relaxed = true))
        field(activity, "adapter", mockkClass(Class.forName("com.joe.jmlite.OnlineSearchActivity\$ResultAdapter").kotlin, relaxed = true))
        field(activity, "sourceSession", session)
        field(activity, "results", results)
        field(activity, "scores", HashMap<String, Int>())
        field(activity, "itemKeys", HashSet<String>())
        field(activity, "currentPage", loaded)
        field(activity, "activeQuery", activeQuery)
        field(activity, "hasNext", true)
        return Browser(activity, session, results)
    }

    @Test fun `first empty search loads the popular first page`() = runTest {
        val browser = browser(backgroundScope)
        browser.load()
        runCurrent()
        coVerify(exactly = 1) { browser.session.page("", 1) }
        assertEquals(1, page(browser.activity))
    }

    @Test fun `repeated empty search preserves loaded popular pages and cards`() = runTest {
        val browser = browser(backgroundScope, loaded = 2)
        val existing = OnlineRepository.Item(1, "图源", "/book", "已有热门", null, null, null, null)
        browser.results.add(existing)
        repeat(3) { browser.load() }
        runCurrent()
        coVerify(exactly = 0) { browser.session.page(any(), any()) }
        assertEquals(listOf(existing), browser.results)
        assertEquals(2, page(browser.activity))
    }

    @Test fun `repeated empty search does not cancel and restart an in flight popular request`() = runTest {
        val browser = browser(backgroundScope)
        coEvery { browser.session.page("", 1) } coAnswers { awaitCancellation() }
        browser.load()
        runCurrent()
        repeat(3) { browser.load() }
        runCurrent()
        coVerify(exactly = 1) { browser.session.page("", 1) }
    }

    @Test fun `clearing a real query returns to popular only once`() = runTest {
        val browser = browser(backgroundScope, loaded = 1, activeQuery = "漫画名")
        browser.load()
        browser.load()
        runCurrent()
        browser.load()
        runCurrent()
        coVerify(exactly = 1) { browser.session.page("", 1) }
        assertEquals(1, page(browser.activity))
    }

    @Test fun `popular pagination still requests the next page`() = runTest {
        val browser = browser(backgroundScope, loaded = 1)
        browser.load(reset = false)
        runCurrent()
        coVerify(exactly = 1) { browser.session.page("", 2) }
        assertEquals(2, page(browser.activity))
    }

    @Test fun `explicit retry can reload a completed empty popular page`() = runTest {
        val browser = browser(backgroundScope, loaded = 1)
        browser.load(retry = true)
        runCurrent()
        coVerify(exactly = 1) { browser.session.page("", 1) }
    }

    @Test fun `failed first page remains explicitly retryable`() = runTest {
        val browser = browser(backgroundScope)
        coEvery { browser.session.page("", 1) } throws IOException("offline")
        browser.load()
        runCurrent()
        browser.load(retry = true)
        runCurrent()
        coVerify(exactly = 2) { browser.session.page("", 1) }
        assertEquals(0, page(browser.activity))
    }
}
