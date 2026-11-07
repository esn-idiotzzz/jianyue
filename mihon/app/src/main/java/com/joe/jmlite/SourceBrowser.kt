package com.joe.jmlite

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph

/** Resolves a repository entry to its actual, loaded source and keeps requests scoped to it. */
internal object SourceBrowser {
    data class Page(val items:List<OnlineRepository.Item>,val hasNext:Boolean)

    fun select(sources:List<HttpSource>,id:Long,name:String,lang:String):HttpSource {
        sources.singleOrNull { it.id==id }?.let{return it}
        val candidates=sources.filter{lang.isBlank()||it.lang.equals(lang,true)}
        candidates.singleOrNull{it.name==name}?.let{return it}
        if(candidates.size==1)return candidates.single()
        throw IllegalStateException("无法匹配图源：$name，请返回仓库重试")
    }

    suspend fun resolve(context:Context,id:Long,name:String,lang:String,pkg:String):HttpSource=withContext(Dispatchers.IO){
        val all=BundledSources.ensure(context){}
        val sources=if(pkg.isBlank()) all else {
            val manager=context.applicationContext.appGraph.extensionManager
            val extension=manager.getLoadedExtensions().find{it.pkgName==pkg}
            if(extension==null){
                val reason=manager.getNotLoadedExtensions().find{it.pkgName==pkg}?.reason
                throw IllegalStateException("图源未加载：$name${reason?.let{" · $it"}.orEmpty()}")
            }
            extension.sources.filterIsInstance<HttpSource>().filter{it.lang.startsWith("zh",true)}
        }
        select(sources,id,name,lang)
    }

    class Session(val source:HttpSource) {
        private var catalogueMode:Int?=null
        private suspend fun requestCatalogue(page:Int):MangasPage {
            when(catalogueMode){
                0->return withTimeout(12_000){source.getPopularManga(page)}
                1->return withTimeout(12_000){source.getLatestUpdates(page)}
                2->return withTimeout(12_000){source.getSearchManga(page,"",source.getFilterList())}
            }
            var first:Exception?=null
            try {
                val result=withTimeout(12_000){source.getPopularManga(page)}
                if(result.mangas.isNotEmpty()){catalogueMode=0;return result}
            }catch(e:kotlinx.coroutines.TimeoutCancellationException){first=e}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(SourceLogin.required(e))throw e;first=e}
            if(source.supportsLatest){
                try {val result=withTimeout(12_000){source.getLatestUpdates(page)};catalogueMode=1;return result}
                catch(e:kotlinx.coroutines.TimeoutCancellationException){if(first==null)first=e}
                catch(e:CancellationException){throw e}
                catch(e:Exception){if(SourceLogin.required(e))throw e;if(first==null)first=e}
            }
            try {val result=withTimeout(12_000){source.getSearchManga(page,"",source.getFilterList())};catalogueMode=2;return result}
            catch(e:CancellationException){throw e}
            catch(e:Exception){throw java.io.IOException("无法浏览 ${source.name}：${first?.message?:e.message?:e.javaClass.simpleName}",e)}
        }
        suspend fun page(query:String,page:Int):Page=withContext(Dispatchers.IO){
            val result=if(query.isBlank())requestCatalogue(page) else withTimeout(20_000){source.getSearchManga(page,query,source.getFilterList())}
            Page(result.mangas.filter{it.url.isNotBlank()}.distinctBy{it.url}.map { manga ->
                val thumbnail=manga.thumbnail_url?.takeIf{it.isNotBlank()}?.let{java.net.URI(source.baseUrl).resolve(it).toString()}
                OnlineRepository.Item(source.id,source.name,manga.url,manga.title.ifBlank{"未命名"},thumbnail,manga.author,manga.artist,manga.description)
            },result.hasNextPage)
        }
    }
}
