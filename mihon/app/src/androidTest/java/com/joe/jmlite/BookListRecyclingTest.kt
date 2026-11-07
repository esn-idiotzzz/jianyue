package com.joe.jmlite

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookListRecyclingTest {
    @Test fun shelfHistoryAndRecentDownloadsReuseTheirCoverViews() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("settings",0)
        val acknowledged=prefs.getBoolean(FirstRunGuide.ACKNOWLEDGED,false)
        prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,true).commit()
        var activity:MainActivity?=null
        try {
            val page=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=page
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(page)
                fun images(view:View):List<ImageView> = when(view) {
                    is ImageView -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap{images(view.getChildAt(it))}
                    else -> emptyList()
                }
                for(tab in intArrayOf(1,2,0)) {
                    MainActivity::class.java.getDeclaredMethod("switchTab",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(page,tab)
                    @Suppress("UNCHECKED_CAST")
                    val rows=field("rows") as ArrayList<JSONObject>
                    rows.clear()
                    repeat(100){i->rows.add(JSONObject().put("key","2|/book$i").put("sourceId",2).put("thumbnail","").put("title","Book $i"))}
                    val adapter=field(if(tab==0)"adapter" else "shelfAdapter") as BaseAdapter
                    val parent=field(if(tab==0)"list" else "grid") as ViewGroup
                    val first=adapter.getView(0,null,parent)
                    val cover=images(first).single()
                    for(i in 1 until rows.size) {
                        val recycled=adapter.getView(i,first,parent)
                        assertSame("tab $tab must reuse its row",first,recycled)
                        assertSame("tab $tab must reuse its cover",cover,images(recycled).single())
                        assertSame("the reused image must bind to the new book",rows[i],cover.tag)
                    }
                }
            }
        } finally {
            activity?.let{page->instrumentation.runOnMainSync{page.finish()}}
            prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,acknowledged).commit()
        }
    }
}
