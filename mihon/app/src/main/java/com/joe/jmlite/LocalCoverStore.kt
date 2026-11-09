package com.joe.jmlite

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Reader "set as cover" bridge for the app's own downloaded shelf.
 *
 * Mihon's reader already updates its local-cover store. JianYue's shelf and details read the
 * Store metadata instead, so copy the chosen page into the book's private directory and update
 * that metadata. Online/comic-search records are deliberately ignored.
 */
object LocalCoverStore {
    private const val STAMP = "local_cover_stamp"

    @JvmStatic
    fun save(context: Context, mangaUrl: String, input: InputStream): Boolean {
        val key = keyFor(mangaUrl) ?: return true
        if (!key.startsWith("download:")) return true
        val id = key.removePrefix("download:")
        if (!id.matches(Regex("[0-9]{1,12}"))) return false
        val app = context.applicationContext
        val directory = File(app.filesDir, "local-covers")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        val target = File(directory, "$id.cover")
        val temporary = File(directory, "$id.cover.part")
        try {
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (target.exists() && !target.delete()) return false
            if (!temporary.renameTo(target)) return false
            val stamp = System.currentTimeMillis()
            Store(app).setCover(id, Uri.fromFile(target).toString(), stamp)
            app.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putLong(STAMP, stamp).apply()
            return true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun keyFor(mangaUrl: String): String? {
        val uri = Uri.parse(mangaUrl)
        if (uri.scheme != "jmlite" || uri.authority != "library") return null
        return uri.lastPathSegment?.let { Uri.decode(it) }
    }
}
