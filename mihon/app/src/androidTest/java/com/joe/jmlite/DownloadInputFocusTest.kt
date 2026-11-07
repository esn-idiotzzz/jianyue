package com.joe.jmlite

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device: real touches and IME/layout changes must keep the chosen editor. */
@RunWith(AndroidJUnit4::class)
class DownloadInputFocusTest {
    @Test fun alternatingTouchesTypeOnlyInTheChosenInput() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val preferences=context.getSharedPreferences("settings",0)
        val previous=preferences.getBoolean(FirstRunGuide.ACKNOWLEDGED,false)
        preferences.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,true).commit()
        var activity:MainActivity?=null
        try {
            val page=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=page
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("switchTab",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(page,0)
            }
            instrumentation.waitForIdleSync()
            val jm=page.findViewById<EditText>(1001)
            val online=page.findViewById<EditText>(1006)
            instrumentation.runOnMainSync{jm.setText("");online.setText("")}
            fun tapAndType(target:EditText,other:EditText,text:String) {
                var unchanged=""
                val bounds=Rect()
                instrumentation.runOnMainSync {
                    (page.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(target.windowToken,0)
                }
                SystemClock.sleep(350)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    unchanged=other.text.toString()
                    target.setText("")
                    assertTrue(target.getGlobalVisibleRect(bounds))
                }
                val down=SystemClock.uptimeMillis()
                for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.left+24f,bounds.exactCenterY(),0)
                    try{instrumentation.sendPointerSync(event)}finally{event.recycle()}
                }
                SystemClock.sleep(350)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertSame("touch must select this editor after keyboard resize",target,page.currentFocus)
                    var header=target.parent as ViewGroup
                    while(header !is DownloadHeader)header=header.parent as ViewGroup
                    header.requestFocus() // The same deferred request ListView makes after a resize.
                    assertSame("header focus restoration must preserve the chosen editor",target,page.currentFocus)
                }
                instrumentation.sendStringSync(text)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(text,target.text.toString())
                    assertEquals("other input must not receive characters",unchanged,other.text.toString())
                }
            }
            repeat(3) { index ->
                tapAndType(online,jm,"online$index")
                tapAndType(jm,online,"123$index")
            }
        } finally {
            activity?.let{page->instrumentation.runOnMainSync{page.finish()}}
            preferences.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,previous).commit()
        }
    }
}
