package com.joe.jmlite

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * JM Lite's adapter for Mihon's PageLoader API. It owns no Activity or decoded bitmaps.
 * Offline streams go directly to Mihon's subsampling decoder; online data uses the existing
 * six-worker, bounded forward window and per-reader temporary files.
 */
class JmPageLoader(context: Context, private val descriptor: String) : PageLoader() {
    private val app = context.applicationContext
    private val source = Uri.parse(descriptor)
    private val online = source.authority == "online"
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class DisplayJob(val page: ReaderPage, val job: Job)
    private val jobs = ConcurrentHashMap<Int, DisplayJob>()
    @Volatile private var api: JmApi? = null
    @Volatile private var session: OnlineSession? = null
    @Volatile private var closed = false
    @Volatile private var anchor = 0
    @Volatile private var visibleFirst = 0
    @Volatile private var visibleLast = 0
    @Volatile private var allStarted = false
    @Volatile var displayTitle: String? = null
        private set

    override var isLocal = !online

    override suspend fun getPages(): List<ReaderPage> = withContext(Dispatchers.IO) {
        try {
            ensureOpen()
            if (online) {
                onlinePages(source.lastPathSegment ?: error("编号无效"))
            } else {
                val images = when (source.authority) {
                    "book" -> Store(app).pages(source.lastPathSegment).map { LocalImages.Page(Uri.fromFile(it).toString(), it.name) }
                    "tree" -> LocalImages.scan(app.contentResolver, Uri.parse(source.getQueryParameter("uri") ?: error("文件夹无效")))
                    "library" -> {
                        val library = Library(app)
                        val book = library.find(source.lastPathSegment ?: error("书籍无效")) ?: error("书籍已移除")
                        library.pages(book)
                    }
                    else -> error("不支持的书籍来源")
                }
                ensureOpen()
                val resolver = app.contentResolver
                images.mapIndexed { index, image ->
                    val uri = Uri.parse(image.uri)
                    ReaderPage(index, image.uri).apply {
                        stream = { resolver.openInputStream(uri) ?: error("无法读取图片") }
                        status = Page.State.Ready
                    }
                }
            }
        } catch (error: Throwable) {
            recycle()
            throw error
        }
    }

    private suspend fun ensureOpen() {
        coroutineContext.ensureActive()
        if (closed) throw InterruptedIOException("阅读已关闭")
    }

    private suspend fun onlinePages(id: String): List<ReaderPage> {
        val client = JmApi(app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("api_host", ""))
        synchronized(lock) {
            if (closed) { client.cancel(); throw InterruptedIOException("阅读已关闭") }
            api = client
        }
        val album = runInterruptible { client.album(id) }
        displayTitle = album.optString("name").takeIf { it.isNotBlank() }
        val history=OnlineHistory(app,true)
        val record=history.find("-1",id)?:JSONObject()
        record.put("key",OnlineHistory.key("-1",id)).put("sourceId",-1).put("url",id).put("jm",true).put("type","online").put("title",record.optString("customTitle").ifBlank{displayTitle?:"JM$id"})
        history.save(record)

        val series = album.optJSONArray("series")
        val ordered = if (series == null || series.length() == 0) {
            listOf(JSONObject().put("id", album.getString("id")))
        } else {
            (0 until series.length()).map { series.getJSONObject(it) }.sortedBy { it.optInt("sort", 0) }
        }
        val records = ArrayList<OnlineSession.Page>()
        for (item in ordered) {
            ensureOpen()
            val chapterId = item.getString("id")
            val chapter = runInterruptible { client.chapter(chapterId) }
            val images = chapter.getJSONArray("images")
            for (index in 0 until images.length()) {
                records.add(OnlineSession.Page(chapterId, images.getString(index), chapter.getInt("scramble_id")))
            }
        }
        ensureOpen()
        val cache = OnlineSession(app, client, records)
        synchronized(lock) {
            if (closed) { cache.close(); throw InterruptedIOException("阅读已关闭") }
            session = cache
        }
        // JM direct reading publishes one "全书" chapter, even when the album has multiple
        // volumes/series. There is no cross-chapter window in the reader, so the whole chapter is
        // loaded once the reader reports its starting page (see setVisiblePage()).
        return records.mapIndexed { index, record -> ReaderPage(index, "online:${record.chapterId}/${record.filename}") }
    }

