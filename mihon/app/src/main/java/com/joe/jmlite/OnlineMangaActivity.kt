package com.joe.jmlite

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.model.Chapter

/** Online manga details with chapter list, in-app reading and download entry points. */
class OnlineMangaActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: OnlineUi
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var coverView: ImageView
    private lateinit var coverFrame: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var cacheNotice: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chapterTitle: TextView
    private lateinit var chaptersList: ListView
    private lateinit var adapter: ChapterAdapter
    private lateinit var startButton: TextView
    private lateinit var resumeButton: TextView
    private lateinit var resumeHint: TextView
    private lateinit var downloadButton: TextView
    private lateinit var favoriteHeart: Ui.Icon
    private lateinit var favoriteButton: FrameLayout
    private var favorited = false
    private var favoriteBusy = false

    private var details: OnlineRepository.Details? = null
    private var loading = false
    private var sourceId = 0L
    private var mangaUrl = ""
    private var mangaTitle = ""
    private var mangaThumbnail: String? = null
    private var mangaAuthor: String? = null
    private var mangaDescription: String? = null
    private var sourceName = ""
    private var lastChapterUrl = ""
    private var lastChapterName = ""
    private var predictiveBack: PredictiveBack? = null
    private var chapterProgress = org.json.JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = OnlineUi(this)
        sourceId = intent.getLongExtra("sourceId", 0L)
        mangaUrl = intent.getStringExtra("url").orEmpty()
        mangaTitle = intent.getStringExtra("title").orEmpty().ifBlank { "未命名" }
        mangaThumbnail = intent.getStringExtra("thumbnail")
        mangaAuthor = intent.getStringExtra("author")
        mangaDescription = intent.getStringExtra("description")
        sourceName = intent.getStringExtra("sourceName").orEmpty()
        if (sourceId == 0L || mangaUrl.isBlank()) {
            finish()
            return
        }

        val root = ui.column()
        root.setBackgroundColor(ui.bg)
        val heading=ui.header("漫画详情") { finish() }
        favoriteButton=FrameLayout(this).apply{isClickable=true;contentDescription="收藏"}
        favoriteHeart=Ui(this).icon("heart",ui.muted)
        favoriteHeart.setMinimumWidth(0);favoriteHeart.setMinimumHeight(0)
        favoriteButton.addView(favoriteHeart,FrameLayout.LayoutParams(ui.dp(26),ui.dp(26),Gravity.CENTER))
        heading.addView(favoriteButton,LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)))
        favoriteButton.setOnClickListener { toggleFavorite() }
        root.addView(heading)

        val stage = FrameLayout(this)
        chaptersList = ListView(this).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24))
            clipToPadding = false
        }
        adapter = ChapterAdapter()
        chaptersList.addHeaderView(buildHeader(), null, false)
        chaptersList.adapter = adapter
        stage.addView(chaptersList, FrameLayout.LayoutParams(-1, -1))

        statusView = ui.text("正在加载漫画信息…", 13, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(16))
            setOnClickListener { if (!loading) load() }
        }
        stage.addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        progress = ui.progress()
        stage.addView(progress, FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        applyOnlineInsets(this, root, ui.dark)
        setContentView(root)
        predictiveBack=PredictiveBack(this,root,{true},{true},{finish()})

        load()
        paintFavorite()
        refreshFavorite()
    }

    private fun buildHeader(): LinearLayout {
        val header = ui.column()
        header.setPadding(0, ui.dp(10), 0, ui.dp(10))

        val coverRow = ui.row()
        coverRow.gravity = Gravity.CENTER_HORIZONTAL
        coverFrame = FrameLayout(this)
        coverFrame.background = ui.shape(ui.tint, 16)
        coverFrame.clipToOutline = true
        coverFrame.addView(
            ui.text("JM", 22, OnlineUi.BLUE, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1),
        )
        coverView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverFrame.addView(coverView, FrameLayout.LayoutParams(-1, -1))
        coverRow.addView(coverFrame, LinearLayout.LayoutParams(ui.dp(126), ui.dp(176)))
        header.addView(coverRow, LinearLayout.LayoutParams(-1, -2))

        titleView = ui.text(mangaTitle, 20, ui.ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(8), ui.dp(14), ui.dp(8), 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        subtitleView = ui.text("", 12, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), 0)
        }
        header.addView(subtitleView, LinearLayout.LayoutParams(-1, -2))
        descriptionView = ui.text(mangaDescription.orEmpty(), 12, ui.muted).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0)
        }
        header.addView(descriptionView, LinearLayout.LayoutParams(-1, -2))
        cacheNotice = ui.text("", 11, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), 0)
            visibility = View.GONE
            setOnClickListener { if (!loading) load() }
        }
        header.addView(cacheNotice, LinearLayout.LayoutParams(-1, -2))

        val actions = ui.row()
        actions.setPadding(0, ui.dp(14), 0, ui.dp(8))
        startButton = ui.button("从头开始阅读", false).apply {
            setTextSize(14f)
            setOnClickListener { read(oldestChapter(), true) }
        }
        resumeButton = ui.button("上次位置阅读", true).apply {
            setTextSize(14f)
            setOnClickListener { read(resumeChapter(), false) }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
        actions.addView(resumeButton, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        startButton.visibility = View.GONE
        resumeButton.visibility = View.GONE
        header.addView(actions)

        resumeHint=ui.text("",12,ui.muted).apply {
            gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
            setPadding(ui.dp(4),0,ui.dp(4),ui.dp(10));visibility=View.GONE
        }
        header.addView(resumeHint,LinearLayout.LayoutParams(-1,-2))

        downloadButton = ui.button("下载章节", false).apply {
            setTextSize(13f)
            minHeight = ui.dp(44)
            visibility = View.GONE
            setOnClickListener { showChapterDownloadDialog() }
        }
        header.addView(downloadButton, LinearLayout.LayoutParams(-1, ui.dp(44)))

        chapterTitle = ui.text("章节", 16, ui.ink, true).apply {
            setPadding(ui.dp(2), ui.dp(18), 0, ui.dp(8))
        }
        header.addView(chapterTitle, LinearLayout.LayoutParams(-1, -2))
        return header
    }

    private fun load() {
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        statusView.text = "正在加载漫画信息…"
        cacheNotice.visibility = View.GONE
        titleView.text = mangaTitle
        if (!mangaThumbnail.isNullOrBlank()) RemoteCovers.load(coverView, mangaThumbnail, sourceId)
        if (sourceName.isNotBlank()) subtitleView.text = sourceName
        scope.launch {
            // Read the local database first so cached titles, chapters and the blue
            // last-read marker appear immediately, even when the network is unavailable.
            val cached = try {
                withContext(Dispatchers.IO) {
                    OnlineRepository.cachedDetails(
                        this@OnlineMangaActivity,
                        sourceId,
                        mangaUrl,
                        mangaTitle,
                        mangaThumbnail,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (isFinishing || isDestroyed) {
                loading = false
                return@launch
            }
            if (cached != null) {
                readHistory()
                details = cached
                sourceName = cached.sourceName
                showDetails(cached)
                showCacheNotice("已显示本地缓存，正在联网刷新…")
            }
            try {
                val result = OnlineRepository.loadDetails(
                    this@OnlineMangaActivity,
                    sourceId,
                    mangaUrl,
                    mangaTitle,
                    mangaThumbnail,
                )
                if (isFinishing || isDestroyed) return@launch
                readHistory()
                details = result
                sourceName = result.sourceName
                showDetails(result)
                hideCacheNotice()
                withContext(Dispatchers.IO) { saveHistory(result, null) }
            } catch (error: Throwable) {
                // A real scope cancellation must stop here; a withTimeout cancellation is a
                // normal network failure and should leave the cached details on screen.
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (isFinishing || isDestroyed) return@launch
                progress.visibility = View.GONE
                if (cached != null) {
                    showCacheNotice("网络不可用 · 正在显示缓存，点击重试")
                } else {
                    statusView.visibility = View.VISIBLE
                    statusView.text = "加载失败：${error.message ?: "网络错误"}\n点击重试"
                }
            } finally {
                loading = false
            }
        }
    }

    private fun showCacheNotice(message: String) {
        cacheNotice.text = message
        cacheNotice.visibility = View.VISIBLE
    }

    private fun hideCacheNotice() {
        cacheNotice.visibility = View.GONE
    }

    private fun showDetails(result: OnlineRepository.Details) {
        progress.visibility = View.GONE
        statusView.visibility = View.GONE
        titleView.text = result.title
        val by = result.author?.takeIf { it.isNotBlank() } ?: result.artist?.takeIf { it.isNotBlank() }
        subtitleView.text = listOfNotNull(by, result.sourceName).joinToString(" · ")
        descriptionView.text = result.description.orEmpty()
        descriptionView.visibility = if (result.description.isNullOrBlank()) View.GONE else View.VISIBLE
        RemoteCovers.load(coverView, result.thumbnail, sourceId)
        val count = result.chapters.size
        chapterTitle.text = if (count <= 1) "章节" else "章节 · $count"
        startButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        resumeButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        downloadButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        downloadButton.text = "下载章节"
        updateResumeHint()
        adapter.notifyDataSetChanged()
        chaptersList.visibility = View.VISIBLE
        if (count == 0) {
            statusView.text = "暂无章节"
            statusView.visibility = View.VISIBLE
        }
    }

    private fun chapters(): List<Chapter> = details?.chapters.orEmpty()

    private fun oldestChapter(): Chapter? = chapters().lastOrNull()

    private fun resumeChapter(): Chapter? {
        val list = chapters()
        return list.find { it.url==lastChapterUrl } ?: list.firstOrNull { it.lastPageRead > 0 && !it.read } ?: list.lastOrNull()
    }

    private fun updateResumeHint(){
        if(!::resumeHint.isInitialized)return
        val label=OnlineResumeHint.text(chapters(),lastChapterUrl,lastChapterName,chapterProgress)
        if(resumeHint.text.toString()!=label)resumeHint.text=label
        resumeHint.visibility=if(label.isEmpty())View.GONE else View.VISIBLE
    }

    private fun read(chapter: Chapter?, fromStart: Boolean) {
        val result = details ?: return
        if (chapter == null) {
            Toast.makeText(this, "暂无章节", Toast.LENGTH_SHORT).show()
            return
        }
        lastChapterUrl=chapter.url
        lastChapterName=chapter.name
        updateResumeHint()
        adapter.notifyDataSetChanged()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { saveHistory(result, chapter) }
                OnlineRepository.openChapter(
                    this@OnlineMangaActivity,
                    result.manga.id,
                    chapter.id,
                    fromStart,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    this@OnlineMangaActivity,
                    "打开阅读器失败：${error.message ?: "图源不可用"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showChapterDownloadDialog() {
        val result = details ?: return
        if (result.chapters.isEmpty()) {
            Toast.makeText(this, "暂无章节", Toast.LENGTH_SHORT).show()
            return
        }
        val chapters = result.chapters
        val checked = BooleanArray(chapters.size)
        val box = ui.column().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), 0) }
        val selectAll = android.widget.CheckBox(this).apply {
            text = "全选"
            setTextColor(ui.ink)
            textSize = 14f
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
        }
        box.addView(selectAll, LinearLayout.LayoutParams(-1, ui.dp(44)))
        val chapterList = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        lateinit var chapterAdapter: BaseAdapter
        fun syncSelectAll() {
            selectAll.setOnCheckedChangeListener(null)
            selectAll.isChecked = checked.isNotEmpty() && checked.all { it }
            selectAll.setOnCheckedChangeListener { _, on ->
                for (i in checked.indices) checked[i] = on
                chapterAdapter.notifyDataSetChanged()
            }
        }
        chapterAdapter = object : BaseAdapter() {
            override fun getCount(): Int = chapters.size
            override fun getItem(position: Int): Any = chapters[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? android.widget.CheckBox ?: android.widget.CheckBox(this@OnlineMangaActivity).apply {
                    setTextColor(ui.ink)
                    textSize = 14f
                    setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    minHeight = ui.dp(46)
                }
                row.setOnCheckedChangeListener(null)
                row.text = chapters[position].name.ifBlank { "章节 ${position + 1}" }
                row.isChecked = checked[position]
                row.setOnCheckedChangeListener { _, on ->
                    checked[position] = on
                    syncSelectAll()
                }
                return row
            }
        }
        chapterList.adapter = chapterAdapter
        box.addView(chapterList, LinearLayout.LayoutParams(-1, ui.dp(320)))
        selectAll.setOnCheckedChangeListener { _, on ->
            for (i in checked.indices) checked[i] = on
            chapterAdapter.notifyDataSetChanged()
        }
        val dialog = PredictiveBack.dialog(this)
            .setTitle("下载章节")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("全部下载", null)
            .setPositiveButton("下载", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ids = chapters.indices.filter { checked[it] }.map { chapters[it].id }
                if (ids.isEmpty()) {
                    Toast.makeText(this, "请选择章节", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    downloadChapters(ids)
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                downloadChapters(chapters.map { it.id })
            }
        }
        dialog.show()
    }

    private fun downloadChapters(ids: List<Long>) {
        val result = details ?: return
        if (ids.isEmpty()) return
        Toast.makeText(this, "正在加入下载队列…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val count = OnlineRepository.download(this@OnlineMangaActivity, result.manga.id, ids)
                Toast.makeText(
                    this@OnlineMangaActivity,
                    "已加入下载队列：$count 个章节",
                    Toast.LENGTH_LONG,
                ).show()
                adapter.notifyDataSetChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    this@OnlineMangaActivity,
                    "下载失败：${error.message ?: "请稍后重试"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun favoriteRecord(): org.json.JSONObject = org.json.JSONObject()
        .put("key", "$sourceId|$mangaUrl")
        .put("sourceId", sourceId)
        .put("url", mangaUrl)
        .put("title", details?.title ?: titleView.text.toString())
        .put("thumbnail", details?.thumbnail ?: mangaThumbnail ?: "")
        .put("sourceName", details?.sourceName ?: sourceName)
        .put("author", details?.author ?: mangaAuthor ?: "")
        .put("description", details?.description ?: mangaDescription ?: "")
        .put("chapterCount", details?.chapters?.size ?: 0)
        .put("lastChapterName", details?.chapters?.find { it.url == lastChapterUrl }?.name ?: "")
        .put("lastChapterUrl", lastChapterUrl)

    private fun paintFavorite() {
        FavoriteStyle.paint(Ui(this), favoriteHeart, favoriteButton, null, favorited)
        favoriteButton.contentDescription = if (favorited) "取消收藏" else "收藏"
    }

    private fun refreshFavorite() {
        scope.launch {
            val value = try {
                withContext(Dispatchers.IO) { Library(this@OnlineMangaActivity).hasOnline("$sourceId|$mangaUrl") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (isFinishing || isDestroyed) return@launch
            favorited = value
            paintFavorite()
        }
    }

    private fun toggleFavorite() {
        if (favoriteBusy) return
        favoriteBusy = true
        val next = !favorited
        val record = favoriteRecord()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Library(this@OnlineMangaActivity).favoriteOnline(record, next) }
                if (!isFinishing && !isDestroyed) {
                    favorited = next
                    paintFavorite()
                    Toast.makeText(
                        this@OnlineMangaActivity,
                        if (next) "已加入书架 · 在线全网漫画" else "已取消收藏",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@OnlineMangaActivity, "收藏失败：${error.message ?: "请重试"}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                favoriteBusy = false
            }
        }
    }

    private fun saveHistory(result: OnlineRepository.Details, chapter: Chapter?) {
        OnlineRepository.saveHistory(
            this,
            sourceId = sourceId,
            sourceName = result.sourceName.ifBlank { sourceName },
            url = mangaUrl,
            title = result.title,
            thumbnail = result.thumbnail ?: mangaThumbnail,
            author = result.author ?: mangaAuthor,
            description = result.description ?: mangaDescription,
            chapterCount = result.chapters.size,
            lastChapterName = chapter?.name,
            lastChapterUrl = chapter?.url,
            progress = chapter?.lastPageRead ?: 0,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshFavorite()
        if (details == null) return
        scope.launch {
            try {
                readHistory()
                val mangaId = details?.manga?.id ?: return@launch
                val graph = applicationContext.appGraph
                val chapters = graph.jmChapterRepository.getChapterByMangaId(mangaId)
                    .sortedWith(
                        compareBy<Chapter> { it.sourceOrder }
                            .thenByDescending { it.chapterNumber },
                    )
                if (chapters.isNotEmpty()) {
                    // Re-read details after the suspensions: load() may have replaced the
                    // cached details with a fresh network result meanwhile.
                    val current = details ?: return@launch
                    details = current.copy(chapters = chapters)
                    adapter.notifyDataSetChanged()
                    updateResumeHint()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        predictiveBack?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStop(){predictiveBack?.onStop();super.onStop()}

    private suspend fun readHistory(){
        val record=try {
            withContext(Dispatchers.IO){OnlineHistory(this@OnlineMangaActivity).find(sourceId.toString(),mangaUrl)}
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (record != null) {
            lastChapterUrl=record.optString("lastChapterUrl")
            lastChapterName=record.optString("lastChapterName")
            chapterProgress=record.optJSONObject("chapterProgress")?:org.json.JSONObject()
        }
        updateResumeHint()
    }
    private inner class ChapterAdapter : BaseAdapter() {
        override fun getCount(): Int = (chapters().size+2)/3
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row=ui.row()
            row.setPadding(0,0,0,ui.dp(10))
            for(column in 0..2){
                val chapter=chapters().getOrNull(position*3+column)
                val cell=ui.column().apply {setPadding(ui.dp(10),ui.dp(12),ui.dp(10),ui.dp(12))}
                val params=LinearLayout.LayoutParams(0,ui.dp(90),1f).apply{setMargins(ui.dp(3),0,ui.dp(3),0)}
                row.addView(cell,params)
                if(chapter==null){cell.visibility=View.INVISIBLE;continue}
                cell.background=ui.shape(ui.card,12).apply{if(chapter.url==lastChapterUrl)setStroke(ui.dp(2),OnlineUi.BLUE)}
                cell.addView(ui.text(chapter.name,12,ui.ink,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,0,1f))
                val stored=chapterProgress.optJSONObject(chapter.url)
                val total=stored?.optInt("total")?:0
                val label=when {
                    chapter.read->"已读完"
                    total>0->"已读 ${minOf(chapter.lastPageRead+1,total)}/$total 页"
                    chapter.lastPageRead>0 || chapter.url==lastChapterUrl->"已读 ${chapter.lastPageRead+1} 页"
                    else->"未读"
                }
                cell.addView(ui.text(label,10,if(chapter.url==lastChapterUrl)OnlineUi.BLUE else ui.muted))
                cell.setOnClickListener{read(chapter,false)}
                cell.setOnLongClickListener {PredictiveBack.dialog(this@OnlineMangaActivity).setTitle(chapter.name).setItems(arrayOf("从头开始阅读","上次位置阅读")){_,n->read(chapter,n==0)}.show();true}
            }
            return row
        }
    }
}
