package com.joe.jmlite

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpdateReleaseTest {
    private fun payload(
        tag: String = "v3.4.0",
        notes: JsonElement = JsonPrimitive("新增功能\n修复问题"),
        url: String = "${AppRelease.PROJECT_URL}/releases/tag/$tag",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "简阅-3.4.0.apk")
                put("state", "uploaded")
                put("browser_download_url", "${AppRelease.PROJECT_URL}/releases/download/$tag/Jianyue.apk")
            })
        },
    ) = buildJsonObject {
        put("tag_name", tag)
        put("html_url", url)
        put("draft", draft)
        put("prerelease", prerelease)
        put("body", notes)
        put("assets", assets)
    }.toString()

    @Test fun `compares each numeric component instead of strings`() {
        assertTrue(AppRelease.isNewer("3.10.0", "3.9.9"))
        assertTrue(AppRelease.isNewer("4.0.0", "3.99.99"))
        assertTrue(AppRelease.isNewer("3.3.1", "3.3.0"))
        assertFalse(AppRelease.isNewer("3.2.99", "3.3.0"))
    }

    @Test fun `equal versions do not trigger updates`() {
        assertFalse(AppRelease.isNewer("3.3.0", "3.3.0"))
        assertFalse(AppRelease.isNewer("v3.3", "3.3.0"))
        assertFalse(AppRelease.isNewer("V3.3.0.0", "3.3.0"))
    }

    @Test fun `accepts v prefix and additional numeric component`() {
        assertTrue(AppRelease.isNewer("v3.4.0", "3.3.0"))
        assertTrue(AppRelease.isNewer("V3.3.0.1", "v3.3.0"))
    }

    @Test fun `ignores malformed and prerelease version tags`() {
        for (tag in listOf("", "latest", "3.4.0-beta", " 3.4.0", "3.4.0 ", "3..4", "-3.4", "3", "3.4.0+5")) {
            assertFalse(AppRelease.isNewer(tag, "3.3.0"), tag)
        }
        assertFalse(AppRelease.isNewer("3.4.0", "bad-version"))
    }

    @Test fun `large numeric components cannot overflow`() {
        assertTrue(AppRelease.isNewer("999999999999999999999999.0", "3.3.0"))
        assertFalse(AppRelease.isNewer("3.3.0", "999999999999999999999999.0"))
    }

    @Test fun `reads newer release version notes and canonical download page`() {
        val release = AppRelease.parse(200, payload(), "3.3.0")!!
        assertEquals("3.4.0", release.version)
        assertEquals("新增功能\n修复问题", release.notes)
        assertEquals("${AppRelease.PROJECT_URL}/releases/tag/v3.4.0", release.url)
    }

    @Test fun `empty and null notes have readable fallback`() {
        for (notes in listOf(JsonPrimitive("  "), JsonNull)) {
            assertEquals("此版本未提供更新说明。", AppRelease.parse(200, payload(notes = notes), "3.3.0")?.notes)
        }
    }

    @Test fun `ignores equal and older releases`() {
        assertNull(AppRelease.parse(200, payload(tag = "v3.3.0"), "3.3.0"))
        assertNull(AppRelease.parse(200, payload(tag = "v3.2.0"), "3.3.0"))
    }

    @Test fun `never offers drafts or prereleases`() {
        assertNull(AppRelease.parse(200, payload(draft = true), "3.3.0"))
        assertNull(AppRelease.parse(200, payload(prerelease = true), "3.3.0"))
        assertNull(AppRelease.parse(200, payload(tag = "v3.4.0-beta"), "3.3.0"))
    }

    @Test fun `empty repository rate limits and server errors are silent`() {
        for (status in listOf(204, 301, 403, 404, 429, 500)) {
            assertNull(AppRelease.parse(status, payload(), "3.3.0"))
        }
    }

    @Test fun `malformed responses are ignored`() {
        for (body in listOf("", "not json", "[]", "null", "{}", "{\"tag_name\":{}}", "{\"draft\":[]}")) {
            assertNull(AppRelease.parse(200, body, "3.3.0"))
        }
    }

    @Test fun `only opens the matching release on this github project`() {
        for (url in listOf(
            "http://github.com/${AppRelease.PROJECT}/releases/tag/v3.4.0",
            "https://github.com.evil.invalid/${AppRelease.PROJECT}/releases/tag/v3.4.0",
            "https://github.com/other/project/releases/tag/v3.4.0",
            "https://github.com:444/${AppRelease.PROJECT}/releases/tag/v3.4.0",
            "https://user@github.com/${AppRelease.PROJECT}/releases/tag/v3.4.0",
            "${AppRelease.PROJECT_URL}/releases/tag/v3.4.0?next=elsewhere",
            "${AppRelease.PROJECT_URL}/releases/tag/v3.4.0#fragment",
            "${AppRelease.PROJECT_URL}/releases/tag/v3.5.0",
            "javascript:alert(1)",
        )) assertNull(AppRelease.parse(200, payload(url = url), "3.3.0"), url)
    }

    @Test fun `release needs an uploaded apk in the same release`() {
        assertNull(AppRelease.parse(200, payload(assets = JsonArray(emptyList())), "3.3.0"))
        for ((name, state, download) in listOf(
            Triple("source.zip", "uploaded", "${AppRelease.PROJECT_URL}/releases/download/v3.4.0/source.zip"),
            Triple("app.apk", "starter", "${AppRelease.PROJECT_URL}/releases/download/v3.4.0/app.apk"),
            Triple("app.apk", "uploaded", "https://elsewhere.invalid/app.apk"),
        )) {
            val asset = JsonObject(mapOf("name" to JsonPrimitive(name), "state" to JsonPrimitive(state), "browser_download_url" to JsonPrimitive(download)))
            assertNull(AppRelease.parse(200, payload(assets = JsonArray(listOf(asset))), "3.3.0"))
        }
    }
}
