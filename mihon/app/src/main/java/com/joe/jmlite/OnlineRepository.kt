package com.joe.jmlite

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.json.JSONObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * Search and reading bridge for the Chinese Mihon sources bundled with the app.
 */
object OnlineRepository {
    internal const val SEARCH_PARALLELISM = 16
    data class SearchSummary(val total: Int, val succeeded: Int, val failed: Int, val failedSourceIds: List<Long> = emptyList())

    data class Item(
        val sourceId: Long,
        val sourceName: String,
        val url: String,
        val title: String,
        val thumbnail: String?,
        val author: String?,
        val artist: String?,
        val description: String?,
    )

    data class Details(
        val manga: Manga,
        val chapters: List<Chapter>,
        val downloaded: Set<Long>,
        val sourceName: String,
        val title: String,
        val thumbnail: String?,
        val author: String?,
        val artist: String?,
        val description: String?,
    )

    suspend fun ensureChineseSources(
        context: Context,
        onProgress: (String) -> Unit,
    ): List<HttpSource> = BundledSources.ensure(context, onProgress)

    suspend fun search(
        context: Context,
        query: String,
        onProgress: (String) -> Unit,
        onItem: (Item) -> Unit,
        sourceId: Long = 0L,
    ): SearchSummary = withContext(Dispatchers.IO) {
        val sources = ensureChineseSources(context, onProgress).filter { sourceId == 0L || it.id == sourceId }
        searchSources(sources, query, onProgress, onItem)
    }

