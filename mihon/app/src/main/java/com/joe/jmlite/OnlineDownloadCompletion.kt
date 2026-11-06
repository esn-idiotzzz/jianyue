package com.joe.jmlite

import android.content.Context
import eu.kanade.tachiyomi.data.download.model.Download
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/** Called only after a chapter's final files and download cache have been committed. */
internal object OnlineDownloadCompletion {
    fun onStoredChapter(context: Context, download: Download) {
        try {
            val manga = download.manga
            val record = JSONObject()
                .put("key", OnlineHistory.key(manga.source.toString(), manga.url))
                .put("sourceId", manga.source)
                .put("url", manga.url)
                .put("title", manga.title)
                .put("thumbnail", manga.thumbnailUrl.orEmpty())
                .put("sourceName", download.source.name)
                .put("author", manga.author.orEmpty())
                .put("description", manga.description.orEmpty())
                .put("type", "online")
                .put("kind", "web")
                .put("chapterId", download.chapter.id)
                .put("chapterName", download.chapter.name)
                .put("chapterUrl", download.chapter.url)
            val library = Library(context)
            publish(
                record = { DownloadHistory(context).recordOnline(record) },
                alreadyOnShelf = { library.hasOnline(record.getString("key")) },
                // Recheck under Library's lock: another chapter or the user may have added it meanwhile.
                addToShelf = { library.ensureOnline(record) },
                notifyChanged = { DownloadHistory.notifyChanged(context, record.getString("key"), "complete", 0, 0) },
                onFailure = { logcat(LogPriority.WARN, it) },
            )
        } catch (error: Exception) {
            // History or shelf failures cannot turn already committed chapter files into an error.
            logcat(LogPriority.WARN, error)
        }
    }

    internal fun publish(
        record: () -> Unit,
        alreadyOnShelf: () -> Boolean,
        addToShelf: () -> Unit,
        notifyChanged: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        try { record() } catch (error: Exception) { onFailure(error) }
        try { if (!alreadyOnShelf()) addToShelf() } catch (error: Exception) { onFailure(error) }
        try { notifyChanged() } catch (error: Exception) { onFailure(error) }
    }
}
