package com.joe.jmlite

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.WeakHashMap

/** Reads the extension's packaged launcher icon; never fetches remote images or initializes sources. */
internal class SourceIcons(context:Context):AutoCloseable {
    private val app=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val slots=Semaphore(2)
    private val jobs=WeakHashMap<ImageView,Job>()
    private val cache=LruCache<String,Drawable.ConstantState>(32)
    fun load(view:ImageView,pkg:String){
        if(view.tag==pkg&&view.drawable!=null)return
        jobs.remove(view)?.cancel();view.tag=pkg;view.setImageDrawable(null)
        cache.get(pkg)?.let{view.setImageDrawable(it.newDrawable(app.resources));return}
        jobs[view]=scope.launch {
            val state=withContext(Dispatchers.IO){slots.withPermit {
                cache.get(pkg)?:runCatching {
                    BundledSources.prepareFiles(app)
                    val path=File(app.filesDir,"exts/$pkg.ext").absolutePath
                    val info=app.packageManager.getPackageArchiveInfo(path,PackageManager.GET_META_DATA)?.applicationInfo?:return@runCatching null
                    info.sourceDir=path;info.publicSourceDir=path
                    app.packageManager.getResourcesForApplication(info).getDrawable(info.icon,null).constantState
                }.getOrNull()?.also{cache.put(pkg,it)}
            }}
            if(view.tag==pkg&&state!=null)view.setImageDrawable(state.newDrawable(app.resources))
        }
    }
    override fun close(){scope.cancel();jobs.clear();cache.evictAll()}
}
