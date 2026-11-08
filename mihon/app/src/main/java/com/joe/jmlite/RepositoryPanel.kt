package com.joe.jmlite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import kotlinx.coroutines.*
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.app.di.appGraph

/** A searchable, recycled row list of the packaged Chinese sources. */
object RepositoryPanel {
    private class Row(val root:FrameLayout,val icon:ImageView,val title:TextView,val subtitle:TextView,val globe:Ui.Icon,val heart:Ui.Icon,val body:LinearLayout)
    internal fun failedEntries(entries:List<JSONObject>,ids:Set<Long>,loaded:Map<String,List<HttpSource>>):List<JSONObject> = entries.filter { entry ->
        entry.getLong("id") in ids || loaded[entry.getString("packageName")]?.let { sources ->
            runCatching{SourceBrowser.select(sources,entry.getLong("id"),entry.getString("name"),entry.getString("language")).id in ids}.getOrDefault(false)
        } == true
    }
    internal fun showFailures(activity:Activity,ids:Set<Long>,failed:Int):android.app.AlertDialog {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        val host=FrameLayout(activity).apply{
            isFocusableInTouchMode=true
            addView(create(activity,ids),FrameLayout.LayoutParams(-1,-1))
        }
        val dialog=PredictiveBack.dialog(activity).setTitle("失败的图源 · $failed").setView(host).setPositiveButton("关闭",null).create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        // Size the dialog itself so its weighted list receives the actual remaining space.
        dialog.window?.let{window->window.setLayout(window.attributes.width,(activity.resources.displayMetrics.heightPixels*.75f).toInt())}
        host.requestFocus()
        return dialog
    }
    @JvmStatic fun create(activity:Activity):View {
        return create(activity,null)
    }
    fun create(activity:Activity,sourceIds:Set<Long>?):View {
        val ui=OnlineUi(activity)
        val baseUi=Ui(activity)
        val prefs=activity.getSharedPreferences("source_favorites",Context.MODE_PRIVATE)
        val visits=activity.getSharedPreferences("source_visits",Context.MODE_PRIVATE)
        val icons=SourceIcons(activity)
        val failureMode=sourceIds!=null
        val entries=activity.assets.open("bundled-sources.json").bufferedReader().use{reader->
            val entries=JSONObject(reader.readText()).getJSONArray("extensions")
            buildList<JSONObject>{for(i in 0 until entries.length()){
                val entry=entries.getJSONObject(i);val sources=entry.getJSONArray("sources")
                for(j in 0 until sources.length())add(JSONObject(sources.getJSONObject(j).toString()).put("packageName",entry.getString("packageName")))
            }}.distinctBy{it.getString("id")}
        }
        var all=if(sourceIds==null)entries else failedEntries(entries,sourceIds,emptyMap())
        val root=ui.column().apply{isFocusableInTouchMode=sourceIds!=null}
        // Keep the source rows readable on wide tablets; phones and narrow windows keep their margins.
        if(AdaptiveLayout.isTabletDevice(activity)){
            root.addOnLayoutChangeListener { view,l,_,r,_,_,_,_,_->
                val inset=AdaptiveLayout.centeredInsetPx(r-l,ui.dp(18),ui.dp(AdaptiveLayout.REPOSITORY_LIST_MAX_WIDTH_DP))
                if(view.paddingLeft!=inset||view.paddingRight!=inset)view.setPadding(inset,0,inset,0)
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        fun visited(item:JSONObject)=visits.getBoolean(item.getString("id"),false)
        fun paintGlobe(row:Row,item:JSONObject){
            val seen=visited(item)
            row.globe.alpha=if(seen).35f else 1f
            if(failureMode){
                row.root.alpha=if(seen).5f else 1f
                row.title.setTextColor(if(seen)ui.muted else ui.ink)
            }
        }
        fun detect(){
            val status=TextView(activity).apply{textSize=14f;setTextColor(ui.ink)}
            val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=1;progress=0}
            val panel=ui.column().apply{setPadding(ui.dp(20),ui.dp(8),ui.dp(20),0)}
            panel.addView(status,LinearLayout.LayoutParams(-1,-2))
            panel.addView(progress,LinearLayout.LayoutParams(-1,ui.dp(8)).apply{topMargin=ui.dp(12)})
            val dialog=PredictiveBack.dialog(activity).setTitle("检测图源").setView(panel)
                .setNegativeButton("取消",null).setCancelable(true).create()
            var job:Job?=null
            dialog.setOnDismissListener{job?.cancel()}
            status.text="正在准备中文图源…"
            dialog.show()
            job=scope.launch{
                try{
                    val summary=OnlineRepository.search(
                        activity,"1",
                        onProgress={message->activity.runOnUiThread{
                            if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                            status.text=message
                            Regex("(\\d+)\\s*/\\s*(\\d+)").find(message)?.let{match->
                                val done=match.groupValues[1].toIntOrNull()?:0
                                val total=match.groupValues[2].toIntOrNull()?:0
                                if(total>0){progress.max=total;progress.progress=done}
                            }
                            Regex("正在搜索 (\\d+) 个中文图源").find(message)?.let{match->
                                match.groupValues[1].toIntOrNull()?.let{total->progress.max=total;progress.progress=0}
                            }
                        }},
                        onItem={},
                    )
                    if(activity.isFinishing||activity.isDestroyed)return@launch
                    dialog.dismiss()
                    if(summary.failed>0)showFailures(activity,summary.failedSourceIds.toSet(),summary.failed)
                    else PredictiveBack.dialog(activity).setTitle("检测完成").setMessage("未发现失败的图源。").setPositiveButton("知道了",null).show()
                }catch(cancelled:CancellationException){
                    return@launch
                }catch(error:Throwable){
                    if(!activity.isFinishing&&!activity.isDestroyed){
                        dialog.dismiss()
                        Toast.makeText(activity,"检测失败：${error.message?:error.javaClass.simpleName}",Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        fun confirmDetect(){
            PredictiveBack.dialog(activity).setTitle("检测图源").setMessage("请确认已开启魔法")
                .setNegativeButton("未开启",null)
                .setPositiveButton("已开启",{_,_->detect()}).show()
        }
        val search=ui.row().apply{setPadding(ui.dp(12),0,ui.dp(8),0);background=ui.shape(ui.card,16)}
        search.addView(Ui(activity).icon("search",ui.muted),LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)))
        val input=EditText(activity).apply{hint="搜索图源";setSingleLine();textSize=15f;setTextColor(ui.ink);setHintTextColor(ui.muted);setBackgroundColor(Color.TRANSPARENT);setPadding(ui.dp(8),0,0,0)}
        Ui.bindCursorToIme(input)
        Ui.clearable(input)
        search.addView(input,LinearLayout.LayoutParams(0,ui.dp(48),1f))
        root.addView(search,LinearLayout.LayoutParams(-1,ui.dp(48)).apply{setMargins(ui.dp(18),ui.dp(4),ui.dp(18),0)})
        val count=ui.text("",12,ui.muted).apply{setPadding(0,0,0,0)}
        val countRow=ui.row().apply{setPadding(ui.dp(22),ui.dp(12),ui.dp(18),ui.dp(10));gravity=Gravity.CENTER_VERTICAL}
        countRow.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        if(!failureMode){
            val detectButton=ui.text("检测图源",12,OnlineUi.BLUE,true).apply{
                setPadding(ui.dp(10),ui.dp(2),0,ui.dp(2))
                contentDescription="检测图源"
                isClickable=true;isFocusable=true
                setOnClickListener{confirmDetect()}
            }
            countRow.addView(detectButton)
        }
        root.addView(countRow)
        val bottomPadding=ui.dp(if(sourceIds==null)88 else 16)
        val list=ListView(activity).apply{divider=null;selector=ColorDrawable(Color.TRANSPARENT);setPadding(ui.dp(18),0,ui.dp(18),bottomPadding);clipToPadding=false}
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(list){view,insets->
            val bottom=bottomPadding+insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            if(view.paddingBottom!=bottom)view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,bottom)
            insets
        }
        val listHost=FrameLayout(activity)
        listHost.addView(list,FrameLayout.LayoutParams(-1,-1))
        val empty=ui.text(if(sourceIds?.isEmpty()==true)"本次没有失败的图源" else "没有匹配的图源",14,ui.muted).apply{gravity=Gravity.CENTER}
        listHost.addView(empty,FrameLayout.LayoutParams(-1,-1));list.emptyView=empty
        root.addView(listHost,LinearLayout.LayoutParams(-1,0,1f))
        var shown=all
        fun favorite(item:JSONObject)=prefs.getBoolean(item.getString("id"),false)
        fun sorted(items:List<JSONObject>)=items.sortedWith(compareByDescending<JSONObject>{favorite(it)}.thenBy{it.getString("name")})
        val adapter=object:BaseAdapter(){
            override fun getCount()=shown.size
            override fun getItem(position:Int)=shown[position]
            override fun getItemId(position:Int)=shown[position].getLong("id")
            override fun getView(position:Int,old:View?,parent:ViewGroup):View {
                val item=shown[position]
                val row=old?.tag as? Row ?: run {
                    val body=ui.row().apply{setPadding(ui.dp(14),ui.dp(12),ui.dp(8),ui.dp(12));background=ui.shape(ui.card,18)}
                    val face=FrameLayout(activity).apply{background=ui.shape(ui.tint,12);clipToOutline=true}
                    face.addView(Ui(activity).icon("repository",ui.muted),FrameLayout.LayoutParams(-1,-1))
                    val icon=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                    face.addView(icon,FrameLayout.LayoutParams(-1,-1));body.addView(face,LinearLayout.LayoutParams(ui.dp(46),ui.dp(46)))
                    val text=ui.column()
                    val name=ui.text("",15,ui.ink,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
                    val sub=ui.text("",11,ui.muted).apply{setPadding(0,ui.dp(6),0,0);maxLines=1}
                    text.addView(name);text.addView(sub)
                    body.addView(text,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(ui.dp(13),0,ui.dp(6),0)})
                    val globe=Ui(activity).icon("online",ui.muted)
                    body.addView(globe,LinearLayout.LayoutParams(ui.dp(44),ui.dp(48)))
                    val heart=Ui(activity).icon("heart",ui.muted)
                    body.addView(heart,LinearLayout.LayoutParams(ui.dp(44),ui.dp(48)))
                    val wrapper=FrameLayout(activity).apply{setPadding(0,0,0,ui.dp(9));addView(body,FrameLayout.LayoutParams(-1,-2))}
                    Row(wrapper,icon,name,sub,globe,heart,body).also{wrapper.tag=it}
                }
                val on=favorite(item)
                val seen=visited(item)
                row.heart.visibility=if(failureMode)View.GONE else View.VISIBLE
                row.root.alpha=if(failureMode&&seen).5f else 1f
                row.title.setTextColor(if(failureMode&&seen)ui.muted else ui.ink)
                row.title.text=item.getString("name")
                row.subtitle.text=when(item.getString("language")){"zh-Hans"->"简体中文";"zh-Hant"->"繁體中文";else->"中文图源"}+(if(on)" · 已收藏" else "")
                row.globe.alpha=if(seen).35f else 1f
                FavoriteStyle.paint(baseUi,row.heart,row.heart,null,on)
                row.globe.contentDescription="打开 ${item.getString("name")} 的网站并登录"
                row.globe.setOnClickListener{view->
                    visits.edit().putBoolean(item.getString("id"),true).apply()
                    paintGlobe(row,item)
                    view.isEnabled=false
                    scope.launch {
                        try {
                            val source=SourceBrowser.resolve(activity,item.getLong("id"),item.getString("name"),item.getString("language"),item.getString("packageName"))
                            val url=withContext(Dispatchers.IO){source.getHomeUrl()}
                            if(!activity.isFinishing&&!activity.isDestroyed)activity.startActivity(WebViewActivity.newIntent(activity,url,source.id,source.name))
                        }catch(e:CancellationException){throw e}
                        catch(e:Exception){Toast.makeText(activity,"无法打开图源网站：${e.message}",Toast.LENGTH_LONG).show()}
                        finally{view.isEnabled=true}
                    }
                }
                row.heart.contentDescription=if(on)"取消收藏 ${item.getString("name")}" else "收藏 ${item.getString("name")}"
                row.heart.setOnClickListener{
                    prefs.edit().putBoolean(item.getString("id"),!favorite(item)).apply()
                }
                if(failureMode){
                    row.body.isClickable=false
                    row.body.setOnClickListener(null)
                }else{
                    row.body.isClickable=true
                    row.body.setOnClickListener{BackPreview.capture(activity.window.decorView);activity.startActivity(Intent(activity,OnlineSearchActivity::class.java).putExtra("sourceId",item.getLong("id")).putExtra("sourceName",item.getString("name")).putExtra("sourceLang",item.getString("language")).putExtra("sourcePackage",item.getString("packageName")))}
                }
                icons.load(row.icon,item.getString("packageName"))
                return row.root
            }
        }
        list.adapter=adapter
        fun filter(){
            val q=input.text.toString().trim()
            shown=sorted(all.filter{it.getString("name").contains(q,true)})
            count.text=if(failureMode)"${shown.size} 个失败图源" else "${shown.size} 个图源 · ${all.count{favorite(it)}} 个收藏"
            adapter.notifyDataSetChanged()
        }
        val favoritesListener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->if(!activity.isFinishing)filter()}
        val visitsListener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->if(!activity.isFinishing)adapter.notifyDataSetChanged()}
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){
                prefs.registerOnSharedPreferenceChangeListener(favoritesListener)
                visits.registerOnSharedPreferenceChangeListener(visitsListener)
                filter()
            }
            override fun onViewDetachedFromWindow(view:View){
                prefs.unregisterOnSharedPreferenceChangeListener(favoritesListener)
                visits.unregisterOnSharedPreferenceChangeListener(visitsListener)
                icons.close();scope.cancel()
            }
        })
        input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()}})
        filter()
        if(sourceIds!=null)scope.launch {
            val loaded=activity.applicationContext.appGraph.extensionManager.getLoadedExtensions().associate { extension ->
                extension.pkgName to extension.sources.filterIsInstance<HttpSource>().filter{it.lang.startsWith("zh",true)}
            }
            all=failedEntries(entries,sourceIds,loaded);filter()
        }
        return root
    }
}