    internal suspend fun searchSources(
        sources: List<HttpSource>,
        query: String,
        onProgress: (String) -> Unit,
        onItem: (Item) -> Unit,
    ): SearchSummary {
        onProgress("正在搜索 ${sources.size} 个中文图源…")
        val semaphore = Semaphore(SEARCH_PARALLELISM)
        val done = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        val failedSources = Collections.synchronizedSet(HashSet<Long>())
        val seen = Collections.synchronizedSet(HashSet<String>())
        coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        try {
                            val page = withTimeout(15_000) {
                                if (query.isBlank()) source.getPopularManga(1) else source.getSearchManga(1, query, source.getFilterList())
                            }
                            page.mangas.forEach { manga ->
                                val key = "${source.id}|${manga.url}"
                                if (manga.url.isNotBlank() && seen.add(key)) {
                                    onItem(
                                        Item(
                                            sourceId = source.id,
                                            sourceName = source.name,
                                            url = manga.url,
                                            title = manga.title.ifBlank { "未命名" },
                                            thumbnail = absoluteUrl(manga.thumbnail_url, source.baseUrl),
                                            author = manga.author,
                                            artist = manga.artist,
                                            description = manga.description,
                                        ),
                                    )
                                }
                            }
                            succeeded.incrementAndGet()
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            failedSources.add(source.id)
                            // A single source timing out must not cancel the entire search.
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            failedSources.add(source.id)
                            // A broken or blocking source must not stop the other sources.
                        } finally {
                            onProgress("正在搜索 ${done.incrementAndGet()}/${sources.size} 个中文图源…")
                        }
                    }
                }
            }.awaitAll()
        }
        return SearchSummary(sources.size, succeeded.get(), done.get() - succeeded.get(), sources.map { it.id }.filter { it in failedSources }.distinct())
    }

    suspend fun loadDetails(
        context: Context,
        sourceId: Long,
        mangaUrl: String,
        fallbackTitle: String,
        fallbackThumbnail: String?,
    ): Details = withContext(Dispatchers.IO) {
        val graph = context.applicationContext.appGraph
        var source = graph.sourceManager.get(sourceId) as? HttpSource
        if (source == null) {
            BundledSources.ensure(context) {}
            source = graph.sourceManager.get(sourceId) as? HttpSource
        }
        val httpSource = source ?: throw IllegalStateException("图源未安装或已失效")
        val smanga = SManga.create().also {
            it.url = mangaUrl
            it.title = fallbackTitle
            it.thumbnail_url = fallbackThumbnail
        }
        val update = withTimeout(45_000) {
            httpSource.getMangaUpdate(smanga, emptyList(), true, true)
        }
        val remote = update.manga
        val title = remote.title.ifBlank { fallbackTitle.ifBlank { "未命名" } }
        val thumbnail = absoluteUrl(remote.thumbnail_url, httpSource.baseUrl) ?: fallbackThumbnail
        val manga = graph.jmMangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = sourceId,
                    url = mangaUrl,
                    title = title,
                    author = remote.author,
                    artist = remote.artist,
                    description = remote.description,
                    genre = remote.getGenres(),
                    status = remote.status.toLong(),
                    thumbnailUrl = thumbnail,
                    initialized = true,
                ),
            ),
        ).single()

        val existing = graph.jmChapterRepository.getChapterByMangaId(manga.id)
        val existingUrls = existing.associateBy { it.url }
        ChapterAvailability.mark(context, sourceId, mangaUrl, update.chapters.isEmpty())
        val remoteChapters = update.chapters.filter { it.url.isNotBlank() }.distinctBy { it.url }
        val added = remoteChapters.mapIndexedNotNull { index, chapter ->
            if (chapter.url.isBlank() || chapter.url in existingUrls) return@mapIndexedNotNull null
            Chapter.create().copy(
                mangaId = manga.id,
                url = chapter.url,
                name = chapter.name.ifBlank { "第${index + 1}话" },
                chapterNumber = chapter.chapter_number.toDouble(),
                dateUpload = Instant.fromEpochMilliseconds(
                    chapter.date_upload.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ),
                sourceOrder = index.toLong(),
                scanlator = chapter.scanlator,
            )
        }.distinctBy { it.url }
        val reordered = remoteChapters.mapIndexedNotNull { index, remoteChapter ->
            val old = existingUrls[remoteChapter.url] ?: return@mapIndexedNotNull null
            if (old.sourceOrder == index.toLong()) return@mapIndexedNotNull null
            ChapterRemoteUpdate(
                id = old.id,
                name = old.name,
                scanlator = old.scanlator,
                chapterNumber = old.chapterNumber,
                dateUpload = old.dateUpload,
                sourceOrder = index.toLong(),
                memo = old.memo,
            )
        }
        if (added.isNotEmpty() || reordered.isNotEmpty()) {
            graph.jmChapterRepository.updateFromRemote(emptyList(), added, reordered)
        }
        val chapters = graph.jmChapterRepository.getChapterByMangaId(manga.id)
            .sortedWith(compareBy<Chapter> { it.sourceOrder }.thenByDescending { it.chapterNumber })
        val downloaded = chapters.mapNotNull { chapter ->
            if (graph.downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    sourceId,
                )
            ) chapter.id else null
        }.toSet()
        Details(
            manga = manga,
            chapters = chapters,
            downloaded = downloaded,
            sourceName = httpSource.name,
            title = title,
            thumbnail = thumbnail,
            author = remote.author,
            artist = remote.artist,
            description = remote.description,
        )
    }

    suspend fun cachedDetails(
        context: Context,
        sourceId: Long,
        mangaUrl: String,
        fallbackTitle: String,
        fallbackThumbnail: String?,
    ): Details? = withContext(Dispatchers.IO) {
        val graph = context.applicationContext.appGraph
        val manga = graph.jmMangaRepository.getMangaByUrlAndSourceId(mangaUrl, sourceId)
            ?: return@withContext null
        val chapters = graph.jmChapterRepository.getChapterByMangaId(manga.id)
            .sortedWith(compareBy<Chapter> { it.sourceOrder }.thenByDescending { it.chapterNumber })
        if (chapters.isEmpty()) return@withContext null
        val sourceName = try {
            (graph.sourceManager.get(sourceId) as? Source)?.name
        } catch (_: Exception) {
            null
        } ?: "在线图源"
        val downloaded = chapters.mapNotNull { chapter ->
            if (graph.downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    sourceId,
                )
            ) chapter.id else null
        }.toSet()
        Details(
            manga = manga,
            chapters = chapters,
            downloaded = downloaded,
            sourceName = sourceName,
            title = manga.title.ifBlank { fallbackTitle },
            thumbnail = manga.thumbnailUrl ?: fallbackThumbnail,
            author = manga.author,
            artist = manga.artist,
            description = manga.description,
        )
    }

    /** Search the bundled 禁漫天堂 source by JM id and open its first chapter directly. */
    suspend fun directJmRead(context: Context, query: String, fromStart: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val sources = ensureChineseSources(context) {}
        val source = sources.firstOrNull {
            it.name.contains("禁漫") || it.name.contains("Jinman", ignoreCase = true) ||
                it.javaClass.name.contains("jinmantiantang", ignoreCase = true)
        } ?: throw IllegalStateException("未找到禁漫天堂图源")
        val page = withTimeout(45_000) {
            source.getSearchManga(1, query, source.getFilterList())
        }
        val manga = page.mangas.firstOrNull() ?: throw IllegalStateException("禁漫天堂未找到编号 $query")
        val details = loadDetails(context, source.id, manga.url, manga.title, manga.thumbnail_url)
        DirectJmHistory.mark(details.manga.id, query)
        val chapter = (if(fromStart) details.chapters.lastOrNull() else details.chapters.firstOrNull { it.lastPageRead > 0 && !it.read }) ?: details.chapters.lastOrNull() ?: throw IllegalStateException("该漫画暂无章节")
        openChapter(context, details.manga.id, chapter.id, fromStart)
        true
    }
    suspend fun openChapter(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        fromStart: Boolean,
    ) = withContext(Dispatchers.IO) {
        val graph = context.applicationContext.appGraph
        val current = graph.jmChapterRepository.getChapterById(chapterId)
        if (fromStart) {
            graph.jmChapterRepository.update(
                ChapterUpdate(chapterId) {
                    read = false
                    lastPageRead = 0
                },
            )
        } else if (current != null && current.read && current.lastPageRead > 0) {
            // Reader starts at lastPageRead only for chapters not marked read.
            graph.jmChapterRepository.update(
                ChapterUpdate(chapterId) {
                    read = false
                    lastPageRead = current.lastPageRead
                },
            )
        }
        val intent = ReaderActivity.newIntent(context, mangaId, chapterId)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun download(context: Context, mangaId: Long, chapterIds: List<Long>): Int =
        withContext(Dispatchers.IO) {
            val graph = context.applicationContext.appGraph
            val manga = graph.jmMangaRepository.getMangaById(mangaId)
            val chapters = chapterIds.mapNotNull { graph.jmChapterRepository.getChapterById(it) }
            if (chapters.isNotEmpty()) graph.downloadManager.downloadChapters(manga, chapters)
            chapters.size
        }

    fun saveHistory(
        context: Context,
        sourceId: Long,
        sourceName: String,
        url: String,
        title: String,
        thumbnail: String?,
        author: String?,
        description: String?,
        chapterCount: Int,
        lastChapterName: String? = null,
        lastChapterUrl: String? = null,
        progress: Int = 0,
    ) {
        try {
            val history = OnlineHistory(context.applicationContext)
            val old = history.find(sourceId.toString(), url)
            val previousName = old?.optString("lastChapterName").orEmpty()
            val previousUrl = old?.optString("lastChapterUrl").orEmpty()
            val record = JSONObject()
                .put("key", OnlineHistory.key(sourceId.toString(), url))
                .put("chapterProgress", old?.optJSONObject("chapterProgress") ?: JSONObject())
                .put("sourceId", sourceId)
                .put("sourceName", sourceName)
                .put("url", url)
                .put("title", old?.optString("customTitle")?.takeIf { it.isNotBlank() } ?: title)
                .put("customTitle",old?.optString("customTitle").orEmpty())
                .put("thumbnail", thumbnail ?: old?.optString("thumbnail").orEmpty())
                .put("author", author ?: old?.optString("author").orEmpty())
                .put("description", description ?: old?.optString("description").orEmpty())
                .put("chapterCount", chapterCount)
                .put("lastChapterName", lastChapterName ?: previousName)
                .put("lastChapterUrl", lastChapterUrl ?: previousUrl)
                .put("progress", if (lastChapterName == null) old?.optInt("progress", 0) ?: progress else progress)
                .put("totalPages", old?.optInt("totalPages", 0) ?: 0)
                .put("read", old?.optBoolean("read", false) ?: false)
            history.save(record)
        } catch (_: Exception) {
        }
    }

    private fun absoluteUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            null
        }
    }
}
