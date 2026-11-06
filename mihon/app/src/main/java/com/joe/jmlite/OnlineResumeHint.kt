package com.joe.jmlite

import org.json.JSONObject
import tachiyomi.domain.chapter.model.Chapter

/** Describes the same saved chapter used by the existing resume action. */
internal object OnlineResumeHint {
    fun text(chapters:List<Chapter>,lastUrl:String,lastName:String,progress:JSONObject):String {
        val chapter=chapters.find{lastUrl.isNotEmpty()&&it.url==lastUrl}
            ?: chapters.firstOrNull{it.lastPageRead>0&&!it.read}
        val name=chapter?.name?.takeIf{it.isNotBlank()} ?: lastName.takeIf{lastUrl.isNotEmpty()}.orEmpty()
        if(name.isBlank())return ""
        val saved=progress.optJSONObject(chapter?.url ?: lastUrl)
        val total=saved?.optInt("total") ?: 0
        val page=(chapter?.lastPageRead ?: saved?.optInt("page") ?: 0).coerceAtLeast(0).toLong()+1
        val read=chapter?.read ?: saved?.optBoolean("read") ?: false
        val position=when {read->"已读完";total>0->"已读 ${minOf(page,total.toLong())}/$total 页";else->"已读 $page 页"}
        return "上次阅读：$name · $position"
    }
}
