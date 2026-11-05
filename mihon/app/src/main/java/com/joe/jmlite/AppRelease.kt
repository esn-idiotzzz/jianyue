package com.joe.jmlite

import java.math.BigInteger
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class AppRelease(val version: String, val notes: String, val url: String) {
    companion object {
        const val PROJECT = "esn-idiotzzz/jianyue"
        const val PROJECT_URL = "https://github.com/$PROJECT"
        const val API_URL = "https://api.github.com/repos/$PROJECT/releases/latest"
        private val versionPattern = Regex("[vV]?[0-9]+(?:\\.[0-9]+){1,3}")

        fun isNewer(candidate: String, current: String): Boolean {
            if (!versionPattern.matches(candidate) || !versionPattern.matches(current)) return false
            val newer = candidate.removePrefix("v").removePrefix("V").split('.').map(::BigInteger)
            val installed = current.removePrefix("v").removePrefix("V").split('.').map(::BigInteger)
            for (index in 0 until maxOf(newer.size, installed.size)) {
                val comparison = (newer.getOrNull(index) ?: BigInteger.ZERO)
                    .compareTo(installed.getOrNull(index) ?: BigInteger.ZERO)
                if (comparison != 0) return comparison > 0
            }
            return false
        }

        fun parse(status: Int, body: String, current: String): AppRelease? {
            if (status != 200) return null
            return try {
                val release = Json.parseToJsonElement(body) as? JsonObject ?: return null
                if (release["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                    release["prerelease"]?.jsonPrimitive?.booleanOrNull != false
                ) return null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!isNewer(tag, current)) return null
                val link = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
                val uri = URI(link)
                if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
                    uri.userInfo != null || uri.query != null || uri.fragment != null ||
                    uri.path != "/$PROJECT/releases/tag/$tag"
                ) return null
                val assets = release["assets"] as? JsonArray ?: return null
                if (assets.none { asset ->
                    val item = asset as? JsonObject
                    val name = item?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val download = item?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty()
                    name.endsWith(".apk", ignoreCase = true) &&
                        download.startsWith("$PROJECT_URL/releases/download/$tag/") &&
                        item?.get("state")?.jsonPrimitive?.contentOrNull == "uploaded"
                }) return null
                val notes = release["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                AppRelease(tag.removePrefix("v").removePrefix("V"), notes.ifEmpty { "此版本未提供更新说明。" }, link)
            } catch (_: Exception) {
                null
            }
        }
    }
}
