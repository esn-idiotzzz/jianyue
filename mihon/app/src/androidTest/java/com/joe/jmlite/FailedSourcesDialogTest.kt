package com.joe.jmlite

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailedSourcesDialogTest {
    @Test fun failedRowsRemainVisibleInTheDialogAndAfterFiltering() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("settings",0)
        val acknowledged=prefs.getBoolean(FirstRunGuide.ACKNOWLEDGED,false)
        prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,true).commit()
        var activity:MainActivity?=null
        var dialog:AlertDialog?=null
        try {
            val page=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=page
            val entries=context.assets.open("bundled-sources.json").bufferedReader().use{JSONObject(it.readText()).getJSONArray("extensions")}
            val ids=(0..2).map{entries.getJSONObject(it).getJSONArray("sources").getJSONObject(0).getLong("id")}.toSet()
            instrumentation.runOnMainSync{dialog=RepositoryPanel.showFailures(page,ids,ids.size)}
            instrumentation.waitForIdleSync()
            fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
            lateinit var list:ListView
            lateinit var input:EditText
            instrumentation.runOnMainSync {
                val all=views(dialog!!.window!!.decorView)
                list=all.filterIsInstance<ListView>().single()
                input=all.filterIsInstance<EditText>().single()
                assertEquals(3,list.count)
                assertTrue("failed list needs an actual viewport",list.height>0)
                assertTrue("failed rows must be laid out",list.childCount>0)
                assertTrue(list.getChildAt(0).getGlobalVisibleRect(Rect()))
                assertFalse("opening the list must not focus its search editor",input.hasFocus())
                // A constrained window must shrink the list, not clip a screen-sized child.
                dialog!!.window!!.setLayout(dialog!!.window!!.attributes.width,(360*page.resources.displayMetrics.density).toInt())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(list.height>0)
                assertTrue(list.getChildAt(0).getGlobalVisibleRect(Rect()))
                input.setText((list.adapter.getItem(0) as JSONObject).getString("name"))
                assertTrue(list.count>0)
                input.setText("no-such-source-for-regression")
                assertEquals(0,list.count)
                assertEquals(View.VISIBLE,list.emptyView.visibility)
                input.setText("")
                assertEquals(3,list.count)
            }
        } finally {
            instrumentation.runOnMainSync{dialog?.dismiss();activity?.finish()}
            prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,acknowledged).commit()
        }
    }
}
