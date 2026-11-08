package com.joe.jmlite

import java.util.concurrent.ConcurrentHashMap

/** Remembers mangas opened through the download page's JM source shortcut so their history
 *  stays in the JM category instead of the repository/web category. */
internal object DirectJmHistory {
    private val ids = ConcurrentHashMap<Long, String>()

    fun mark(mangaId: Long, jmId: String) {
        ids[mangaId] = jmId
    }

    fun id(mangaId: Long): String? = ids[mangaId]
}
