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
        every { update["check"](any<AppUpdateChecks.Endpoint>()) } answers { Unit }
        field(update, "checks", AppUpdateChecks().apply { completed(1_000, 200) })
        return update to handler
    }

    @Test fun `opening starts one check without scheduling polling`() {
        val (update, handler) = fixture()
        invoke(update, "onResume")
        verify(exactly = 1) { update["check"](AppUpdateChecks.Endpoint.MANIFEST) }
        verify(exactly = 0) { handler.postDelayed(any<Runnable>(), any<Long>()) }
    }

    @Test fun `switching screens attaches the result without another network check`() {
        val (update, handler) = fixture()
        update.attach(mockk<android.app.Activity>(relaxed=true))
        verify(exactly=0){update["check"](any<AppUpdateChecks.Endpoint>())}
        verify(exactly=0){handler.postDelayed(any<Runnable>(),any<Long>())}
    }

    @Test fun `backgrounding does not schedule polling`() {
        val (update, handler) = fixture()
        invoke(update, "onResume")
        clearMocks(handler)
        invoke(update, "onPause")
        verify(exactly = 0) { handler.postDelayed(any<Runnable>(), any<Long>()) }
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
