package com.joe.jmlite

import android.app.Activity
import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

object SearchRanking {
    private fun clean(s:String)=java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).lowercase().filter{it.isLetterOrDigit()}
    fun rank(query:String,title:String):Int {
        val q=clean(query);val t=clean(title)
        if(q==t)return 0
        if(q.isEmpty())return 2
        var i=0;for(c in t)if(i<q.length&&q[i]==c)i++
        return if(i==q.length || (t.isNotEmpty()&&q.contains(t))) 1 else 2
    }
}
object ChapterAvailability {
    private val slots=Semaphore(3)
    private fun key(id:Long,url:String)="$id|$url"
    fun empty(c:Context,id:Long,url:String):Boolean {val time=c.getSharedPreferences("empty_chapters",0).getLong(key(id,url),0);return time>0&&System.currentTimeMillis()-time<30*60_000}
    fun mark(c:Context,id:Long,url:String,empty:Boolean){val edit=c.getSharedPreferences("empty_chapters",0).edit();if(empty)edit.putLong(key(id,url),System.currentTimeMillis()) else edit.remove(key(id,url));edit.apply()}
    suspend fun probe(c:Context,item:OnlineRepository.Item):Boolean?=withContext(Dispatchers.IO){slots.withPermit {
        try {withTimeout(8_000){
            val source=BundledSources.ensure(c){}.find{it.id==item.sourceId}?:return@withTimeout null
            val manga=SManga.create().also{it.url=item.url;it.title=item.title}
            val missing=confirmedEmpty(source,manga)
            if(missing!=null)mark(c,item.sourceId,item.url,missing)
            missing
        }}catch(e:TimeoutCancellationException){null}catch(e:CancellationException){throw e}catch(e:Exception){null}
    }}
    internal suspend fun confirmedEmpty(source:eu.kanade.tachiyomi.source.online.HttpSource,manga:SManga):Boolean? {
        return try {withTimeout(8_000){source.getMangaUpdate(manga,emptyList(),false,true).chapters.isEmpty()}}
        catch(e:TimeoutCancellationException){null}
        catch(e:tachiyomi.domain.chapter.model.NoChaptersException){true}
        catch(e:CancellationException){throw e}
        catch(e:Exception){null}
    }
}
object FavoriteActions {
    @JvmStatic fun show(activity:Activity,record:JSONObject){
        val library=Library(activity)
        val scope=CoroutineScope(Dispatchers.Main+SupervisorJob()+CoroutineExceptionHandler{_,e->if(!activity.isDestroyed)android.widget.Toast.makeText(activity,"收藏失败：${e.message}",1).show()})
        scope.launch {
            val favorite=withContext(Dispatchers.IO){library.hasOnline(record.optString("key"))}
            if(activity.isDestroyed||activity.isFinishing)return@launch
            val folder=if(OnlineHistory.isJm(record)) "在线JM漫画" else "在线全网漫画"
            PredictiveBack.dialog(activity).setTitle(record.optString("title")).setItems(arrayOf(if(favorite) "♥ 取消收藏" else "♡ 收藏")){_,_->scope.launch{
                withContext(Dispatchers.IO){library.favoriteOnline(record,!favorite)}
                if(!activity.isDestroyed)android.widget.Toast.makeText(activity,if(favorite) "已取消收藏" else "已加入书架 · $folder",0).show()
            }}.show()
        }
    }
}
