package com.joe.jmlite

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/** Background release checks on opening the app; no account or token is required. */
class AppUpdate(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var call: Call? = null
    private var pending: AppRelease? = null
    private var dialog: AlertDialog? = null
    private var resumed = false
    private var closed = false
    private var shownVersion: String? = null
    private val checks = AppUpdateChecks()
    private val recheck = Runnable { check() }

    private fun scheduleCheck() {
        handler.removeCallbacks(recheck)
        if (resumed && !closed) handler.postDelayed(recheck, checks.delay(SystemClock.elapsedRealtime()))
    }

    private fun check() {
        if (closed || !resumed || call != null) return
        if (checks.delay(SystemClock.elapsedRealtime()) > 0) {
            scheduleCheck()
            return
        }
        val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: return
        val request = checks.request(current)
        call = client.newCall(request).also { check ->
            check.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.post {
                        if (closed || this@AppUpdate.call !== call) return@post
                        this@AppUpdate.call = null
                        checks.completed(SystemClock.elapsedRealtime())
                        scheduleCheck()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    var status: Int? = response.code
                    val headers = response.headers
                    val release = try {
                        response.use { AppRelease.parse(it.code, it.body.string(), current) }
                    } catch (_: IOException) {
                        if (status == 200 || status == 304) status = null
                        null
                    }
                    handler.post {
                        if (!closed && this@AppUpdate.call === call) {
                            this@AppUpdate.call = null
                            checks.completed(SystemClock.elapsedRealtime(), status, headers, System.currentTimeMillis())
                            if (release != null && release.version != shownVersion) pending = release
                            showPending()
                            scheduleCheck()
                        }
                    }
                }
            })
        }
    }

    fun onResume() {
        resumed = true
        showPending()
        check()
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(recheck)
    }

    private fun showPending() {
        val release = pending ?: return
        if (!resumed || closed || activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) return
        pending = null
        shownVersion = release.version
        val ui = Ui(activity)
        val notes = ui.text("最新版本：${release.version}\n\n${release.notes}", 15, ui.ink, false).apply {
            setPadding(ui.dp(24f), ui.dp(8f), ui.dp(24f), ui.dp(8f))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(notes) }
        dialog = PredictiveBack.dialog(activity)
            .setTitle("发现新版本")
            .setView(scroll)
            .setNegativeButton("取消更新", null)
            .setPositiveButton("Github") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, "没有可打开 GitHub 的浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .create().also { update ->
                update.show()
                // Keep both actions together at the lower right, including on wide screens.
                val buttons = update.getButton(AlertDialog.BUTTON_POSITIVE).parent as? LinearLayout
                buttons?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
    }

    fun close() {
        closed = true
        call?.cancel()
        call = null
        handler.removeCallbacksAndMessages(null)
        pending = null
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        const val PROJECT_URL = AppRelease.PROJECT_URL
        private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
