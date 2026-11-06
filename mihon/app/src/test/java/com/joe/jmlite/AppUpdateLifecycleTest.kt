package com.joe.jmlite

import android.os.Handler
import android.os.SystemClock
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AppUpdateLifecycleTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun field(update: AppUpdate, name: String, value: Any) {
        AppUpdate::class.java.getDeclaredField(name).apply { isAccessible = true }.set(update, value)
    }
    private fun invoke(update: AppUpdate, name: String) {
        AppUpdate::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(update)
    }
    private fun fixture(): Pair<AppUpdate, Handler> {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000
        val update = spyk(mockk<AppUpdate>(relaxed = true), recordPrivateCalls = true)
        val handler = mockk<Handler>(relaxed = true)
        field(update, "handler", handler)
        field(update, "recheck", Runnable {})
        field(update, "checks", AppUpdateChecks().apply { completed(1_000, 200) })
        return update to handler
    }

    @Test fun `returning to the foreground schedules the next eligible check`() {
        val (update, handler) = fixture()
        invoke(update, "onResume")
        verify(exactly = 1) { handler.postDelayed(any<Runnable>(), 60_000) }
    }

    @Test fun `backgrounding cancels foreground polling`() {
        val (update, handler) = fixture()
        invoke(update, "onResume")
        clearMocks(handler)
        invoke(update, "onPause")
        verify(exactly = 1) { handler.removeCallbacks(any<Runnable>()) }
        verify(exactly = 0) { handler.postDelayed(any<Runnable>(), any<Long>()) }
    }

    @Test fun `closing cancels callbacks and later resume cannot reschedule them`() {
        val (update, handler) = fixture()
        invoke(update, "close")
        invoke(update, "onResume")
        verify(exactly = 1) { handler.removeCallbacksAndMessages(null) }
        verify(exactly = 0) { handler.postDelayed(any<Runnable>(), any<Long>()) }
    }

    @Test fun `a newer release discovered in the background remains pending for resume`() {
        val (update, handler) = fixture()
        val release = AppRelease("3.7.0", "新增功能", "${AppRelease.PROJECT_URL}/releases/tag/v3.7.0")
        field(update, "shownVersion", "3.6.0")
        field(update, "pending", release)
        invoke(update, "showPending")
        val pending = AppUpdate::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(update)
        assertSame(release, pending)
        verify(exactly = 0) { handler.postDelayed(any<Runnable>(), any<Long>()) }
    }
}
