package com.joe.jmlite

import android.view.View
import android.widget.TextView
import io.mockk.*
import java.nio.file.Path
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HistoryDetailsTest {
    @TempDir lateinit var files: Path

    @AfterEach fun cleanMocks() = unmockkAll()

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun history(loaded: Boolean, records: Int): Pair<MainActivity, TextView> {
        val activity = spyk(mockk<MainActivity>(relaxed = true), recordPrivateCalls = true)
        val empty = mockk<TextView>(relaxed = true)
        field(activity, "historyLoaded", loaded)
        field(activity, "emptyShelf", empty)
        field(activity, "onlineBooks", ArrayList<JSONObject>().apply {
            repeat(records) { add(mockk()) }
        })
        return activity to empty
    }

    @Test fun `empty history is hidden while its records are still loading`() {
        val (activity, empty) = history(false, 0)
        invoke(activity, "paintHistoryEmpty")
        verify { empty.visibility = View.GONE }
        verify(exactly = 0) { empty.visibility = View.VISIBLE }
    }

    @Test fun `a successfully loaded empty history displays the empty message`() {
        val (activity, empty) = history(true, 0)
        invoke(activity, "paintHistoryEmpty")
        verify { empty.visibility = View.VISIBLE }
    }

    @Test fun `records hide the empty message after loading`() {
        val (activity, empty) = history(true, 1)
        invoke(activity, "paintHistoryEmpty")
        verify { empty.visibility = View.GONE }
        verify(exactly = 0) { empty.visibility = View.VISIBLE }
    }

    @Test fun `switching from an empty loaded history back to loading hides its old message`() {
        val (activity, empty) = history(true, 0)
        invoke(activity, "paintHistoryEmpty")
        field(activity, "historyLoaded", false)
        invoke(activity, "paintHistoryEmpty")
        verifyOrder {
            empty.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
    }

    @Test fun `a local shelf book disables download and removal restores the action`() {
        val activity = spyk(mockk<BookInfoActivity>(relaxed = true), recordPrivateCalls = true)
        val button = mockk<TextView>(relaxed = true)
        field(activity, "downloadButton", button)
        invoke(activity, "paintDownloadButton", true)
        invoke(activity, "paintDownloadButton", false)
        verifyOrder {
            button.text = "已存在于书架"
            button.isEnabled = false
            button.alpha = .55f
            button.text = "下载到书架"
            button.isEnabled = true
            button.alpha = 1f
        }
    }

    private fun localState(status: String?): BookInfoActivity {
        val activity = spyk(mockk<BookInfoActivity>(relaxed = true), recordPrivateCalls = true)
        every { activity.filesDir } returns files.toFile()
        mockkConstructor(Store::class)
        val local = status?.let {
            mockk<JSONObject>().also { book -> every { book.optString("status") } returns it }
        }
        every { anyConstructed<Store>().read(any()) } answers {
            if (firstArg<String>() == "12345") local else null
        }
        return activity
    }

    @Test fun `only the same completed local JM number counts as being on the shelf`() {
        val activity = localState("complete")
        assertEquals(true, invoke(activity, "localDownloadExists", "12345"))
        assertEquals(false, invoke(activity, "localDownloadExists", "54321"))
    }

    @Test fun `an incomplete download is not yet a book exposed by the shelf`() {
        val activity = localState("paused")
        assertEquals(false, invoke(activity, "localDownloadExists", "12345"))
    }

    @Test fun `online favorite without a local download does not disable download`() {
        val activity = localState(null)
        assertEquals(false, invoke(activity, "localDownloadExists", "12345"))
    }
}
