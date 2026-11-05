package com.joe.jmlite

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.domain.extension.model.ExtensionStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Loads only the Chinese extension APKs shipped with JianYue. No runtime installer is used. */
object BundledSources {
    private const val SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    private val loadMutex = Mutex()
    private var prepared = false
    @Volatile private var cachedSources: List<HttpSource>? = null

    private val store = ExtensionStore(
        indexUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
        name = "Keiyoushi",
        badgeLabel = "KEI",
        signingKey = SIGNING_KEY,
        contact = ExtensionStore.Contact(website = "https://keiyoushi.github.io", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    @Synchronized
    fun prepareFiles(context: Context) {
        if (prepared) return
        val app = context.applicationContext
        val entries = app.assets.open("bundled-sources.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("extensions")
        }
        check(entries.length() > 0) { "应用内置图源缺失，请重新安装" }
        val extDir = File(app.filesDir, "exts")
        check(extDir.isDirectory || extDir.mkdirs()) { "无法创建内置图源目录" }
        val packages = HashSet<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val pkg = entry.getString("packageName")
            packages.add(pkg)
            val expectedHash = entry.getString("sha256")
            val target = File(extDir, "$pkg.ext")
            if (!target.isFile || target.length() != entry.getLong("size") || sha256(target) != expectedHash) {
                val staged = File(extDir, "$pkg.part")
                if (staged.exists()) check(staged.delete()) { "无法清理图源临时文件：$pkg" }
                try {
                    app.assets.open("exts/$pkg.apk").use { input ->
                        FileOutputStream(staged).use { output ->
                            // Mark dynamic code read-only before writing, as required on Android 14+.
                            check(staged.setReadOnly()) { "无法保护图源文件：$pkg" }
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(sha256(staged) == expectedHash) { "内置图源校验失败：$pkg" }
                    check(staged.renameTo(target)) { "无法更新内置图源：$pkg" }
                } finally {
                    staged.delete()
                }
            }
            check(target.setReadOnly()) { "无法保护图源文件：$pkg" }
        }
        extDir.listFiles()?.filter { it.extension == "ext" && it.nameWithoutExtension !in packages }
            ?.forEach { check(it.delete()) { "无法移除旧图源：${it.name}" } }
        prepared = true
    }

    suspend fun ensure(context: Context, onProgress: (String) -> Unit): List<HttpSource> =
        withContext(Dispatchers.IO) {
            loadMutex.withLock {
                cachedSources?.let { return@withLock it }
                val app = context.applicationContext
                onProgress("正在加载内置中文图源…")
                prepareFiles(app)
                val packages = app.assets.list("exts").orEmpty().filter { it.endsWith(".apk") }
                    .map { it.removeSuffix(".apk") }.toSet()
                val graph = app.appGraph
                graph.extensionStoreRepository.upsert(store)
                val sourceManager = graph.sourceManager
                val sources = withTimeout(60_000) {
                    graph.extensionManager.reloadBundledExtensions()
                    val loaded = graph.extensionManager.getLoadedExtensions().filter { it.pkgName in packages }
                    val notLoaded = graph.extensionManager.getNotLoadedExtensions().filter { it.pkgName in packages }
                    val recognized = (loaded.map { it.pkgName } + notLoaded.map { it.pkgName }).toSet()
                    check(recognized.containsAll(packages)) { "部分内置图源无法识别，请重新安装" }
                    val chinese = loaded.flatMap { it.sources }.filterIsInstance<HttpSource>()
                        .filter { it.lang.startsWith("zh", ignoreCase = true) }.distinctBy { it.id }
                    check(chinese.isNotEmpty()) {
                        "内置图源加载失败：${notLoaded.firstOrNull()?.reason ?: "没有中文图源"}"
                    }
                    val ids = chinese.mapTo(HashSet()) { it.id }
                    sourceManager.sources.first { current -> current.map { it.id }.containsAll(ids) }
                    if (notLoaded.isNotEmpty()) onProgress("${chinese.size} 个中文图源已就绪，${notLoaded.size} 个扩展加载失败")
                    chinese
                }
                cachedSources = sources
                sources
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
