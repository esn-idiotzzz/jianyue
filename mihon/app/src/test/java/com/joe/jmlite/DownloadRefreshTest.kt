package com.joe.jmlite

import io.mockk.*
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DownloadRefreshTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun field(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun activity(): Pair<MainActivity, ExecutorService> {
        val activity = spyk(mockk<MainActivity>(relaxed = true), recordPrivateCalls = true)
        val disk = mockk<ExecutorService>(relaxed = true)
        every { disk.isShutdown } returns false
        every { activity.isDestroyed } returns false
        field(activity, "disk", disk)
        field(activity, "reading", true)
        field(activity, "operation", true)
        return activity to disk
    }
    private fun refresh(activity: MainActivity, force: Boolean) {
        MainActivity::class.java.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, force)
    }
    @Test fun `a completion refresh is queued after an in flight read or mutation`() {
        val (activity, disk) = activity()
        refresh(activity, true)
        verify(exactly = 1) { disk.execute(any<Runnable>()) }
    }
    @Test fun `an ordinary progress poll does not flood an in flight read`() {
        val (activity, disk) = activity()
        refresh(activity, false)
        verify(exactly = 0) { disk.execute(any<Runnable>()) }
    }
    @Test fun `even a completion waits for a shelf drag to finish before rebinding cards`() {
        val (activity, disk) = activity()
        field(activity, "draggingBook", mockk<JSONObject>())
        refresh(activity, true)
        verify(exactly = 0) { disk.execute(any<Runnable>()) }
    }
}
