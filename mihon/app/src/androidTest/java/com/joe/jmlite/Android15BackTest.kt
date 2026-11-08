package com.joe.jmlite

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Uses SystemUI's actual gesture recognizer and production registered callbacks, without reflection dispatch. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=35)
class Android15BackTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    // The public two-argument injector waits for animations, which cannot test overlapping
    // gestures. Run this test with am instrument --no-hidden-api-checks to use the TestApi.
    private val injector by lazy { android.app.UiAutomation::class.java.getMethod("injectInputEvent",android.view.InputEvent::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType) }
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun field(page:Any,name:String)=page.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(page)
    private fun waitUntil(message:String,condition:()->Boolean) {
        repeat(100) { var ready=false;main { ready=condition() };if(ready)return;SystemClock.sleep(30) }
        fail(message)
    }
    private fun swipe(left:Boolean,duration:Long=150) {
        val metrics=context.resources.displayMetrics
        val bounds=Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        val width=bounds.width();val y=bounds.top+bounds.height()*.52f
        val from=if(left)bounds.left+2f else bounds.right-2f
        val to=if(left)bounds.left+width*.42f else bounds.right-width*.42f
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(injector.invoke(instrumentation.uiAutomation,event,true,false) as Boolean) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,from)
        repeat(10) { SystemClock.sleep(duration/10);send(MotionEvent.ACTION_MOVE,from+(to-from)*(it+1)/10f) }
        send(MotionEvent.ACTION_UP,to)
    }
    private fun withMain(test:(MainActivity)->Unit) {
        val prefs=context.getSharedPreferences("settings",0)
        val had=prefs.contains(FirstRunGuide.ACKNOWLEDGED);val ack=prefs.getBoolean(FirstRunGuide.ACKNOWLEDGED,false)
        prefs.edit().putBoolean(FirstRunGuide.ACKNOWLEDGED,true).commit()
        val page=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            waitUntil("main has focus") { page.hasWindowFocus() }
            (field(page,"disk") as java.util.concurrent.ExecutorService).submit{}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.waitForIdleSync();SystemClock.sleep(350)
            test(page)
        } finally {
            main { page.finish();BackPreview.clear() }
            prefs.edit().apply { if(had)putBoolean(FirstRunGuide.ACKNOWLEDGED,ack) else remove(FirstRunGuide.ACKNOWLEDGED) }.commit()
        }
    }

    @Test fun realSystemFastLeftRightGesturesKeepPhysicalDirectionFor24Commits() {
        val page=instrumentation.startActivitySync(Intent(context,AllDownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as AllDownloadsActivity
        lateinit var surface:FrameLayout
        lateinit var owner:PredictiveBack
        var ownerReady=false
        val actions=AtomicInteger();val directions=mutableListOf<Float>();val previews=mutableListOf<Boolean>()
        try {
            main {
                (field(page,"predictiveBack") as PredictiveBack).close()
                surface=FrameLayout(page).apply { setBackgroundColor(0xff37474f.toInt()) }
                page.setContentView(surface,ViewGroup.LayoutParams(-1,-1))
                owner=PredictiveBack(page,surface,{true},{false},{
                    directions.add(surface.translationX);previews.add(owner.isLeavingByGesture);actions.incrementAndGet()
                })
                ownerReady=true
                page.javaClass.getDeclaredField("predictiveBack").apply { isAccessible=true }.set(page,owner)
            }
            waitUntil("gesture fixture is attached and focused") { page.hasWindowFocus()&&surface.width>0 }
            SystemClock.sleep(350)
            repeat(12) { pair ->
                swipe(true,110)
                // SystemUI first finishes its spring before dispatching onBackInvoked. Start
                // the opposite gesture during our 220 ms exit, after that dispatch is real.
                try { waitUntil("first system commit for pair $pair") { field(owner,"committed") as Boolean } }
                catch(error:AssertionError) { throw AssertionError("first pair=$pair actions=${actions.get()} directions=$directions focus=${page.hasWindowFocus()}",error) }
                SystemClock.sleep(30);swipe(false,110)
                try { waitUntil("two actual system callbacks for pair $pair") { actions.get()==(pair+1)*2 } }
                catch(error:AssertionError) { throw AssertionError("pair=$pair actions=${actions.get()} directions=$directions previews=$previews",error) }
                main {
                    assertTrue("left gesture must leave to the right: ${directions[pair*2]}",directions[pair*2]>surface.width*.9f)
                    assertTrue("right gesture must leave to the left: ${directions[pair*2+1]}",directions[pair*2+1]<-surface.width*.9f)
                    assertTrue(previews[pair*2]);assertTrue(previews[pair*2+1])
                    assertEquals(0f,surface.translationX,0f)
                }
            }
            File(context.cacheDir,"system-gesture-directions.json").writeText(JSONObject().put("commits",actions.get()).put("exit_x",org.json.JSONArray(directions)).put("preview",org.json.JSONArray(previews)).toString(2))
        } finally { main { if(ownerReady)owner.close();page.finish() } }
    }

    @Test fun realSystemBackToHomeThenHotResumeWorksSixTimes() = withMain { page ->
        repeat(6) {
            swipe(it%2==0)
            waitUntil("back gesture backgrounds root, cycle $it") { !page.hasWindowFocus() }
            SystemClock.sleep(300)
            val command=instrumentation.uiAutomation.executeShellCommand("am start -W -n com.joe.jmlite/com.joe.jmlite.MainActivity")
            FileInputStream(command.fileDescriptor).use { it.readBytes() };command.close()
            waitUntil("same root returns to foreground, cycle $it") { page.hasWindowFocus() }
            main { assertFalse(page.isFinishing) }
        }
    }

    @Test fun folderPreviewOmitsCapsuleAndLiveCapsuleOnlyFadesInOnce() = withMain { page ->
        repeat(4) { cycle ->
            lateinit var nav:View
            main {
                nav=field(page,"nav") as View
                assertEquals(1f,nav.alpha,0f)
                val folder=JSONObject().put("book_folder",true).put("id","back-animation-test").put("title","动画验证")
                @Suppress("UNCHECKED_CAST") val folders=field(page,"bookFolders") as ArrayList<JSONObject>
                folders.add(folder)
                MainActivity::class.java.getDeclaredMethod("open",JSONObject::class.java).apply { isAccessible=true }.invoke(page,folder)
                val stack=BackPreview::class.java.getDeclaredField("stack").apply { isAccessible=true }.get(null) as java.util.ArrayDeque<*>
                val snapshot=stack.peek() as Bitmap
                val point=IntArray(2);nav.getLocationOnScreen(point)
                val decor=page.window.decorView
                val x=((point[0]+nav.width/2f)*snapshot.width/decor.width).toInt().coerceIn(0,snapshot.width-1)
                val y=((point[1]+nav.height/2f)*snapshot.height/decor.height).toInt().coerceIn(0,snapshot.height-1)
                assertEquals("capsule must not be frozen into preview",Ui(page).bg,snapshot.getPixel(x,y))
            }
            waitUntil("capsule hidden in folder") { nav.visibility==View.GONE }
            waitUntil("folder enter completes before swipe") { !PageMotion.isEntering(page) }
            swipe(cycle%2==0)
            var last=0f;var sawPartial=false;val samples=org.json.JSONArray()
            waitUntil("folder returns to root") { (field(page,"folderId") as String).isEmpty() }
            repeat(18) {
                main {
                    assertTrue("capsule alpha must never restart: $last -> ${nav.alpha}",nav.alpha+.02f>=last)
                    if(nav.alpha>0f&&nav.alpha<1f)sawPartial=true
                    last=nav.alpha
                    samples.put(last.toDouble())
                }
                SystemClock.sleep(16)
            }
            assertTrue("live capsule must actually fade in",sawPartial)
            main { assertEquals(1f,nav.alpha,0f);assertEquals(0f,nav.translationY,0f) }
            File(context.cacheDir,"capsule-$cycle.json").writeText(samples.toString())
        }
    }

    @Test fun swipeDuringEnterIsIgnoredThenWorksAfterEnter() = withMain { page ->
        repeat(2) { cycle ->
            val id="enter-direct-$cycle"
            val folder=JSONObject().put("book_folder",true).put("id",id).put("title","进入动画验证$cycle")
            main {
                @Suppress("UNCHECKED_CAST") val folders=field(page,"bookFolders") as ArrayList<JSONObject>
                folders.add(folder)
                MainActivity::class.java.getDeclaredMethod("open",JSONObject::class.java).apply { isAccessible=true }.invoke(page,folder)
            }
            waitUntil("folder enter animation active") { PageMotion.isEntering(page) }
            swipe(cycle%2==0,80)
            // A swipe while the enter transition is still running is deliberately consumed.
            SystemClock.sleep(420)
            main {
                assertEquals("unfinished enter must not navigate $cycle",id,field(page,"folderId") as String)
                assertFalse(page.isFinishing)
                assertTrue(page.hasWindowFocus())
            }
            waitUntil("enter animation completes $cycle") { !PageMotion.isEntering(page) }
            swipe(cycle%2==0,80)
            waitUntil("completed enter allows back $cycle") { (field(page,"folderId") as String).isEmpty() }
            main {
                assertFalse(page.isFinishing)
                assertTrue(page.hasWindowFocus())
            }
        }
    }
    @Test fun privacyShelfReturnRestoresCachedShelfWithoutEmptyFlash() = withMain { page ->
        repeat(2) { cycle ->
            val folder=JSONObject().put("book_folder",true).put("id","privacy-cache-$cycle").put("title","隐私返回验证$cycle")
            main {
                @Suppress("UNCHECKED_CAST") val folders=field(page,"bookFolders") as ArrayList<JSONObject>
                folders.add(folder)
                MainActivity::class.java.getDeclaredMethod("setPrivateShelf",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(page,true)
            }
            waitUntil("privacy shelf active") { field(page,"privateShelf") as Boolean }
            main {
                MainActivity::class.java.getDeclaredMethod("setPrivateShelf",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(page,false)
                val empty=field(page,"emptyShelf") as View
                // The cached normal shelf is built synchronously; refresh runs afterwards on disk.
                assertEquals("normal shelf must not flash empty",View.GONE,empty.visibility)
                assertEquals("",field(page,"folderId") as String)
                assertFalse(page.isFinishing)
            }
        }
    }
    @Test fun allSixToolbarVariantsFinishAfterInWindowPopWithoutBlackFrames() = withMain { page ->
        val local=JSONObject().put("id","animation-test").put("title","书籍信息动画验证").put("type","tree").put("uri","content://invalid-test/book")
        val jm=JSONObject().put("id","animation-test").put("title","JM详情动画验证").put("jm",true).put("sourceId",-1L)
        val intents=listOf(
            Intent(context,BookInfoActivity::class.java).putExtra("book",local.toString()),
            Intent(context,BookInfoActivity::class.java).putExtra("book",jm.toString()),
            Intent(context,OnlineMangaActivity::class.java).putExtra("sourceId",Long.MAX_VALUE).putExtra("url","animation-test").putExtra("title","漫画详情动画验证"),
            Intent(context,OnlineSearchActivity::class.java).putExtra("sourceId",Long.MAX_VALUE).putExtra("sourceName","图源动画验证"),
            Intent(context,OnlineSearchActivity::class.java),
            Intent(context,AllDownloadsActivity::class.java),
        )
        fun backButton(view:View):View? {
            if(view.isClickable&&view.contentDescription?.toString()?.startsWith("返回")==true)return view
            if(view is ViewGroup)repeat(view.childCount) { backButton(view.getChildAt(it))?.let { found->return found } }
            return null
        }
        intents.forEachIndexed { index,intent ->
            val monitor=instrumentation.addMonitor(intent.component!!.className,null,false)
            main { BackPreview.capture(page.window.decorView);page.startActivity(intent) }
            val child=instrumentation.waitForMonitorWithTimeout(monitor,4000) as Activity
            instrumentation.removeMonitor(monitor)
            try {
                waitUntil("toolbar page has focus") { child.hasWindowFocus() }
                SystemClock.sleep(450)
                main { assertFalse("enter tracker must finish for ${child.javaClass.simpleName}", PageMotion.isEntering(child)) }
                main {
                    val keyboard=child.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    child.currentFocus?.let { keyboard.hideSoftInputFromWindow(it.windowToken,0);it.clearFocus() }
                }
                SystemClock.sleep(300)
                main {
                    assertTrue("toolbar is clickable",backButton(child.window.decorView)!!.performClick())
                    assertFalse("finish must wait for in-window pop",child.isFinishing)
                    assertNotNull(child.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).animation)
                }
                repeat(8) { frame ->
                    SystemClock.sleep(25)
                    val bitmap=instrumentation.uiAutomation.takeScreenshot()!!
                    var lit=0;var count=0
                    for(y in bitmap.height/5 until bitmap.height*4/5 step 16)for(x in bitmap.width/5 until bitmap.width*4/5 step 16) {
                        val pixel=bitmap.getPixel(x,y);if((pixel and 0x00ffffff)>0x030303)lit++;count++
                    }
                    assertTrue("toolbar $index frame $frame must not go black",lit>count*.8f)
                    if(frame==1)File(context.cacheDir,"toolbar-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                waitUntil("toolbar pop finishes") { child.isFinishing }
                waitUntil("toolbar reveals previous page") { page.hasWindowFocus() }
            } finally { main { if(!child.isFinishing)child.finish() } }
        }
    }
}