    override suspend fun loadPage(page: ReaderPage) {
        if (!online || closed) return
        if (page.status == Page.State.Ready) return
        launchLoad(page)
    }

    /** Only the page selected by Mihon's tracker advances lookahead; binding neighbors does not. */
    fun setVisiblePage(index: Int, page: ReaderPage? = null) {
        synchronized(lock) {
            if (closed) return
            anchor = index
            trimDisplayJobs()
        }
        session?.let { cache ->
            val firstVisible = synchronized(lock) {
                if (allStarted) false else {
                    allStarted = true
                    true
                }
            }
            if (firstVisible) {
                // Current image first, then both directions until this one-chapter book is full.
                cache.prefetchAll(index)
            } else {
                cache.prefetch(index)
            }
        }
        if (page != null) launchLoad(page)
    }

    /** The continuous viewer protects every short image actually visible on screen. */
    fun setVisiblePages(pages: List<ReaderPage>) {
        if (pages.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            visibleFirst = pages.minOf { it.index }
            visibleLast = pages.maxOf { it.index }
            trimDisplayJobs()
        }
        pages.forEach(::launchLoad)
    }

    private fun wanted(index: Int): Boolean = index in (anchor - 4)..(anchor + 12) || index in visibleFirst..visibleLast

    private fun trimDisplayJobs() {
        jobs.entries.filter { !wanted(it.key) }.forEach { (index, display) ->
            if (jobs.remove(index, display)) {
                display.page.status = Page.State.Queue
                display.job.cancel()
            }
        }
    }

    private fun launchLoad(page: ReaderPage) {
        if (closed || page.status == Page.State.Ready || !wanted(page.index)) return
        lateinit var display: DisplayJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureOpen()
                val cache = session ?: throw InterruptedIOException("阅读已关闭")
                synchronized(lock) {
                    if (closed || jobs[page.index] !== display) return@launch
                    page.status = Page.State.DownloadImage
                }
                val file = runInterruptible { cache.fileForReader(page.index) }
                ensureOpen()
                synchronized(lock) {
                    if (closed || jobs[page.index] !== display) return@launch
                    page.stream = { FileInputStream(file) }
                    page.progress = 100
                    page.status = Page.State.Ready
                }
                if(page.index==0) saveHistoryCover(file)
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (!closed && jobs[page.index] === display) page.status = Page.State.Queue }
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) { if (!closed && jobs[page.index] === display) page.status = Page.State.Error(error) }
            }
        }
        display = DisplayJob(page, job)
        synchronized(lock) {
            if (closed || !wanted(page.index) || jobs.putIfAbsent(page.index, display) != null) {
                job.cancel()
                return
            }
        }
        job.invokeOnCompletion { jobs.remove(page.index, display) }
        job.start()
    }

    private fun saveHistoryCover(file:java.io.File){
        runCatching {
            val id=source.lastPathSegment?:return
            val folder=java.io.File(app.cacheDir,"jm-history-covers").apply{mkdirs()}
            val target=java.io.File(folder,"${JmApi.parseId(id)}.jpg")
            if(target.isFile)return
            val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,options)
            if(options.outWidth<=0||options.outHeight<=0)return
            var sample=1;while(options.outWidth/sample>240||options.outHeight/sample>336)sample*=2
            options.inJustDecodeBounds=false;options.inSampleSize=sample
            val bitmap=android.graphics.BitmapFactory.decodeFile(file.path,options)?:return
            try {target.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,82,it)}}finally{bitmap.recycle()}
            val history=OnlineHistory(app,true)
            val record=history.find("-1",id)?:return
            history.save(record.put("thumbnail",Uri.fromFile(target).toString()))
        }
    }

    override fun retryPage(page: ReaderPage) {
        if (closed) return
        synchronized(lock) { jobs.remove(page.index)?.job?.cancel() }
        session?.retry(page.index)
        page.status = Page.State.Queue
        launchLoad(page)
    }

    override fun recycle() {
        val oldSession: OnlineSession?
        val oldApi: JmApi?
        synchronized(lock) {
            if (closed) return
            closed = true
            oldSession = session; session = null
            oldApi = api; api = null
            super.recycle()
        }
        scope.cancel()
        jobs.clear()
        oldSession?.close()
        oldApi?.cancel()
    }
}
