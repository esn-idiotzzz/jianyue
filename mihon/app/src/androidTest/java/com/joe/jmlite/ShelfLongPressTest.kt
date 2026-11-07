package com.joe.jmlite

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.GridView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfLongPressTest {
    @Test fun aShorterShelfHoldStillOpensItsMenuOnlyAfterRelease() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("settings",0)
        val acknowledged=prefs.getBoolean(FirstRunGuide.ACKNOWLEDGED,false)
        prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,true).commit()
        var activity:MainActivity?=null
        var pointerDown=false
        val bounds=Rect()
        val down=SystemClock.uptimeMillis()
        fun touch(action:Int) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.top+32f,0)
            try{instrumentation.sendPointerSync(event)}finally{event.recycle()}
        }
        try {
            val page=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=page
            instrumentation.waitForIdleSync()
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(page)
            (field("disk") as java.util.concurrent.ExecutorService).submit{}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val rows=field("rows") as ArrayList<JSONObject>
                rows.clear();rows.add(JSONObject().put("book_folder",true).put("id","regression-folder").put("title","长按验证书籍夹"))
                ((field("grid") as GridView).adapter as android.widget.BaseAdapter).notifyDataSetChanged()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync{assertTrue((field("grid") as GridView).getChildAt(0).getGlobalVisibleRect(bounds))}
            touch(MotionEvent.ACTION_DOWN);pointerDown=true
            SystemClock.sleep(180)
            instrumentation.runOnMainSync{assertNull(field("draggingBook"))}
            SystemClock.sleep(180)
            instrumentation.runOnMainSync{assertNotNull("300 ms hold must enter the original drag state",field("draggingBook"))}
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("删除书籍夹").isEmpty())
            touch(MotionEvent.ACTION_UP);pointerDown=false
            SystemClock.sleep(250)
            instrumentation.waitForIdleSync()
            assertFalse(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("删除书籍夹").isEmpty())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally {
            if(pointerDown)touch(MotionEvent.ACTION_CANCEL)
            activity?.let{page->instrumentation.runOnMainSync{page.finish()}}
            prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,acknowledged).commit()
        }
    }
}
