package com.joe.jmlite

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BookSearchTest {
    private fun book(title: String, id: String = "", folder: String = "") =
        JSONObject().put("title", title).put("id", id).put("folder", folder)

    @Test fun `JM numbers support partial and prefixed searches`() {
        val record = book("示例漫画", "123456")
        assertTrue(BookSearch.matches(record, "234"))
        assertTrue(BookSearch.matches(record, " jM 123 "))
        assertFalse(BookSearch.matches(record, "987"))
    }

    @Test fun `online JM searches its saved URL number`() {
        val record = book("在线漫画").put("jm", true).put("sourceId", -1).put("url", "556677")
        assertTrue(BookSearch.matches(record, "JM556"))
    }

    @Test fun `web titles match text and case without treating source IDs as JM numbers`() {
        val record = book("中文 Manga", "123").put("sourceId", 99).put("url", "/comic/456")
        assertTrue(BookSearch.matches(record, "mAnGa"))
        assertTrue(BookSearch.matches(record, "中文"))
        assertFalse(BookSearch.matches(record, "123"))
        assertFalse(BookSearch.matches(record, "456"))
    }

    @Test fun `empty shelf search stays inside its folder`() {
        val root = book("根目录", "1")
        val nested = book("子目录", "2", "folder-a")
        assertEquals(listOf(root), BookSearch.shelf(listOf(root, nested), "", "  "))
        assertEquals(listOf(nested), BookSearch.shelf(listOf(root, nested), "folder-a", ""))
    }

    @Test fun `active search finds books across folders and preserves saved order`() {
        val first = book("匹配一", "1", "folder-a")
        val other = book("其他", "2")
        val last = book("匹配二", "3", "folder-b")
        assertEquals(listOf(first, last), BookSearch.shelf(listOf(first, other, last), "", "匹配"))
    }

    @Test fun `no match and empty identifiers return no unrelated books`() {
        val record = book("本地导入")
        assertFalse(BookSearch.matches(record, "123"))
        assertTrue(BookSearch.shelf(listOf(record), "", "不存在").isEmpty())
    }

    @Test fun `download summaries keep chapter and page units separate`() {
        assertEquals("28 页", DownloadRecordActions.summary(book("JM").put("done", 28)))
        val web = book("全网").put("sourceId", 77).put("done", 100).put("downloadedChapters", 3)
        assertEquals("已下载 3 章", DownloadRecordActions.summary(web))
        web.remove("downloadedChapters")
        assertEquals("全网漫画", DownloadRecordActions.summary(web))
    }
}
