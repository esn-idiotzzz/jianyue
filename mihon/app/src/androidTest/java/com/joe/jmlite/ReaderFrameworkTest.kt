package com.joe.jmlite

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ReaderFrameworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun until(timeout: Long = 8000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(30)
        }
        assertTrue("condition did not become true within $timeout ms", condition())
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun viewer(reader: ReaderActivity): Viewer? = (field(reader, "viewerFlow") as StateFlow<*>).value as? Viewer
    private fun launchReader(extras: Intent): ReaderActivity {
        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        try {
            extras.setClassName(context, "com.joe.jmlite.MihonEntryActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(extras)
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 12000)
            assertNotNull("entry opens the actual Mihon ReaderActivity", activity)
            return activity as ReaderActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
    private fun open(extras: Intent): ReaderActivity = launchReader(extras).also { reader ->
        until { reader.viewModel.state.value.viewerChapters?.currChapter?.pages?.isNotEmpty() == true }
    }
    private fun pendingLoader(reader: ReaderActivity): JmPageLoader? {
        val chapters = field(reader.viewModel, "chapterList") as? List<*> ?: return null
        return (chapters.firstOrNull() as? eu.kanade.tachiyomi.ui.reader.model.ReaderChapter)?.pageLoader as? JmPageLoader
    }
    private fun close(activity: Activity?) { if (activity != null) instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }

    private fun sliderBounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.className?.toString() == "android.widget.SeekBar") {
            val area = Rect(); node.getBoundsInScreen(area)
            if (!area.isEmpty) return area
        }
        for (index in 0 until node.childCount) sliderBounds(node.getChild(index))?.let { return it }
        return null
    }

    private fun pointer(down: Long, action: Int, x: Float, y: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun realTimeProgressDrag(reader: ReaderActivity) {
        instrumentation.runOnMainSync { viewer(reader)!!.moveToPage(reader.viewModel.state.value.viewerChapters!!.currChapter.pages!![0]) }
        until { reader.viewModel.state.value.currentPage == 1 }
        instrumentation.runOnMainSync { reader.showMenu() }
        var area: Rect? = null
        until { area = sliderBounds(instrumentation.uiAutomation.rootInActiveWindow); area != null }
        val bounds = area!!
        Thread.sleep(250) // ReaderAppBars animates into position for 200 ms.
        area = sliderBounds(instrumentation.uiAutomation.rootInActiveWindow)
        val stableBounds = area ?: bounds
        // Accessibility semantics include outer padding; start safely inside the actual track.
        val inset = 16 * context.resources.displayMetrics.density
        val left = stableBounds.left + inset; val right = stableBounds.right - inset
        val second = left + (right - left) / 3f; val third = left + (right - left) * 2f / 3f
        val y = stableBounds.exactCenterY(); val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, left, y)
        Thread.sleep(50)
        try {
            for (step in 1..12) {
                pointer(down, MotionEvent.ACTION_MOVE, left + (second - left) * step / 12f, y)
                Thread.sleep(16)
            }
            try { until { reader.viewModel.state.value.currentPage == 2 } } catch (failure: Throwable) {
                throw AssertionError("Held slider did not select page 2: bounds=$stableBounds current=${reader.viewModel.state.value.currentPage} menu=${reader.viewModel.state.value.menuVisible} scrolling=${reader.isScrollingThroughPages}", failure)
            }
            assertEquals("actual Mihon image follows the held slider before finger release", 2, reader.viewModel.state.value.currentPage)
            pointer(down, MotionEvent.ACTION_MOVE, third, y)
            until { reader.viewModel.state.value.currentPage == 3 }
            pointer(down, MotionEvent.ACTION_MOVE, second, y)
            until { reader.viewModel.state.value.currentPage == 2 }
        } finally {
            pointer(down, MotionEvent.ACTION_UP, second, y)
        }
        Thread.sleep(120)
        assertEquals("rapid actual slider dragging retains the newest target after release", 2, reader.viewModel.state.value.currentPage)
    }

    @Test fun actualFrameworkModesAndLegacyPosition() {
        val store = Store(context)
        val id = "999" + (System.currentTimeMillis() % 1000000000).toString().padStart(9, '0')
        assertFalse("fixture must never replace existing user content", store.dir(id).exists())
        var activity: ReaderActivity? = null
        val preferences = context.getSharedPreferences("reading", 0)
        try {
            val chapter = File(store.dir(id), "0001-127"); assertTrue(chapter.mkdirs())
            for (index in 1..4) {
                val image = Bitmap.createBitmap(80, 240, Bitmap.Config.RGB_565)
                image.eraseColor(if (index % 2 == 0) Color.BLUE else Color.CYAN)
                FileOutputStream(File(chapter, "%06d.jpg".format(index))).use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                image.recycle()
            }
            store.write(id, JSONObject().put("title", "Framework synthetic book").put("status", "complete").put("done", 4).put("total", 4))
            preferences.edit().putInt("book_$id", 2).commit()
            activity = open(Intent().putExtra("library_key", "download:$id").putExtra("title", "Framework synthetic book"))
            val reader = activity
            assertTrue(reader.viewModel.manga!!.url.startsWith("jmlite://library/"))
            assertEquals(4, reader.viewModel.state.value.viewerChapters!!.currChapter.pages!!.size)
            assertTrue(reader.viewModel.state.value.viewerChapters!!.currChapter.pageLoader is JmPageLoader)
            until { reader.viewModel.state.value.currentPage == 3 }
            assertEquals("existing book_<id> progress migrates even through library_key", 3, reader.viewModel.state.value.currentPage)
            for ((mode, expected) in listOf(
                ReadingMode.LEFT_TO_RIGHT to "L2RPagerViewer",
                ReadingMode.RIGHT_TO_LEFT to "R2LPagerViewer",
                ReadingMode.VERTICAL to "VerticalPagerViewer",
                ReadingMode.WEBTOON to "WebtoonViewer",
            )) {
                instrumentation.runOnMainSync { reader.viewModel.setMangaReadingMode(mode) }
                until { viewer(reader)?.javaClass?.simpleName == expected }
                assertEquals("uses the actual Mihon $expected", expected, viewer(reader)!!.javaClass.simpleName)
            }
            realTimeProgressDrag(reader)
            instrumentation.runOnMainSync { reader.showMenu() }
            until { reader.viewModel.state.value.menuVisible }
            instrumentation.runOnMainSync { reader.hideMenu() }
            until { !reader.viewModel.state.value.menuVisible }
            val realLoader = reader.viewModel.state.value.viewerChapters!!.currChapter.pageLoader!!
            val page = reader.viewModel.state.value.viewerChapters!!.currChapter.pages!![1]
            instrumentation.runOnMainSync { viewer(reader)!!.moveToPage(page) }
            until { reader.viewModel.state.value.currentPage == 2 }
            val chapterId = reader.viewModel.state.value.viewerChapters!!.currChapter.chapter.id
            close(reader); activity = null
            until { realLoader.isRecycled }
            until { runBlocking { context.appGraph.jmChapterRepository.getChapterById(chapterId)!!.lastPageRead == 1 } }
            val reopened = open(Intent().putExtra("library_key", "download:$id").putExtra("title", "Renamed synthetic book")); activity = reopened
            until { reopened.viewModel.state.value.currentPage == 2 }
            assertEquals("Mihon database retains progress after reopen", 2, reopened.viewModel.state.value.currentPage)
            assertEquals("renamed title refreshes without resetting reader progress", "Renamed synthetic book", reopened.viewModel.manga!!.title)
        } finally {
            close(activity)
            preferences.edit().remove("book_$id").commit()
            store.delete(id)
        }
    }

    @Test fun onlineLoaderUsesBoundedTemporaryCacheAndReleasesWorkers() = runBlocking {
        val transport = ReaderSyntheticHttps()
        URL.setURLStreamHandlerFactory(transport)
        val settings = context.getSharedPreferences("settings", 0)
        val previousHost = settings.getString("api_host", "")
        settings.edit().putString("api_host", "https://framework.synthetic.invalid").commit()
        val loader = JmPageLoader(context, "jmlite://online/99221")
        var activity: ReaderActivity? = null
        try {
            val pages = loader.getPages()
            assertEquals(20, pages.size)
            val session = field(loader, "session") as OnlineSession
            loader.loadPage(pages[0])
            loader.loadPage(pages[1])
            loader.loadPage(pages[2])
            until { pages[0].status == Page.State.Ready && transport.active.get() == 0 && transport.imageRequests.size == 12 }
            until { pages[1].status == Page.State.Ready && pages[2].status == Page.State.Ready }
            assertTrue("online image workers remain bounded by six", transport.peak.get() in 1..6)
            val counts = transport.imageRequests.mapValues { it.value.get() }
            assertTrue("forward window has twelve distinct images", counts.keys.all { it.substringAfterLast('/').substringBefore('.').toInt() in 1..12 })
            pages[0].stream!!.invoke().use { assertTrue(it.read() >= 0) }
            loader.loadPage(pages[0]); Thread.sleep(150)
            assertEquals("rebind and back-reading reuse session files", counts, transport.imageRequests.mapValues { it.value.get() })
            assertTrue(session.directory().isDirectory)
            loader.setVisiblePage(15)
            loader.loadPage(pages[15]); loader.loadPage(pages[14]); loader.loadPage(pages[16])
            until { listOf(14, 15, 16).all { pages[it].status == Page.State.Ready } }
            assertTrue("preloaded neighbors cannot invalidate the visible page's request", pages[15].status == Page.State.Ready)
            loader.loadPage(pages[0]); until { pages[0].status == Page.State.Ready }
            loader.recycle()
            assertTrue(loader.isRecycled)
            assertNull(field(loader, "session")); assertNull(field(loader, "api"))
            until { !session.directory().exists() && transport.active.get() == 0 }
            val pool = field(session, "workers") as java.util.concurrent.ThreadPoolExecutor
            assertTrue("all temporary-cache workers terminate before directory deletion", pool.isTerminated)
            assertTrue((field(loader, "jobs") as Map<*, *>).isEmpty())
            activity = open(Intent().putExtra("online_id", "99221").putExtra("title", "Online synthetic fixture"))
            val reader = activity
            assertEquals("Synthetic framework album", reader.viewModel.manga!!.title)
            val actualLoader = reader.viewModel.state.value.viewerChapters!!.currChapter.pageLoader as JmPageLoader
            val actualSession = field(actualLoader, "session") as OnlineSession
            until { reader.viewModel.state.value.viewerChapters!!.currChapter.pages!![0].status == Page.State.Ready }
            close(reader); activity = null
            until { actualLoader.isRecycled && !actualSession.directory().exists() }
            transport.pageCount = 100; transport.delayMs = 180
            val seeking = JmPageLoader(context, "jmlite://online/99229")
            try {
                val many = seeking.getPages()
                for (target in listOf(10, 35, 70, 20, 90, 5, 60, 80)) {
                    seeking.setVisiblePages(listOf(many[target]))
                    seeking.setVisiblePage(target)
                    for (index in (target - 2)..minOf(many.lastIndex, target + 11)) seeking.loadPage(many[index])
                    seeking.loadPage(many[0]) // An old holder arriving after a newer seek must not revive its job.
                    assertTrue("rapid seeking keeps display jobs within the current window", (field(seeking, "jobs") as Map<*, *>).size <= 14)
                }
                until { many[80].status == Page.State.Ready }
                assertTrue("latest seek target completes after old jobs are cancelled", many[80].status == Page.State.Ready)
                val shortImages = many.subList(65, 81)
                seeking.setVisiblePages(shortImages)
                seeking.setVisiblePage(80)
                until { shortImages.all { it.status == Page.State.Ready } }
                assertTrue("all sixteen actually visible short images survive job trimming", shortImages.all { it.status == Page.State.Ready })
                assertTrue("bounded demand queue remains at or below twelve", (field(field(seeking, "session")!!, "queue") as java.util.concurrent.BlockingDeque<*>).size <= 12)
                seeking.setVisiblePages(listOf(many[5])); seeking.setVisiblePage(5, many[5])
                until { many[5].status == Page.State.Ready }
                Thread.sleep(120)
                assertTrue("an old cancellation cannot replace a newer ready state", many[5].status == Page.State.Ready)
            } finally {
                val temporary = (field(seeking, "session") as? OnlineSession)?.directory()
                seeking.recycle()
                until { temporary == null || !temporary.exists() }
            }
            transport.pageCount = 20; transport.delayMs = 60
            // Exercise cancellation while the loader has no published ViewerChapters owner yet.
            transport.metadataDelayMs = 1800
            for (attempt in 1..3) {
                val pending = launchReader(Intent().putExtra("online_id", "9923$attempt")); activity = pending
                until { pendingLoader(pending)?.let { field(it, "api") != null } == true }
                assertNull(pending.viewModel.state.value.viewerChapters)
                val owned = pendingLoader(pending)!!
                val started = System.currentTimeMillis()
                close(pending); activity = null
                until { owned.isRecycled && field(owned, "api") == null && field(owned, "session") == null }
                assertTrue("initial metadata cancellation releases its loader promptly", System.currentTimeMillis() - started < 2500)
            }
            transport.metadataDelayMs = 0; transport.pageCount = 0
            val cacheRoot = File(context.cacheDir, "online")
            val beforeEmpty = cacheRoot.listFiles()?.map { it.name }?.toSet().orEmpty()
            val empty = launchReader(Intent().putExtra("online_id", "99239")); activity = empty
            until { empty.viewModel.state.value.initError != null }
            val rejected = pendingLoader(empty)!!
            assertTrue("empty page manifests recycle the loader before reader publication", rejected.isRecycled)
            assertNull(field(rejected, "session")); assertNull(empty.viewModel.state.value.viewerChapters)
            until { cacheRoot.listFiles()?.map { it.name }?.toSet().orEmpty() == beforeEmpty }
            close(empty); activity = null
        } finally {
            close(activity)
            loader.recycle()
            settings.edit().putString("api_host", previousHost).commit()
        }
    }
}
