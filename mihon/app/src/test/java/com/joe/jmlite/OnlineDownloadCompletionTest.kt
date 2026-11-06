package com.joe.jmlite

import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OnlineDownloadCompletionTest {
    @Test fun `stored chapter publishes history and shelf before refreshing ui`() {
        val events = mutableListOf<String>()
        OnlineDownloadCompletion.publish(
            record = { events += "record" },
            alreadyOnShelf = { events += "lookup"; false },
            addToShelf = { events += "add" },
            notifyChanged = { events += "refresh" },
            onFailure = { fail("Unexpected error", it) },
        )
        assertEquals(listOf("record", "lookup", "add", "refresh"), events)
    }

    @Test fun `existing book is not readded or moved out of its user folder`() {
        var recorded = false
        var refreshed = false
        OnlineDownloadCompletion.publish(
            record = { recorded = true },
            alreadyOnShelf = { true },
            addToShelf = { fail("Existing shelf entry must retain its folder and metadata") },
            notifyChanged = { refreshed = true },
            onFailure = { fail("Unexpected error", it) },
        )
        assertTrue(recorded)
        assertTrue(refreshed)
    }

    @Test fun `history write failure does not discard completed chapter or skip shelf update`() {
        var added = false
        var refreshed = false
        val failures = mutableListOf<Exception>()
        OnlineDownloadCompletion.publish(
            record = { throw IOException("full history disk") },
            alreadyOnShelf = { false },
            addToShelf = { added = true },
            notifyChanged = { refreshed = true },
            onFailure = { failures += it },
        )
        assertTrue(added)
        assertTrue(refreshed)
        assertEquals(1, failures.size)
    }

    @Test fun `failed shelf lookup does not overwrite a potentially existing folder`() {
        var refreshed = false
        val failures = mutableListOf<Exception>()
        OnlineDownloadCompletion.publish(
            record = {},
            alreadyOnShelf = { throw IOException("cannot read shelf") },
            addToShelf = { fail("Unknown existing state must not be overwritten") },
            notifyChanged = { refreshed = true },
            onFailure = { failures += it },
        )
        assertTrue(refreshed)
        assertEquals(1, failures.size)
    }

    @Test fun `shelf write failure still refreshes the independent completed download record`() {
        var refreshed = false
        val failures = mutableListOf<Exception>()
        OnlineDownloadCompletion.publish(
            record = {},
            alreadyOnShelf = { false },
            addToShelf = { throw IOException("cannot save shelf") },
            notifyChanged = { refreshed = true },
            onFailure = { failures += it },
        )
        assertTrue(refreshed)
        assertEquals(1, failures.size)
    }

    @Test fun `notification failure is reported without changing committed completion status`() {
        val failures = mutableListOf<Exception>()
        OnlineDownloadCompletion.publish(
            record = {},
            alreadyOnShelf = { true },
            addToShelf = { fail("Existing entry") },
            notifyChanged = { throw IllegalStateException("receiver unavailable") },
            onFailure = { failures += it },
        )
        assertEquals(1, failures.size)
    }
}
