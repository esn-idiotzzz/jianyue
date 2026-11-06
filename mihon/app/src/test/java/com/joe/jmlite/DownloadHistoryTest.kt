package com.joe.jmlite

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DownloadHistoryTest {
    private fun local(id: String = "123", time: Long = 100, status: String = "complete") =
        JSONObject().put("id", id).put("title", "漫画$id").put("done", 20).put("total", 20)
            .put("status", status).put("updated", time)

    private fun web(chapter: String = "7", time: Long = 100) = JSONObject()
        .put("sourceId", 99L).put("url", "/manga/story").put("title", "全网漫画")
        .put("sourceName", "图源").put("thumbnail", "https://example.org/cover.jpg")
        .put("chapterId", chapter).put("chapterName", "第$chapter 章").put("chapterUrl", "/chapter/$chapter")
        .put("completedAt", time)

    @Test fun `legacy migration includes only completed downloads and preserves metadata time`() {
        val state = JSONObject()
        assertTrue(DownloadHistory.migrate(state, listOf(local(), local("456", 200, "paused"))))
        val rows = DownloadHistory.sorted(state, 0)
        assertEquals(1, rows.size)
        assertEquals("download:123", rows.single().getString("key"))
        assertEquals("漫画123", rows.single().getString("title"))
        assertEquals(20, rows.single().getInt("done"))
        assertEquals(20, rows.single().getInt("total"))
        assertEquals(100, rows.single().getLong("completedAt"))
        assertTrue(rows.single().getBoolean("download_history"))
    }

    @Test fun `migration runs once and normal reads never restore a manually deleted record`() {
        val state = JSONObject()
        DownloadHistory.migrate(state, listOf(local()))
        DownloadHistory.removeRecord(state, "123")
        val restarted = JSONObject(state.toString())
        assertFalse(DownloadHistory.migrate(restarted, listOf(local(), local("456", 200))))
        assertTrue(DownloadHistory.sorted(restarted, 0).isEmpty())
        assertTrue(DownloadHistory.sorted(restarted, 3).isEmpty())
    }

    @Test fun `migration respects deletion markers even when importing remaining local files`() {
        val state = JSONObject()
        DownloadHistory.removeRecord(state, "download:123")
        DownloadHistory.migrate(state, listOf(local(), local("456", 200)))
        assertEquals(listOf("456"), DownloadHistory.sorted(state, 0).map { it.getString("id") })
    }

    @Test fun `preserving a local deletion cannot resurrect an explicitly removed history entry`() {
        val state = JSONObject()
        val metadata = local()
        DownloadHistory.migrate(state, listOf(metadata))
        DownloadHistory.removeRecord(state, "123")
        DownloadHistory.upsert(state, DownloadHistory.jmRecord(metadata), false)
        assertTrue(DownloadHistory.sorted(state, 0).isEmpty())
        assertEquals("complete", metadata.getString("status"))
        assertEquals("漫画123", metadata.getString("title"))
    }

    @Test fun `a genuinely completed new download can create a new record after manual removal`() {
        val state = JSONObject()
        DownloadHistory.migrate(state, listOf(local()))
        DownloadHistory.removeRecord(state, "download:123")
        DownloadHistory.upsert(state, DownloadHistory.jmRecord(local(time = 300)), true)
        val row = DownloadHistory.sorted(JSONObject(state.toString()), 0).single()
        assertEquals("123", row.getString("id"))
        assertEquals(300, row.getLong("completedAt"))
        assertEquals(0, state.getJSONArray("removed").length())
    }

    @Test fun `recent three and all share descending completion order without changing stored items`() {
        val state = JSONObject()
        DownloadHistory.migrate(state, listOf(local("1", 100), local("2", 400), local("3", 200), local("4", 300)))
        assertEquals(listOf("2", "4", "3"), DownloadHistory.sorted(state, 3).map { it.getString("id") })
        assertEquals(listOf("2", "4", "3", "1"), DownloadHistory.sorted(state, 0).map { it.getString("id") })
        assertEquals(4, state.getJSONArray("items").length())
    }

    @Test fun `completion timestamp takes priority over later local metadata updates`() {
        val metadata = local(time = 999).put("completedAt", 100)
        assertEquals(100, DownloadHistory.jmRecord(metadata).getLong("updated"))
    }

    @Test fun `history snapshots are independent of local book metadata and returned rows`() {
        val state = JSONObject()
        val metadata = local()
        DownloadHistory.migrate(state, listOf(metadata))
        metadata.put("title", "changed").put("status", "error")
        DownloadHistory.sorted(state, 0).single().put("title", "also changed")
        val saved = DownloadHistory.sorted(JSONObject(state.toString()), 0).single()
        assertEquals("漫画123", saved.getString("title"))
        assertEquals("complete", saved.getString("status"))
    }

    @Test fun `web records use source and url identity and retain remote detail fields`() {
        val record = DownloadHistory.onlineRecord(web())
        assertEquals("99|/manga/story", record.getString("key"))
        assertEquals("web", record.getString("kind"))
        assertEquals("online", record.getString("type"))
        assertEquals("https://example.org/cover.jpg", record.getString("thumbnail"))
        assertEquals("图源", record.getString("sourceName"))
        assertFalse(record.has("id"))
    }

    @Test fun `web deletion markers survive reads but allow a later genuine completion`() {
        val state = JSONObject()
        val record = DownloadHistory.onlineRecord(web())
        DownloadHistory.upsert(state, record, true)
        DownloadHistory.removeRecord(state, "99|/manga/story")
        DownloadHistory.upsert(state, record, false)
        assertTrue(DownloadHistory.sorted(state, 0).isEmpty())
        DownloadHistory.mergeOnline(state, web(time = 500))
        assertEquals(500, DownloadHistory.sorted(state, 0).single().getLong("updated"))
    }

    @Test fun `completed chapters deduplicate by chapter id and never masquerade as page counts`() {
        val state = JSONObject()
        DownloadHistory.mergeOnline(state, web())
        DownloadHistory.mergeOnline(state, web(time = 200))
        assertEquals(1, DownloadHistory.sorted(state, 0).single().getInt("downloadedChapters"))
        DownloadHistory.mergeOnline(state, web("8", 300))
        val record = DownloadHistory.sorted(state, 0).single()
        assertEquals(2, record.getInt("downloadedChapters"))
        assertEquals(2, record.getJSONArray("chapterIds").length())
        assertEquals(300, record.getLong("completedAt"))
        assertFalse(record.has("done"))
        assertFalse(record.has("total"))
    }
}
