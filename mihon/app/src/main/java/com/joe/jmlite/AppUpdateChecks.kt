package com.joe.jmlite

import okhttp3.Headers
import okhttp3.Request

/** Foreground checks revalidate GitHub data without repeatedly downloading unchanged notes. */
internal class AppUpdateChecks {
    enum class Endpoint(val url: String) { MANIFEST(AppRelease.MANIFEST_URL), API(AppRelease.API_URL) }
    private class State {
        var etag: String? = null
        var release:AppRelease?=null
        var nextCheckAt = 0L
        var rateLimits = 0
    }
    private val manifest = State()
    private val api = State()
    private fun state(endpoint: Endpoint) = if (endpoint == Endpoint.MANIFEST) manifest else api

    fun remember(endpoint:Endpoint,release:AppRelease?){state(endpoint).release=release}
    fun known(endpoint:Endpoint):AppRelease?=state(endpoint).release

    fun delay(now: Long, endpoint: Endpoint = Endpoint.MANIFEST): Long = (state(endpoint).nextCheckAt - now).coerceAtLeast(0)

    fun request(current: String, endpoint: Endpoint = Endpoint.MANIFEST): Request = Request.Builder().url(endpoint.url)
        .header("Accept", if (endpoint == Endpoint.API) "application/vnd.github+json" else "application/json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Jianyue/$current")
        .header("Cache-Control", "no-cache")
        .apply { state(endpoint).etag?.let { header("If-None-Match", it) } }
        .build()

    fun completed(now: Long, status: Int? = null, headers: Headers = Headers.headersOf(), epoch: Long = 0, endpoint: Endpoint = Endpoint.MANIFEST) {
        val state = state(endpoint)
        var wait = if (status == 200 || status == 304) 60_000L else 15_000L
        if (status == 403 || status == 429) {
            wait = 60_000L shl state.rateLimits.coerceAtMost(5)
            state.rateLimits++
            headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                wait = maxOf(wait, seconds(it))
            }
            if (headers["X-RateLimit-Remaining"] == "0") {
                headers["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                    wait = maxOf(wait, seconds(it) - epoch)
                }
            }
        } else {
            state.rateLimits = 0
        }
        headers["X-Poll-Interval"]?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            wait = maxOf(wait, seconds(it))
        }
        if (status == 200) state.etag = headers["ETag"]
        state.nextCheckAt = now + wait.coerceAtMost(Long.MAX_VALUE - now)
    }

    private fun seconds(value: Long): Long = value.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
}
