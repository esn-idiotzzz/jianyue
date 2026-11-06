package com.joe.jmlite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

/** A searchable, recycled row list of the packaged Chinese sources. */
object RepositoryPanel {
    private class Row(val root:FrameLayout,val icon:ImageView,val title:TextView,val subtitle:TextView,val heart:Ui.Icon,val body:LinearLayout)
    @JvmStatic fun create(activity:Activity):View {
        val ui=OnlineUi(activity)
        val baseUi=Ui(activity)
        val prefs=activity.getSharedPreferences("source_favorites",Context.MODE_PRIVATE)
        val icons=SourceIcons(activity)
        val all=activity.assets.open("bundled-sources.json").bufferedReader().use{reader->
            val entries=JSONObject(reader.readText()).getJSONArray("extensions")
            buildList<JSONObject>{for(i in 0 until entries.length()){
                val entry=entries.getJSONObject(i);val sources=entry.getJSONArray("sources")
                for(j in 0 until sources.length())add(JSONObject(sources.getJSONObject(j).toString()).put("packageName",entry.getString("packageName")))
            }}.distinctBy{it.getString("id")}
        }
        val root=ui.column()
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){}
            override fun onViewDetachedFromWindow(view:View){icons.close()}
        })
        val search=ui.row().apply{setPadding(ui.dp(12),0,ui.dp(8),0);background=ui.shape(ui.card,16)}
        search.addView(Ui(activity).icon("search",ui.muted),LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)))
        val input=EditText(activity).apply{hint="搜索图源";setSingleLine();textSize=15f;setTextColor(ui.ink);setHintTextColor(ui.muted);setBackgroundColor(Color.TRANSPARENT);setPadding(ui.dp(8),0,0,0)}
        Ui.clearable(input)
        search.addView(input,LinearLayout.LayoutParams(0,ui.dp(48),1f))
        root.addView(search,LinearLayout.LayoutParams(-1,ui.dp(48)).apply{setMargins(ui.dp(18),ui.dp(4),ui.dp(18),0)})
        val count=ui.text("",12,ui.muted).apply{setPadding(ui.dp(22),ui.dp(12),ui.dp(22),ui.dp(10))}
        root.addView(count)
        val list=ListView(activity).apply{divider=null;selector=ColorDrawable(Color.TRANSPARENT);setPadding(ui.dp(18),0,ui.dp(18),ui.dp(88));clipToPadding=false}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
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
                    val heart=Ui(activity).icon("heart",ui.muted)
                    body.addView(heart,LinearLayout.LayoutParams(ui.dp(44),ui.dp(48)))
                    val wrapper=FrameLayout(activity).apply{setPadding(0,0,0,ui.dp(9));addView(body,FrameLayout.LayoutParams(-1,-2))}
                    Row(wrapper,icon,name,sub,heart,body).also{wrapper.tag=it}
                }
                val on=favorite(item)
                row.title.text=item.getString("name")
                row.subtitle.text=when(item.getString("language")){"zh-Hans"->"简体中文";"zh-Hant"->"繁體中文";else->"中文图源"}+(if(on)" · 已收藏" else "")
                FavoriteStyle.paint(baseUi,row.heart,row.heart,null,on)
                row.heart.contentDescription=if(on)"取消收藏 ${item.getString("name")}" else "收藏 ${item.getString("name")}"
                row.heart.setOnClickListener{
                    prefs.edit().putBoolean(item.getString("id"),!favorite(item)).apply();shown=sorted(shown)
                    count.text="${shown.size} 个图源 · ${all.count{favorite(it)}} 个收藏";notifyDataSetChanged()
                }
                row.body.setOnClickListener{activity.startActivity(Intent(activity,OnlineSearchActivity::class.java).putExtra("sourceId",item.getLong("id")).putExtra("sourceName",item.getString("name")).putExtra("sourceLang",item.getString("language")).putExtra("sourcePackage",item.getString("packageName")))}
                icons.load(row.icon,item.getString("packageName"))
                return row.root
            }
        }
        list.adapter=adapter
        fun filter(){val q=input.text.toString().trim();shown=sorted(all.filter{it.getString("name").contains(q,true)});count.text="${shown.size} 个图源 · ${all.count{favorite(it)}} 个收藏";adapter.notifyDataSetChanged()}
        input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()}})
        filter();return root
    }
}
