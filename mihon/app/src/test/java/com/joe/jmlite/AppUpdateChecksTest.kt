package com.joe.jmlite

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppUpdateChecksTest {
    @Test fun `a known update survives another opening receiving not modified`() {
        val checks=AppUpdateChecks();val release=AppRelease("3.7.0","更新","${AppRelease.PROJECT_URL}/releases/tag/v3.7.0")
        checks.remember(AppUpdateChecks.Endpoint.MANIFEST,release)
        checks.completed(0,304)
        assertSame(release,checks.known(AppUpdateChecks.Endpoint.MANIFEST))
    }
    @Test fun `update candidates remain isolated between metadata and api`() {
        val checks=AppUpdateChecks();val release=AppRelease("3.7.0","更新","${AppRelease.PROJECT_URL}/releases/tag/v3.7.0")
        checks.remember(AppUpdateChecks.Endpoint.API,release)
        assertNull(checks.known(AppUpdateChecks.Endpoint.MANIFEST));assertSame(release,checks.known(AppUpdateChecks.Endpoint.API))
    }
    @Test fun `first check uses the official endpoint and revalidates caches`() {
        val checks = AppUpdateChecks()
        val request = checks.request("3.6.0")
        assertEquals(0L, checks.delay(1_000))
        assertEquals(AppRelease.MANIFEST_URL, request.url.toString())
        assertEquals("no-cache", request.header("Cache-Control"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("If-None-Match"))
    }

    @Test fun `successful checks are limited to one per minute and reuse the etag`() {
        val checks = AppUpdateChecks()
        checks.completed(1_000, 200, Headers.headersOf("ETag", "\"release-one\""))
        assertEquals(60_000L, checks.delay(1_000))
        assertEquals(1L, checks.delay(60_999))
        assertEquals(0L, checks.delay(61_000))
        assertEquals("\"release-one\"", checks.request("3.6.0").header("If-None-Match"))
    }

    @Test fun `network failures retry after fifteen seconds without forgetting validators`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200, Headers.headersOf("ETag", "\"release-one\""))
        checks.completed(70_000)
        assertEquals(15_000L, checks.delay(70_000))
        assertEquals(0L, checks.delay(85_000))
        assertEquals("\"release-one\"", checks.request("3.6.0").header("If-None-Match"))
    }

    @Test fun `not modified responses keep the validator and next foreground check`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200, Headers.headersOf("ETag", "\"release-one\""))
        checks.completed(60_000, 304)
        assertEquals(60_000L, checks.delay(60_000))
        assertEquals("\"release-one\"", checks.request("3.6.0").header("If-None-Match"))
    }

    @Test fun `rate limits honor retry after instead of using the short failure delay`() {
        val checks = AppUpdateChecks()
        checks.completed(1_000, 429, Headers.headersOf("Retry-After", "120"))
        assertEquals(120_000L, checks.delay(1_000))
    }

    @Test fun `exhausted github quota waits for its reset time`() {
        val checks = AppUpdateChecks()
        checks.completed(1_000, 403, Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1900"), epoch = 1_000_000)
        assertEquals(900_000L, checks.delay(1_000))
    }

    @Test fun `repeated secondary limits back off and successful checks reset the backoff`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 403)
        assertEquals(60_000L, checks.delay(0))
        checks.completed(60_000, 429)
        assertEquals(120_000L, checks.delay(60_000))
        checks.completed(180_000, 304)
        checks.completed(240_000, 403)
        assertEquals(60_000L, checks.delay(240_000))
    }

    @Test fun `server poll intervals are respected`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200, Headers.headersOf("X-Poll-Interval", "120"))
        assertEquals(120_000L, checks.delay(0))
    }

    @Test fun `a new response replaces rather than retaining an obsolete validator`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200, Headers.headersOf("ETag", "\"release-one\""))
        checks.completed(60_000, 200)
        assertNull(checks.request("3.6.0").header("If-None-Match"))
    }

    @Test fun `manifest and api validators cannot be sent to each other`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200, Headers.headersOf("ETag", "\"manifest\""))
        checks.completed(0, 200, Headers.headersOf("ETag", "\"api\""), endpoint = AppUpdateChecks.Endpoint.API)
        assertEquals("\"manifest\"", checks.request("3.6.0").header("If-None-Match"))
        val api = checks.request("3.6.0", AppUpdateChecks.Endpoint.API)
        assertEquals(AppRelease.API_URL, api.url.toString())
        assertEquals("application/vnd.github+json", api.header("Accept"))
        assertEquals("\"api\"", api.header("If-None-Match"))
    }

    @Test fun `api quota exhaustion does not prevent the next manifest check`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 200)
        checks.completed(0, 403, Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "4600"), epoch = 1_000_000, endpoint = AppUpdateChecks.Endpoint.API)
        assertEquals(0L, checks.delay(60_000))
        assertEquals(3_540_000L, checks.delay(60_000, AppUpdateChecks.Endpoint.API))
    }

    @Test fun `a failed manifest can retry while the api is still throttled`() {
        val checks = AppUpdateChecks()
        checks.completed(0, 429, Headers.headersOf("Retry-After", "120"), endpoint = AppUpdateChecks.Endpoint.API)
        checks.completed(0)
        assertEquals(0L, checks.delay(15_000))
        assertEquals(105_000L, checks.delay(15_000, AppUpdateChecks.Endpoint.API))
    }
}
