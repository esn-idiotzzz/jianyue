package com.joe.jmlite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Searches the Chinese sources bundled with JianYue. */
class OnlineSearchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: OnlineUi
    private lateinit var queryInput: EditText
    private lateinit var status: TextView
    private var sourceLoginNotice:TextView?=null
    private lateinit var grid: GridView
    private lateinit var adapter: ResultAdapter
    private val results = ArrayList<OnlineRepository.Item>()
    private var searching = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private var generation = 0
    private var completionSummary: OnlineRepository.SearchSummary? = null
    private var refreshScheduled = false
    private val scores = HashMap<String,Int>()
    private val checked = HashSet<String>()
    private val sourceMode by lazy { intent.hasExtra("sourceId") || intent.hasExtra("sourcePackage") }
    private var sourceSession:SourceBrowser.Session?=null
    private var loginHint:TextView?=null
    private var currentPage=0
    private var hasNext=false
    private var activeQuery=""
    private val itemKeys=HashSet<String>()
    private var searchExpanded=false
    private var backPreview=false
    private val tabletDevice:Boolean by lazy { AdaptiveLayout.isTabletDevice(this) }
    private var predictiveBack:PredictiveBack?=null
    private var searchAnimation:android.animation.ValueAnimator?=null
    private var toggleSearch:((Boolean)->Unit)?=null
    private val queryTask=Runnable { loadSource(true) }
    private val sourceId by lazy { intent.getLongExtra("sourceId",0L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        SessionCovers.install(this)
        super.onCreate(savedInstanceState)
        PageMotion.enter(this)
        ui = OnlineUi(this)
        val root = ui.column()
        root.setBackgroundColor(ui.bg)
        queryInput=EditText(this).apply {
            setCursorVisible(false)
            hint=if(sourceMode) "搜索本图源漫画" else "搜索全网漫画"
            setSingleLine(true);textSize=15f;setTextColor(ui.ink);setHintTextColor(ui.muted)
            setBackgroundColor(Color.TRANSPARENT);inputType=InputType.TYPE_CLASS_TEXT;imeOptions=EditorInfo.IME_ACTION_SEARCH
            setPadding(ui.dp(12),0,ui.dp(6),0)
        }
        queryInput.setOnClickListener{queryInput.setCursorVisible(true)}
        Ui.clearable(queryInput)
        Ui.bindCursorToIme(queryInput)
        val header=ui.header(if(sourceMode) intent.getStringExtra("sourceName").orEmpty() else "搜索全网漫画") {
            if(searchExpanded) toggleSearch?.invoke(false) else finish()
        }
        root.addView(header)
        val go=ui.button("搜索",true)
        if(sourceMode){
            val title=header.getChildAt(1)
            val box=FrameLayout(this)
            header.addView(box,LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)))
            val icon=Ui(this).icon("search",ui.ink).apply{contentDescription="搜索本图源"}
            box.addView(icon,FrameLayout.LayoutParams(ui.dp(44),-1,Gravity.END))
            val editor=ui.row().apply{visibility=View.GONE}
            editor.addView(queryInput,LinearLayout.LayoutParams(0,-1,1f))
            val close=ui.text("×",25,ui.muted).apply{gravity=Gravity.CENTER;contentDescription="关闭搜索，返回热门"}
            editor.addView(close,LinearLayout.LayoutParams(ui.dp(40),-1))
            box.addView(editor,FrameLayout.LayoutParams(-1,-1))
            toggleSearch={ expand ->
                searchAnimation?.cancel();searchExpanded=expand
                predictiveBack?.refresh()
                title.visibility=View.VISIBLE
                editor.visibility=View.VISIBLE;icon.visibility=View.VISIBLE
                val target=if(expand)(header.width-header.paddingLeft-header.paddingRight-ui.dp(44)).coerceAtLeast(ui.dp(120)) else ui.dp(44)
                val from=box.width.coerceAtLeast(ui.dp(44))
                searchAnimation=android.animation.ValueAnimator.ofInt(from,target).apply{
                    duration=220;interpolator=android.view.animation.DecelerateInterpolator()
                    addUpdateListener { animation ->
                        box.layoutParams=box.layoutParams.apply{width=animation.animatedValue as Int}
                        val f=animation.animatedFraction;val progress=if(expand)f else 1f-f
                        title.alpha=1f-progress;editor.alpha=progress;icon.alpha=1f-progress
                    }
                    addListener(object:android.animation.AnimatorListenerAdapter(){
                        override fun onAnimationEnd(animation:android.animation.Animator){
                            if(searchExpanded!=expand)return
                            title.visibility=if(expand)View.GONE else View.VISIBLE
                            editor.visibility=if(expand)View.VISIBLE else View.GONE
                            icon.visibility=if(expand)View.GONE else View.VISIBLE
                            if(expand){queryInput.setCursorVisible(true);queryInput.requestFocus();(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(queryInput,InputMethodManager.SHOW_IMPLICIT)}
                        }
                    });start()
                }
                ui.round(box,if(expand)ui.tint else Color.TRANSPARENT,20)
                if(!expand){queryInput.setText("");queryInput.setCursorVisible(false);queryInput.clearFocus();(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryInput.windowToken,0)}
            }
            icon.setOnClickListener { toggleSearch?.invoke(true) }
            close.setOnClickListener { toggleSearch?.invoke(false) }
        }else{
            val searchRow=ui.row().apply{setPadding(ui.dp(18),ui.dp(4),ui.dp(18),ui.dp(10))}
            val box=ui.row().apply{setPadding(ui.dp(6),0,ui.dp(6),0);ui.round(this,ui.card,18)}
            box.addView(queryInput,LinearLayout.LayoutParams(0,ui.dp(46),1f))
            box.addView(go,LinearLayout.LayoutParams(ui.dp(62),ui.dp(36)))
            searchRow.addView(box,LinearLayout.LayoutParams(-1,ui.dp(56)));root.addView(searchRow)
        }

        status = ui.text("输入关键词，搜索内置中文图源", 12, ui.muted).apply {
            setPadding(ui.dp(22), 0, ui.dp(22), ui.dp(6))
        }
        root.addView(status)
        if(sourceMode){
            sourceLoginNotice=ui.text("若不能加载，请点击地球图标，登录状态~",12,ui.muted).apply{
                setPadding(ui.dp(22),0,ui.dp(22),ui.dp(10))
            }
            root.addView(sourceLoginNotice)
        }

        grid = AdaptiveGridView(this, { contentDp, windowDp -> AdaptiveLayout.searchColumns(windowDp, contentDp, tabletDevice) }).apply {
            val windowDp = AdaptiveLayout.windowWidthDp(this@OnlineSearchActivity)
            numColumns = AdaptiveLayout.searchColumns(windowDp, windowDp - AdaptiveLayout.SEARCH_PAGE_PADDING_DP * 2, tabletDevice)
            horizontalSpacing = ui.dp(AdaptiveLayout.SEARCH_SPACING_DP)
            verticalSpacing = ui.dp(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(ui.dp(AdaptiveLayout.SEARCH_PAGE_PADDING_DP), ui.dp(4), ui.dp(AdaptiveLayout.SEARCH_PAGE_PADDING_DP), ui.dp(24))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        adapter = ResultAdapter()
        grid.adapter = adapter
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        go.setOnClickListener { startSearch() }
        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }
        val initial = intent.getStringExtra("query").orEmpty()
        if (initial.isNotBlank()) {
            queryInput.setText(initial)
            queryInput.setSelection(initial.length)
            queryInput.post { startSearch() }
        } else if(sourceMode) {
            queryInput.post { startSearch() }
        }

        if(sourceMode){
            queryInput.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun afterTextChanged(s:android.text.Editable?){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){grid.removeCallbacks(queryTask);grid.postDelayed(queryTask,350)}
            })
            status.setOnClickListener{if(!searching)loadSource(currentPage==0||results.isEmpty(),retry=true)}
            grid.setOnScrollListener(object:android.widget.AbsListView.OnScrollListener{
                override fun onScrollStateChanged(view:android.widget.AbsListView?,state:Int){}
                override fun onScroll(view:android.widget.AbsListView?,first:Int,count:Int,total:Int){
                    scheduleCovers()
                    if(total>0&&first+count>=total&&hasNext&&!searching)grid.post{loadSource(false)}
                }
            })
        }

        grid.setOnItemClickListener { _, _, position, _ ->
            val item = results.getOrNull(position) ?: return@setOnItemClickListener
            BackPreview.capture(window.decorView)
            startActivity(
                Intent(this, OnlineMangaActivity::class.java)
                    .putExtra("sourceId", item.sourceId)
                    .putExtra("url", item.url)
                    .putExtra("title", item.title)
                    .putExtra("thumbnail", item.thumbnail)
                    .putExtra("author", item.author)
                    .putExtra("description", item.description)
                    .putExtra("sourceName", item.sourceName)
                    .putExtra("promote_history", true),
            )
        }
        grid.setOnItemLongClickListener { _,_,position,_ ->
            val item=results.getOrNull(position) ?: return@setOnItemLongClickListener false
            val record=org.json.JSONObject().put("sourceId",item.sourceId).put("sourceName",item.sourceName).put("url",item.url).put("title",item.title).put("thumbnail",item.thumbnail).put("key","${item.sourceId}|${item.url}")
            FavoriteActions.show(this,record);true
        }
        applyOnlineInsets(this, root, ui.dark)
        backPreview=BackPreview.install(this)
        setContentView(root)
        predictiveBack=PredictiveBack(this,root,{true},{!searchExpanded},{!searchExpanded},{onBackPressed()}).setPreview { BackPreview.install(this) }
    }

    private fun startSearch() {
        if(sourceMode){grid.removeCallbacks(queryTask);loadSource(true);return}
        searchJob?.cancel()
        val token=++generation
        val query = queryInput.text.toString().trim()
        if (query.isEmpty() && sourceId==0L) {
            queryInput.error = "请输入漫画名"
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(queryInput.windowToken, 0)
        searching = true
        completionSummary=null
        grid.removeCallbacks(refreshTask)
        refreshScheduled=false
        scores.clear()
        results.clear()
        adapter.notifyDataSetChanged()
        status.text = "正在准备中文图源…"
        searchJob=scope.launch {
            try {
                val summary = OnlineRepository.search(
                    this@OnlineSearchActivity,
                    query,
                    sourceId=sourceId,
                    onProgress = { message ->
                        runOnUiThread {
                            if (token==generation && !isFinishing && !isDestroyed) status.text = message
                        }
                    },
                    onItem = { item ->
                        runOnUiThread {
                            if (token==generation && !isFinishing && !isDestroyed && !ChapterAvailability.empty(this@OnlineSearchActivity,item.sourceId,item.url)) {
                                val key="${item.sourceId}|${item.url}"
                                val score=SearchRanking.rank(query,item.title)
                                scores[key]=score
                                val index=results.binarySearch { compareValues(scores["${it.sourceId}|${it.url}"]?:2,score) }
                                results.add(if(index<0) -index-1 else index,item)
                                refreshResults()
                            }
                        }
                    },
                )
                if(token!=generation || isFinishing || isDestroyed)return@launch
                completionSummary=summary
                grid.removeCallbacks(refreshTask)
                refreshScheduled=false
                adapter.notifyDataSetChanged()
                status.text = if (summary.succeeded == 0) {
                    "本次 ${summary.total} 个图源均未能完成搜索，请检查网络后重试"
                } else {
                    val found = if (results.isEmpty()) "没有找到相关漫画" else "共找到 ${results.size} 部漫画"
                    "$found · 图源成功 ${summary.succeeded}/${summary.total}，失败 ${summary.failed}"
                }
                appendFailedSourcesLink()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (error: Throwable) {
                if(token!=generation || isFinishing || isDestroyed)return@launch
                status.text = "搜索失败：${error.message ?: error.javaClass.simpleName}"
                Toast.makeText(this@OnlineSearchActivity, status.text, Toast.LENGTH_LONG).show()
            } finally {
                if(token==generation)searching = false
            }
        }
    }

    private fun loadSource(reset:Boolean,retry:Boolean=false){
        if(!reset&&(searching||!hasNext))return
        val query=queryInput.text.toString().trim()
        if(reset&&!retry&&query.isEmpty()&&activeQuery.isEmpty()&&(searching||currentPage>0))return
        if(reset){
            searchJob?.cancel();generation++;currentPage=0;hasNext=true
            activeQuery=query;completionSummary=null
            results.clear();scores.clear();itemKeys.clear();grid.removeCallbacks(refreshTask);refreshScheduled=false
            adapter.notifyDataSetChanged()
        }
        val token=generation
        sourceLoginNotice?.visibility=if(activeQuery.isBlank())View.VISIBLE else View.GONE
        clearLoginHint()
        searching=true
        status.text=if(currentPage>0) "正在加载更多漫画…" else if(activeQuery.isBlank()) "正在加载热门书籍…" else "正在搜索本图源…"
        searchJob=scope.launch {
            try {
                val session=sourceSession?:SourceBrowser.Session(SourceBrowser.resolve(this@OnlineSearchActivity,sourceId,intent.getStringExtra("sourceName").orEmpty(),intent.getStringExtra("sourceLang").orEmpty(),intent.getStringExtra("sourcePackage").orEmpty())).also{sourceSession=it}
                val page=session.page(activeQuery,currentPage+1)
                if(token!=generation)return@launch
                for(item in page.items){
                    val key="${item.sourceId}|${item.url}"
                    if(!itemKeys.add(key)||ChapterAvailability.empty(this@OnlineSearchActivity,item.sourceId,item.url))continue
                    if(activeQuery.isBlank())results.add(item) else {
                        val score=SearchRanking.rank(activeQuery,item.title);scores[key]=score
                        val index=results.binarySearch{compareValues(scores["${it.sourceId}|${it.url}"]?:2,score)}
                        results.add(if(index<0)-index-1 else index,item)
                    }
                }
                currentPage++;hasNext=page.hasNext&&page.items.isNotEmpty()
                adapter.notifyDataSetChanged()
                status.text=if(results.isEmpty()) "此图源暂未返回漫画，点击重试" else "${if(activeQuery.isBlank())"热门书籍" else "搜索结果"} · ${results.size} 部${if(hasNext)" · 上滑加载更多" else ""}"
            }catch(e:kotlinx.coroutines.TimeoutCancellationException){if(token==generation)status.text="图源请求超时，点击重试"}
            catch(e:kotlinx.coroutines.CancellationException){return@launch}
            catch(e:Exception){if(token==generation){
                status.text="加载失败：${e.message?:e.javaClass.simpleName} · 点击重试"
                if(activeQuery.isBlank()&&currentPage==0&&SourceLogin.required(e))showLoginHint()
            }}
            finally{if(token==generation)searching=false}
        }
    }
    private fun showLoginHint(){
        if(isFinishing||isDestroyed)return
        val source=sourceSession?.source?:return
        val host=findViewById<FrameLayout>(android.R.id.content)
        val hint=ui.text("此图源需要登录 · 点击打开网站",14,Color.WHITE,true).apply{
            gravity=Gravity.CENTER;setPadding(ui.dp(22),ui.dp(12),ui.dp(22),ui.dp(12))
            background=ui.shape(0xf02a3445.toInt(),28);elevation=ui.dp(8).toFloat()
            setOnClickListener{
                scope.launch {
                    try {
                        val url=kotlinx.coroutines.withContext(Dispatchers.IO){source.getHomeUrl()}
                        startActivity(eu.kanade.tachiyomi.ui.webview.WebViewActivity.newIntent(this@OnlineSearchActivity,url,source.id,source.name))
                        clearLoginHint();finishForBrowser()
                    }catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(e:Exception){Toast.makeText(this@OnlineSearchActivity,"无法打开图源网站：${e.message}",Toast.LENGTH_LONG).show()}
                }
            }
        }
        val inset=androidx.core.view.ViewCompat.getRootWindowInsets(host)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom?:0
        host.addView(hint,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=ui.dp(32)+inset;leftMargin=ui.dp(18);rightMargin=ui.dp(18)})
        loginHint=hint;hint.alpha=0f;hint.animate().alpha(1f).setDuration(160).start()
        hint.announceForAccessibility(hint.text)
        hint.postDelayed({if(loginHint===hint)clearLoginHint()},5000)
    }
    private fun clearLoginHint(){loginHint?.let{it.animate().cancel();(it.parent as? ViewGroup)?.removeView(it)};loginHint=null}
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {predictiveBack?.recordTouch(event);return super.dispatchTouchEvent(event)}
    override fun finish(){
        if(predictiveBack?.animateButtonBack { finishPage(true) }==true)return
        finishPage(predictiveBack?.isLeavingByGesture==true)
    }
    /** Browser opens above us; this is not a user back, must not animate or consume the preview. */
    private fun finishForBrowser(){
        clearLoginHint()
        BackPreview.hide()
        if(backPreview)backPreview=false
        PageMotion.prepareExit(this,true)
        super.finish()
        PageMotion.exitLegacy(this,true)
    }
    private fun finishPage(gesture:Boolean){
        if(backPreview&&!isChangingConfigurations){backPreview=false;BackPreview.pop()}
        PageMotion.prepareExit(this,gesture);super.finish();PageMotion.exitLegacy(this,gesture)
    }
    override fun onBackPressed(){if(searchExpanded)toggleSearch?.invoke(false) else super.onBackPressed()}
    override fun onStop(){predictiveBack?.onStop();super.onStop()}
    private fun appendFailedSourcesLink(){
        val summary=completionSummary?:return
        if(sourceMode)return
        val text=SpannableString("${status.text} 点此查看")
        text.setSpan(object:ClickableSpan(){
            override fun updateDrawState(paint:TextPaint){paint.color=OnlineUi.BLUE;paint.isUnderlineText=false}
            override fun onClick(view:View){
                RepositoryPanel.showFailures(this@OnlineSearchActivity,summary.failedSourceIds.toSet(),summary.failed)
            }
        },text.length-4,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.text=text
        status.movementMethod=LinkMovementMethod.getInstance()
        status.highlightColor=Color.TRANSPARENT
    }
    private val refreshTask=Runnable { refreshScheduled=false;coverFirst=-1;coverLast=-1;adapter.notifyDataSetChanged();grid.post{scheduleCovers()};completionSummary?.let{if(it.succeeded>0){status.text="共找到 ${results.size} 部漫画 · 图源成功 ${it.succeeded}/${it.total}，失败 ${it.failed}";appendFailedSourcesLink()}} }
    private var coverFirst = -1
    private var coverLast = -1
    private fun scheduleCovers(){
        if(!::grid.isInitialized)return
        val first=grid.firstVisiblePosition.coerceAtLeast(0)
        val last=grid.lastVisiblePosition.coerceAtLeast(first)
        if(first==coverFirst&&last==coverLast)return
        coverFirst=first;coverLast=last
        val anchor=(first+last)/2
        for(position in first..last){if(position in results.indices){val item=results[position];SessionCovers.prefetch(this@OnlineSearchActivity,item.thumbnail,item.sourceId)}}
        for(distance in 0..12){
            for(position in intArrayOf(anchor-distance,anchor+distance)){
                if(position !in results.indices)continue
                val item=results[position]
                SessionCovers.prefetch(this@OnlineSearchActivity,item.thumbnail,item.sourceId)
            }
        }
    }
    private fun refreshResults(){ if(!refreshScheduled){refreshScheduled=true;grid.postDelayed(refreshTask,80)} }
    override fun onResume(){super.onResume();predictiveBack?.reinstall();if(::adapter.isInitialized){results.removeAll{ChapterAvailability.empty(this,it.sourceId,it.url)};adapter.notifyDataSetChanged()}}
    override fun onDestroy() {
        if(backPreview&&isFinishing&&!isChangingConfigurations)BackPreview.pop()
        clearLoginHint()
        predictiveBack?.close()
        grid.removeCallbacks(refreshTask)
        grid.removeCallbacks(queryTask)
        searchAnimation?.run { removeAllListeners();removeAllUpdateListeners();cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount(): Int = results.size
        override fun getItem(position: Int): Any = results[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = results[position]
            val key="${item.sourceId}|${item.url}"
            if(checked.add(key)) scope.launch {
                val empty=ChapterAvailability.probe(this@OnlineSearchActivity,item)
                if(empty==true){results.removeAll{it.sourceId==item.sourceId&&it.url==item.url};refreshResults()}
            }
            val holder=convertView as? ResultTile ?: ResultTile()
            holder.title.text=item.title
            holder.source.text=item.sourceName
            SessionCovers.load(holder.image,item.thumbnail,item.sourceId)
            scheduleCovers()
            return holder
        }
    }

    /**
     * One search result tile. On tablets the cover height follows the measured tile width so the
     * phone's 1.36 height/width ratio is kept; phones keep their original fixed 138dp cover.
     */
    /** Clip the bitmap itself, not only the parent outline, so software snapshot capture shows round corners too. */
    private class RoundedImageView(context: android.content.Context, private val radius: Float) : ImageView(context) {
        private val clipPath = android.graphics.Path()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }
    private inner class ResultTile : LinearLayout(this@OnlineSearchActivity) {
        val image: ImageView
        val title: TextView
        val source: TextView
        private val coverFrame: FrameLayout
        private var coverHeight = 0

        init {
            orientation = VERTICAL
            coverFrame = FrameLayout(this@OnlineSearchActivity)
            coverFrame.background = ui.shape(ui.tint, 12)
            coverFrame.clipToOutline = true
            coverFrame.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ui.dp(12).toFloat())
                }
            }
            coverFrame.addView(
                Ui(this@OnlineSearchActivity).icon("book", OnlineUi.BLUE).apply { setMinimumWidth(0); setMinimumHeight(0); glyphScale = .72f },
                FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER),
            )
            image = RoundedImageView(this@OnlineSearchActivity, ui.dp(12).toFloat()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            coverFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(coverFrame, LinearLayout.LayoutParams(-1, ui.dp(138)))
            title = ui.text("", 12, ui.ink, true).apply {
                maxLines = 2
                minLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0)
            }
            addView(title, LinearLayout.LayoutParams(-1, -2))
            source = ui.text("", 10, ui.muted).apply {
                maxLines = 1
                minLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(2), ui.dp(2), ui.dp(2), 0)
            }
            addView(source, LinearLayout.LayoutParams(-1, -2))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measured = View.MeasureSpec.getSize(widthMeasureSpec)
            val density = resources.displayMetrics.density
            val content = grid.width - grid.paddingLeft - grid.paddingRight
            val tablet = tabletDevice && content > 0 && AdaptiveLayout.isTabletWidth(Math.round(content / density))
            val wanted = if (tablet) AdaptiveLayout.searchCoverHeightPx(measured) else ui.dp(138)
            if (wanted != coverHeight) {
                coverHeight = wanted
                (coverFrame.layoutParams as? LinearLayout.LayoutParams)?.height = wanted
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
