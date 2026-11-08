package com.joe.jmlite

import android.app.Activity
import android.os.Build
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
class AppUpdate(private var activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var call: Call? = null
    private var pending: AppRelease? = null
    private var dialog: AlertDialog? = null
    private var resumed = false
    private var closed = false
    private var shownVersion: String? = null
    private val checks = sharedChecks


    fun attach(host: Activity){activity=host;resumed=true;showPending()}

    private fun check(endpoint: AppUpdateChecks.Endpoint = AppUpdateChecks.Endpoint.MANIFEST) {
        if (closed || call != null) return
        if(endpoint==AppUpdateChecks.Endpoint.API&&checks.delay(SystemClock.elapsedRealtime(),endpoint)>0)return
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val current = info.versionName ?: return
        @Suppress("DEPRECATION")
        val currentCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val request = checks.request(current, endpoint)
        call = client.newCall(request).also { requestCall ->
            requestCall.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.post {
                        if (closed || this@AppUpdate.call !== call) return@post
                        this@AppUpdate.call = null
                        checks.completed(SystemClock.elapsedRealtime(), endpoint = endpoint)
                        if (endpoint == AppUpdateChecks.Endpoint.MANIFEST) check(AppUpdateChecks.Endpoint.API)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    var status: Int? = response.code
                    val headers = response.headers
                    val result = try {
                        response.use { AppRelease.check(it.code, if (it.code == 304) "" else it.body.string(), current, currentCode) }
                    } catch (_: IOException) {
                        if (status == 200 || status == 304) status = null
                        AppRelease.Check(false, null)
                    }
                    if (status == 200 && !result.valid) status = null
                    handler.post {
                        if (!closed && this@AppUpdate.call === call) {
                            this@AppUpdate.call = null
                            checks.completed(SystemClock.elapsedRealtime(), status, headers, System.currentTimeMillis(), endpoint)
                            if(status==200&&result.valid)checks.remember(endpoint,result.release)
                            val release = result.release ?: if(status==304)checks.known(endpoint)?.takeIf{it.versionCode?.let{code->code>currentCode} ?: AppRelease.isNewer(it.version,current)} else null
                            if (release != null && release.version != shownVersion) pending = release
                            showPending()
                            if (endpoint == AppUpdateChecks.Endpoint.MANIFEST && !result.valid) check(AppUpdateChecks.Endpoint.API)
                        }
                    }
                }
            })
        }
    }

    fun onResume() {
        if(closed)return
        resumed = true
        showPending()
        check()
    }

    fun onPause() {
        resumed = false
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
        @Suppress("DEPRECATION")
        val installedCode = if (Build.VERSION.SDK_INT >= 28) {
            activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
        }
        // Old builds keep the historical GitHub page behaviour; builds that ship this direct
        // downloader (code 56 / 1.2.2+) open the release APK asset directly.
        val directApk = installedCode >= 56L && release.downloadUrl != null
        val targetUrl = if (directApk) release.downloadUrl!! else release.url
        dialog = PredictiveBack.dialog(activity)
            .setTitle("发现新版本")
            .setView(scroll)
            .setNegativeButton("取消更新", null)
            .setPositiveButton(if (directApk) "下载 APK" else "Github") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, if (directApk) "没有可打开下载链接的浏览器" else "没有可打开 GitHub 的浏览器", Toast.LENGTH_SHORT).show()
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
        private val sharedChecks=AppUpdateChecks()
        private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
