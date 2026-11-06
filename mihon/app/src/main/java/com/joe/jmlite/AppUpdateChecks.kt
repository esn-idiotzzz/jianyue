package com.joe.jmlite

import okhttp3.Headers
import okhttp3.Request

/** Foreground checks revalidate GitHub data without repeatedly downloading unchanged notes. */
internal class AppUpdateChecks {
    private var etag: String? = null
    private var nextCheckAt = 0L
    private var rateLimits = 0

    fun delay(now: Long): Long = (nextCheckAt - now).coerceAtLeast(0)

    fun request(current: String): Request = Request.Builder().url(AppRelease.API_URL)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Jianyue/$current")
        .header("Cache-Control", "no-cache")
        .apply { etag?.let { header("If-None-Match", it) } }
        .build()

    fun completed(now: Long, status: Int? = null, headers: Headers = Headers.headersOf(), epoch: Long = 0) {
        var wait = if (status == 200 || status == 304) 60_000L else 15_000L
        if (status == 403 || status == 429) {
            wait = 60_000L shl rateLimits.coerceAtMost(5)
            rateLimits++
            headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                wait = maxOf(wait, seconds(it))
            }
            if (headers["X-RateLimit-Remaining"] == "0") {
                headers["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                    wait = maxOf(wait, seconds(it) - epoch)
                }
            }
        } else {
            rateLimits = 0
        }
        headers["X-Poll-Interval"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            wait = maxOf(wait, seconds(it))
        }
        if (status == 200) etag = headers["ETag"]
        nextCheckAt = now + wait.coerceAtMost(Long.MAX_VALUE - now)
    }

    private fun seconds(value: Long): Long = value.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
}
