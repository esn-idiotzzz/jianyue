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
        val icons=SourceIcons(activity)
        val entries=activity.assets.open("bundled-sources.json").bufferedReader().use{reader->
            val entries=JSONObject(reader.readText()).getJSONArray("extensions")
            buildList<JSONObject>{for(i in 0 until entries.length()){
                val entry=entries.getJSONObject(i);val sources=entry.getJSONArray("sources")
                for(j in 0 until sources.length())add(JSONObject(sources.getJSONObject(j).toString()).put("packageName",entry.getString("packageName")))
            }}.distinctBy{it.getString("id")}
        }
        var all=if(sourceIds==null)entries else failedEntries(entries,sourceIds,emptyMap())
        val root=ui.column().apply{isFocusableInTouchMode=sourceIds!=null}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val search=ui.row().apply{setPadding(ui.dp(12),0,ui.dp(8),0);background=ui.shape(ui.card,16)}
        search.addView(Ui(activity).icon("search",ui.muted),LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)))
        val input=EditText(activity).apply{hint="搜索图源";setSingleLine();textSize=15f;setTextColor(ui.ink);setHintTextColor(ui.muted);setBackgroundColor(Color.TRANSPARENT);setPadding(ui.dp(8),0,0,0)}
        Ui.clearable(input)
        search.addView(input,LinearLayout.LayoutParams(0,ui.dp(48),1f))
        root.addView(search,LinearLayout.LayoutParams(-1,ui.dp(48)).apply{setMargins(ui.dp(18),ui.dp(4),ui.dp(18),0)})
        val count=ui.text("",12,ui.muted).apply{setPadding(ui.dp(22),ui.dp(12),ui.dp(22),ui.dp(10))}
        root.addView(count)
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
                row.title.text=item.getString("name")
                row.subtitle.text=when(item.getString("language")){"zh-Hans"->"简体中文";"zh-Hant"->"繁體中文";else->"中文图源"}+(if(on)" · 已收藏" else "")
                FavoriteStyle.paint(baseUi,row.heart,row.heart,null,on)
                row.globe.contentDescription="打开 ${item.getString("name")} 的网站并登录"
                row.globe.setOnClickListener{view->
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
                row.body.setOnClickListener{activity.startActivity(Intent(activity,OnlineSearchActivity::class.java).putExtra("sourceId",item.getLong("id")).putExtra("sourceName",item.getString("name")).putExtra("sourceLang",item.getString("language")).putExtra("sourcePackage",item.getString("packageName")))}
                icons.load(row.icon,item.getString("packageName"))
                return row.root
            }
        }
        list.adapter=adapter
        fun filter(){val q=input.text.toString().trim();shown=sorted(all.filter{it.getString("name").contains(q,true)});count.text="${shown.size} 个图源 · ${all.count{favorite(it)}} 个收藏";adapter.notifyDataSetChanged()}
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->filter()}
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){prefs.registerOnSharedPreferenceChangeListener(listener);filter()}
            override fun onViewDetachedFromWindow(view:View){prefs.unregisterOnSharedPreferenceChangeListener(listener);icons.close();scope.cancel()}
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
