package com.joe.jmlite

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot first-install warm-up for the bundled 禁漫天堂 source.
 *
 * It deliberately has no Activity, notification, toast or result callback: the only observable
 * effect is that the source has performed a real search before the user can dismiss the manual.
 */
object FirstRunSearch {
    private const val STARTED = "first_run_search_started"
    private const val COMPLETED = "first_run_search_completed"
    private val claimed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (preferences.getBoolean(STARTED, false)) return
        if (!claimed.compareAndSet(false, true)) return
        // Record the attempt before the network call so a process restart during the manual cannot
        // repeat the silent query.
        preferences.edit().putBoolean(STARTED, true).apply()
        scope.launch {
            val completed = runCatching { OnlineRepository.warmUpJmSearch(app) }.getOrDefault(false)
            preferences.edit().putBoolean(COMPLETED, completed).apply()
        }
    }
}
