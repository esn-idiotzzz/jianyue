package com.joe.jmlite

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.Context
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import org.json.JSONObject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter

object HistoryActions {
    @JvmStatic fun resume(activity:Activity,record:JSONObject) {
        val app=activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph=app.appGraph
                val jm=OnlineHistory.isJm(record)
                if(jm){
                    val local=Library(app).findLocalDownload(record.getString("url"))
                    if(local!=null){
                        withContext(Dispatchers.Main){if(!activity.isDestroyed&&!activity.isFinishing){
                            activity.startActivity(Intent(activity,MihonEntryActivity::class.java).putExtra("library_key",Library.key(local)).putExtra("title",local.optString("title",record.optString("title"))))
                        }}
                        return@launch
                    }
                }
                val url=if(jm) "jmlite://online/${Uri.encode(record.getString("url"))}" else record.getString("url")
                val source=if(jm) tachiyomi.source.local.LocalSource.ID else record.getLong("sourceId")
                if(!jm) BundledSources.ensure(app){}
                val manga=graph.jmMangaRepository.getMangaByUrlAndSourceId(url,source)
                val chapters=manga?.let{graph.jmChapterRepository.getChapterByMangaId(it.id)}.orEmpty()
                val chapter=chapters.find{it.url==record.optString("lastChapterUrl")} ?: chapters.find{it.lastPageRead>0&&!it.read} ?: chapters.maxByOrNull{it.sourceOrder}
                withContext(Dispatchers.Main){if(!activity.isDestroyed&&!activity.isFinishing){
                    if(manga!=null&&chapter!=null) OnlineRepository.openChapter(activity,manga.id,chapter.id,false)
                    else if(jm) activity.startActivity(Intent(activity,MihonEntryActivity::class.java).putExtra("online_id",record.getString("url")).putExtra("title",record.optString("title")))
                    else activity.startActivity(Intent(activity,OnlineMangaActivity::class.java).putExtra("sourceId",source).putExtra("url",url).putExtra("title",record.optString("title")))
                }}
            } catch(e:Exception){withContext(Dispatchers.Main){if(!activity.isDestroyed)android.widget.Toast.makeText(activity,"无法继续阅读：${e.message}",1).show()}}
        }
    }
    suspend fun record(context:Context,manga:Manga,chapter:Chapter,total:Int) = withContext(Dispatchers.IO) {
        runCatching {
            val uri=Uri.parse(manga.url)
            val jm=uri.scheme=="jmlite"&&uri.authority=="online"
            if(uri.scheme=="jmlite"&&!jm)return@runCatching
            val directJm=if(jm) null else DirectJmHistory.id(manga.id)
            if(directJm!=null){
                val history=OnlineHistory(context,true)
                val entry=history.find("-1",directJm)?:JSONObject()
                entry.put("sourceId",-1).put("url",directJm).put("key",OnlineHistory.key("-1",directJm))
                    .put("title",entry.optString("customTitle").ifBlank{manga.title}).put("thumbnail",manga.thumbnailUrl?:entry.optString("thumbnail"))
                    .put("jm",true).put("type","online").put("lastChapterUrl",chapter.url).put("lastChapterName",chapter.name)
                    .put("progress",chapter.lastPageRead).put("totalPages",total).put("read",chapter.read)
                val progress=entry.optJSONObject("chapterProgress")?:JSONObject()
                progress.put(chapter.url,JSONObject().put("page",chapter.lastPageRead).put("total",total).put("read",chapter.read))
                entry.put("chapterProgress",progress);history.save(entry);return@runCatching
            }
            val source=if(jm) -1L else manga.source
            val url=if(jm) uri.lastPathSegment.orEmpty() else manga.url
            val history=OnlineHistory(context,jm)
            val entry=history.find(source.toString(),url)?:JSONObject()
            entry.put("sourceId",source).put("url",url).put("key",OnlineHistory.key(source.toString(),url))
                .put("title",entry.optString("customTitle").ifBlank{if(jm)entry.optString("title").ifBlank{manga.title}else manga.title}).put("thumbnail",manga.thumbnailUrl?:entry.optString("thumbnail"))
                .put("jm",jm).put("type","online").put("lastChapterUrl",chapter.url).put("lastChapterName",chapter.name)
                .put("progress",chapter.lastPageRead).put("totalPages",total).put("read",chapter.read)
            val progress=entry.optJSONObject("chapterProgress")?:JSONObject()
            progress.put(chapter.url,JSONObject().put("page",chapter.lastPageRead).put("total",total).put("read",chapter.read))
            entry.put("chapterProgress",progress)
            history.save(entry)
        }; Unit
    }
}
