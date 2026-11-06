package com.joe.jmlite

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class OnlineResumeHintTest {
    private fun chapter(url:String,name:String,page:Int=0,read:Boolean=false)=Chapter.create().copy(url=url,name=name,lastPageRead=page,read=read)

    @Test fun `saved chapter url wins over other partially read chapters`() {
        val chapters=listOf(chapter("/other","其他章节",8),chapter("/last","第十二话",4))
        val progress=JSONObject().put("/last",JSONObject().put("total",20))
        assertEquals("上次阅读：第十二话 · 已读 5/20 页",OnlineResumeHint.text(chapters,"/last","",progress))
    }
    @Test fun `database progress can still identify resume chapter without an older history record`() {
        assertEquals("上次阅读：第七话 · 已读 3 页",OnlineResumeHint.text(listOf(chapter("/7","第七话",2)),"","",JSONObject()))
    }
    @Test fun `a brand new manga does not invent a previous reading position`() {
        assertEquals("",OnlineResumeHint.text(listOf(chapter("/1","第一话")),"","",JSONObject()))
    }
    @Test fun `cached history retains the chapter name and page when the current source omits it`() {
        val progress=JSONObject().put("/missing",JSONObject().put("page",6).put("total",15))
        assertEquals("上次阅读：缓存章节 · 已读 7/15 页",OnlineResumeHint.text(emptyList(),"/missing","缓存章节",progress))
    }
    @Test fun `finished chapters are described as finished`() {
        assertEquals("上次阅读：最终话 · 已读完",OnlineResumeHint.text(listOf(chapter("/end","最终话",20,true)),"/end","",JSONObject()))
    }
    @Test fun `known page totals clamp stale progress without changing the resume target`() {
        val progress=JSONObject().put("/3",JSONObject().put("total",10))
        assertEquals("上次阅读：第三话 · 已读 10/10 页",OnlineResumeHint.text(listOf(chapter("/3","第三话",20)),"/3","",progress))
    }
}
