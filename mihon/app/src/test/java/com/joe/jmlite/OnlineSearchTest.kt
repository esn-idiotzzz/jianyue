package com.joe.jmlite

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSearchTest {
    private fun source(id: Long, filters: FilterList = FilterList()): HttpSource = mockk {
        every { this@mockk.id } returns id
        every { name } returns "图源$id"
        every { baseUrl } returns "https://source$id.example"
        every { getFilterList() } returns filters
    }

    private fun manga(title: String, url: String): SManga = SManga.create().also {
        it.title = title
        it.url = url
    }

    @Test
    fun `one source timing out does not cancel successful sources`() = runTest {
        val hanging = source(1)
        val working = source(2)
        coEvery { hanging.getSearchManga(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { working.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(manga("有效结果", "/book/1")), false)
        val items = mutableListOf<OnlineRepository.Item>()

        val summary = OnlineRepository.searchSources(listOf(hanging, working), "漫画", {}, items::add)

        assertEquals(OnlineRepository.SearchSummary(2, 1, 1, listOf(1)), summary)
        assertEquals(listOf("有效结果"), items.map { it.title })
    }

    @Test
    fun `a source exception is isolated from other sources`() = runTest {
        val broken = source(1)
        val first = source(2)
        val second = source(3)
        coEvery { broken.getSearchManga(any(), any(), any()) } throws IOException("source unavailable")
        coEvery { first.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(manga("结果一", "/one")), false)
        coEvery { second.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(manga("结果二", "/two")), false)
        val items = mutableListOf<OnlineRepository.Item>()

        val summary = OnlineRepository.searchSources(listOf(broken, first, second), "漫画", {}, items::add)

        assertEquals(OnlineRepository.SearchSummary(3, 2, 1, listOf(1)), summary)
        assertEquals(setOf("结果一", "结果二"), items.map { it.title }.toSet())
    }

    @Test
    fun `all failed sources are reported as failures`() = runTest {
        val broken = source(1)
        val hanging = source(2)
        coEvery { broken.getSearchManga(any(), any(), any()) } throws IOException("network error")
        coEvery { hanging.getSearchManga(any(), any(), any()) } coAnswers { awaitCancellation() }
        val items = mutableListOf<OnlineRepository.Item>()

        val summary = OnlineRepository.searchSources(listOf(broken, hanging), "漫画", {}, items::add)

        assertEquals(OnlineRepository.SearchSummary(2, 0, 2, listOf(1, 2)), summary)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `results are delivered before the final source completes and remain available`() = runTest {
        val fast = source(1)
        val slow = source(2)
        coEvery { fast.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(manga("先到结果", "/fast")), false)
        coEvery { slow.getSearchManga(any(), any(), any()) } coAnswers {
            delay(1_000)
            MangasPage(listOf(manga("后到结果", "/slow")), false)
        }
        val items = mutableListOf<OnlineRepository.Item>()
        val pending = async {
            OnlineRepository.searchSources(listOf(fast, slow), "漫画", {}, items::add)
        }

        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(listOf("先到结果"), items.map { it.title })
        advanceUntilIdle()
        assertEquals(OnlineRepository.SearchSummary(2, 2, 0), pending.await())
        assertEquals(listOf("先到结果", "后到结果"), items.map { it.title })
    }

    @Test
    fun `duplicate urls are deduplicated per source without merging different sources`() = runTest {
        val first = source(1)
        val second = source(2)
        coEvery { first.getSearchManga(any(), any(), any()) } returns MangasPage(
            listOf(manga("第一来源", "/same"), manga("重复条目", "/same"), manga("无效条目", " ")),
            false,
        )
        coEvery { second.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(manga("第二来源", "/same")), false)
        val items = mutableListOf<OnlineRepository.Item>()

        val summary = OnlineRepository.searchSources(listOf(first, second), "漫画", {}, items::add)

        assertEquals(OnlineRepository.SearchSummary(2, 2, 0), summary)
        assertEquals(2, items.size)
        assertEquals(setOf(1L, 2L), items.map { it.sourceId }.toSet())
        assertTrue(items.all { it.url == "/same" })
    }

    @Test
    fun `external cancellation reaches in flight sources and is not reported as success`() = runTest {
        val hanging = source(1)
        var sourceCancelled = false
        coEvery { hanging.getSearchManga(any(), any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                sourceCancelled = true
            }
        }
        val pending = async {
            OnlineRepository.searchSources(listOf(hanging), "漫画", {}, {})
        }
        runCurrent()

        pending.cancel(CancellationException("search page closed"))
        val failure = try {
            pending.await()
            null
        } catch (error: CancellationException) {
            error
        }

        assertNotNull(failure)
        assertTrue(pending.isCancelled)
        assertTrue(sourceCancelled)
    }

    @Test
    fun `search passes the source default filters and the original query`() = runTest {
        val filters = FilterList(object : Filter.Text("语言", "zh") {})
        val working = source(1, filters)
        var receivedFilters: FilterList? = null
        coEvery { working.getSearchManga(any(), any(), any()) } coAnswers {
            receivedFilters = thirdArg()
            MangasPage(emptyList(), false)
        }

        val summary = OnlineRepository.searchSources(listOf(working), "海贼王", {}, {})

        assertEquals(OnlineRepository.SearchSummary(1, 1, 0), summary)
        assertSame(filters, receivedFilters)
        verify(exactly = 1) { working.getFilterList() }
        coVerify(exactly = 1) { working.getSearchManga(1, "海贼王", any()) }
    }

    @Test
    fun `eight slow sources do not hold the next fast source behind their timeouts`() = runTest {
        val slow = (1L..8L).map { source(it) }
        slow.forEach { source -> coEvery { source.getSearchManga(any(), any(), any()) } coAnswers { awaitCancellation() } }
        val fast = source(9)
        coEvery { fast.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(manga("快源结果", "/fast")), false)
        val items = mutableListOf<OnlineRepository.Item>()
        val pending = async { OnlineRepository.searchSources(slow + fast, "漫画", {}, items::add) }

        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(listOf("快源结果"), items.map { it.title })
        advanceUntilIdle()
        assertEquals(OnlineRepository.SearchSummary(9, 1, 8, (1L..8L).toList()), pending.await())
    }

    @Test
    fun `failed source identities follow repository order regardless of completion order`() = runTest {
        val slow = source(11)
        val fast = source(22)
        val empty = source(33)
        coEvery { slow.getSearchManga(any(), any(), any()) } coAnswers {
            delay(1_000)
            throw IOException("late failure")
        }
        coEvery { fast.getSearchManga(any(), any(), any()) } throws IOException("early failure")
        coEvery { empty.getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)

        val summary = OnlineRepository.searchSources(listOf(slow, fast, empty), "漫画", {}, {})

        assertEquals(listOf(11L, 22L), summary.failedSourceIds)
        assertEquals(summary.failed, summary.failedSourceIds.size)
        assertEquals(1, summary.succeeded)
    }

    @Test
    fun `source search concurrency remains bounded`() = runTest {
        val sources = (1L..32L).map { source(it) }
        var active = 0
        var peak = 0
        sources.forEach { source ->
            coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                active++; peak = maxOf(peak, active)
                try { delay(1_000); MangasPage(emptyList(), false) } finally { active-- }
            }
        }
        val pending = async { OnlineRepository.searchSources(sources, "漫画", {}, {}) }
        runCurrent()
        assertEquals(16, active)
        advanceUntilIdle()
        assertEquals(16, peak)
        assertEquals(OnlineRepository.SearchSummary(32, 32, 0), pending.await())
    }
}
