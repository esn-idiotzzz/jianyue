package com.joe.jmlite

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking

/** Called only by the bounded cover workers, including when opening saved online history. */
object CoverSourceAccess {
    @JvmStatic
    fun find(context: Context, sourceId: Long): HttpSource? = runBlocking {
        BundledSources.ensure(context) {}.firstOrNull { it.id == sourceId }
    }
}
