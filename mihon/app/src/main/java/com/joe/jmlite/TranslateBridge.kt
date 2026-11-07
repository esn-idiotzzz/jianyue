package com.joe.jmlite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridge for the bundled "网页中英双显互译" userscript.
 *
 * The WebView only gets this interface after the user opens the translation panel, and every
 * network request is limited to the translation providers listed in the userscript header.
 */
class TranslateBridge(private val context: Context, private val webView: WebView) {
    private val preferences = context.getSharedPreferences("source_translate", Context.MODE_PRIVATE)
    private val executor get() = EXECUTOR
    private val client get() = CLIENT

    @JavascriptInterface
    fun getValue(key: String): String? = try {
        preferences.getString(key.take(96), null)
    } catch (_: Exception) {
        null
    }

    @JavascriptInterface
    fun setValue(key: String, value: String) {
        try {
            if (key.length > 96 || value.length > 262_144) return
            preferences.edit().putString(key, value).apply()
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun request(id: String, method: String, url: String, headersJson: String, body: String, timeoutMs: Int) {
        if (!allowed(url)) {
            respond(id, 0, "blocked", "{}")
            return
        }
        executor.execute {
            var status = 0
            var text = ""
            var headers = "{}"
            try {
                val headersObject = JSONObject(headersJson.ifBlank { "{}" })
                val builder = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                headersObject.keys().forEach { name ->
                    if (name.equals("Host", true) || name.equals("Content-Length", true)) return@forEach
                    builder.header(name, headersObject.optString(name))
                }
                val verb = method.uppercase().ifBlank { "GET" }
                if (verb == "POST" || verb == "PUT" || verb == "PATCH") {
                    val type = headersObject.optString("Content-Type").ifBlank { headersObject.optString("content-type") }
                        .ifBlank { "text/plain; charset=utf-8" }
                    builder.method(verb, body.toRequestBody(type.toMediaTypeOrNull()))
                } else {
                    builder.method(verb, null)
                }
                val timeout = timeoutMs.coerceIn(2_000, 60_000).toLong()
                client.newBuilder().readTimeout(timeout, TimeUnit.MILLISECONDS).build()
                    .newCall(builder.build()).execute().use { response ->
                        status = response.code
                        text = response.body?.string().orEmpty()
                        if (text.length > 4_000_000) text = text.take(4_000_000)
                        val map = JSONObject()
                        response.headers.names().forEach { name ->
                            map.put(name, response.headers.values(name).firstOrNull().orEmpty())
                        }
                        headers = map.toString()
                    }
            } catch (e: Exception) {
                status = 0
                text = e.message.orEmpty()
            }
            respond(id, status, text, headers)
        }
    }

    @JavascriptInterface
    fun openTab(url: String) {
        if (!allowed(url)) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun setClipboard(text: String) {
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("译文", text))
        } catch (_: Exception) {
        }
    }

    private fun respond(id: String, status: Int, text: String, headers: String) {
        val script = "window.__jyGMResponse&&window.__jyGMResponse(" +
            JSONObject.quote(id) + "," + status + "," +
            JSONObject.quote(text) + "," + JSONObject.quote(headers) + ")"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun allowed(url: String): Boolean = try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        scheme in setOf("http", "https") && host != null &&
            !host.equals("localhost", true) && !host.startsWith("127.") &&
            !host.startsWith("10.") && !host.startsWith("192.168.") &&
            !host.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*")) &&
            HOSTS.any { host == it || host.endsWith(".$it") }
    } catch (_: Exception) {
        false
    }

    companion object {
        private val EXECUTOR = Executors.newFixedThreadPool(6)
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Mobile Safari/537.36"
        private val HOSTS = listOf(
            "microsoft.com",
            "microsofttranslator.com",
            "googleapis.com",
            "baidu.com",
            "sogou.com",
            "iciba.com",
            "hjenglish.com",
            "youdao.com",
            "caiyunai.com",
            "caiyunapp.com",
            "qq.com",
            "alibaba.com",
            "naver.com",
            "worldlingo.com",
            "deepl.com",
            "flitto.com.cn",
            "yandex.com",
            "yandex.net",
            "pdf365.cn",
            "cnki.net",
            "xf-yun.com",
            "xfyun.cn",
            "wps.cn",
            "httpcn.com",
            "greasyfork.org",
            "google.com",
        )
    }
}
