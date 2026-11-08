package com.joe.jmlite

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/** Opens JM Lite content through Mihon's ReaderActivity and its real viewers. */
class MihonEntryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ui.bg)
            addView(ProgressBar(this@MihonEntryActivity))
            addView(TextView(this@MihonEntryActivity).apply {
                text = "正在加载callio~"
                textSize = 14f
                setTextColor(ui.muted)
                setPadding(0, ui.dp(20f), 0, 0)
            })
        }
        setContentView(box)
        scope.launch {
            try {
                val directJmId = intent.getStringExtra("source_jm_id")
                if (directJmId != null) {
                    withContext(Dispatchers.IO) { OnlineRepository.directJmRead(this@MihonEntryActivity, directJmId, intent.getBooleanExtra("start_over", true)) }
                    if (!isFinishing) finish()
                    return@launch
                }
                val target = withContext(Dispatchers.IO) { prepare(intent) }
                if (!isFinishing) {
                    startActivity(target)
                    finish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isFinishing) {
                    Toast.makeText(this@MihonEntryActivity, error.message ?: "无法打开阅读器", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private suspend fun prepare(request: Intent): Intent {
        val online = request.getStringExtra("online_id")
        val library = request.getStringExtra("library_key")
        val id = request.getStringExtra("id")
        val tree = request.getStringExtra("tree")
        val startOver = request.getBooleanExtra("start_over", false)
        val canonicalKey = library ?: if (id != null) "download:${JmApi.parseId(id)}" else if (tree != null) "tree:$tree" else null
        val descriptor = when {
            online != null -> "jmlite://online/${Uri.encode(JmApi.parseId(online))}"
            canonicalKey != null -> "jmlite://library/${Uri.encode(canonicalKey)}"
            else -> error("没有可读取的书籍")
        }
        val title = request.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: "JM 简阅"
        val graph = applicationContext.appGraph
        val manga = graph.jmMangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = LocalSource.ID, url = descriptor, title = title, initialized = true)),
        ).single()
        val repository = graph.jmChapterRepository
        val chapterUrl = "$descriptor/pages"
        var chapter = repository.getChapterByUrlAndMangaId(chapterUrl, manga.id)
        if (chapter == null) {
            val legacyKey = when {
                canonicalKey?.startsWith("download:") == true -> "book_${canonicalKey.removePrefix("download:")}"
                canonicalKey?.startsWith("tree:") == true -> "folder_${canonicalKey.removePrefix("tree:")}"
                canonicalKey != null -> "library_$canonicalKey"
                tree != null -> "folder_$tree"
                else -> "book_$id"
            }
            val savedPage = if (online != null || startOver) 0 else getSharedPreferences("reading", MODE_PRIVATE).getInt(legacyKey, 0)
            chapter = repository.updateFromRemote(
                removedIds = emptyList(),
                added = listOf(Chapter.create().copy(mangaId = manga.id, url = chapterUrl, name = "全书", chapterNumber = 1.0, lastPageRead = savedPage)),
                updated = emptyList(),
            ).firstOrNull() ?: repository.getChapterByUrlAndMangaId(chapterUrl, manga.id) ?: error("无法创建阅读记录")
        } else if (startOver) {
            repository.update(ChapterUpdate(chapter.id) { read = false; lastPageRead = 0 })
        } else if (chapter.read && chapter.lastPageRead > 0) {
            // Mihon starts at lastPageRead only for unread chapters. A finished book must
            // first be marked unread so "上次位置阅读" can still restore the saved page.
            repository.update(ChapterUpdate(chapter.id) {
                read = false
                lastPageRead = chapter.lastPageRead
            })
        }
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (!settings.getBoolean("mihon_reader_initialized", false)) {
            graph.readerPreferences.defaultReadingMode.set(ReadingMode.WEBTOON.flagValue)
            settings.edit().putBoolean("mihon_reader_initialized", true).apply()
        }
        return eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(this, manga.id, chapter.id)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
