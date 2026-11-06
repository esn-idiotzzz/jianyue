package com.joe.jmlite

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DownloadPrivacyTest {
    @Test fun `JM hidden titles keep the existing number display`() {
        val book=JSONObject().put("id","12345").put("title","JM书名")
        assertEquals("12345",DownloadRecordActions.displayTitle(book,false))
        assertEquals("JM书名",DownloadRecordActions.displayTitle(book,true))
    }
    @Test fun `a hidden web record displays its source instead of the manga title`() {
        val book=JSONObject().put("sourceId",99).put("title","私密书名").put("sourceName","测试图源")
        assertEquals("测试图源",DownloadRecordActions.displayTitle(book,false))
        assertEquals("私密书名",DownloadRecordActions.displayTitle(book,true))
    }
    @Test fun `missing source names have a readable fallback without exposing the book name`() {
        val book=JSONObject().put("sourceId",99).put("title","私密书名")
        assertEquals("全网漫画",DownloadRecordActions.displayTitle(book,false))
        book.put("sourceName"," ")
        assertEquals("全网漫画",DownloadRecordActions.displayTitle(book,false))
    }
    @Test fun `JM online history still uses the JM number when concealed`() {
        val book=JSONObject().put("id","23456").put("sourceId",-1).put("jm",true).put("title","JM书名")
        assertEquals("23456",DownloadRecordActions.displayTitle(book,false))
    }
}
