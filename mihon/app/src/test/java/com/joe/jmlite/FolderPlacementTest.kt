package com.joe.jmlite

import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FolderPlacementTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun folder(id: String, system: String = "", parent: String = "", title: String = id) =
        JSONObject().put("id", id).put("system", system).put("parent", parent).put("title", title)

    private fun state(vararg folders: JSONObject) = JSONObject().put("folders", JSONArray(folders.toList()))

    private fun library(state: JSONObject): Library {
        val library = spyk(mockk<Library>(relaxed = true), recordPrivateCalls = true)
        every { library["read"]() } returns state
        every { library["write"](any<JSONObject>()) } answers { Unit }
        return library
    }

    @Test fun `downloaded JM books cannot enter the online JM folder`() {
        val state = state(folder("jm", "online_jm"))
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "download:123", "jm") }
    }

    @Test fun `imported archives cannot enter the web online folder`() {
        val state = state(folder("web", "online_web"))
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "import:uuid", "web") }
    }

    @Test fun `local image trees cannot enter legacy online folders`() {
        val state = state(folder("legacy", "online"))
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "tree:content://images", "legacy") }
    }

    @Test fun `children inherit the online classification from their ancestors`() {
        val state = state(folder("web", "online_web"), folder("child", parent = "web"), folder("deep", parent = "child"))
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "download:123", "deep") }
    }

    @Test fun `renaming an online folder cannot bypass the restriction`() {
        val state = state(folder("online", "online_jm", title = "随便改名"))
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "download:123", "online") }
    }

    @Test fun `an ordinary folder with an online looking name remains available to local books`() {
        val state = state(folder("normal", title = "在线JM漫画"))
        assertDoesNotThrow { Library.requirePlacement(state, "download:123", "normal") }
    }

    @Test fun `online manga can still be placed in either online folder`() {
        val state = state(folder("jm", "online_jm"), folder("web", "online_web"))
        assertDoesNotThrow { Library.requirePlacement(state, "-1|123", "jm") }
        assertDoesNotThrow { Library.requirePlacement(state, "99|/book", "web") }
    }

    @Test fun `all local kinds can stay at the root or in normal nested folders`() {
        val state = state(folder("normal"), folder("child", parent = "normal"))
        for (key in listOf("download:123", "import:uuid", "tree:content://images")) {
            assertDoesNotThrow { Library.requirePlacement(state, key, "") }
            assertDoesNotThrow { Library.requirePlacement(state, key, "child") }
        }
    }

    @Test fun `rejecting placement does not mutate any folder or book metadata`() {
        val state = state(folder("jm", "online_jm")).put("entries", JSONObject().put("download:123", JSONObject().put("folder", "")))
        val before = state.toString()
        assertThrows(Library.LocalComicException::class.java) { Library.requirePlacement(state, "download:123", "jm") }
        assertEquals(before, state.toString())
    }

    @Test fun `move rejects a local book before changing its entry or writing the file`() {
        val state = state(folder("jm", "online_jm"))
        val before = state.toString()
        val library = library(state)
        assertThrows(Library.LocalComicException::class.java) { library.move("download:123", "jm") }
        assertEquals(before, state.toString())
        verify(exactly = 0) { library["write"](any<JSONObject>()) }
    }

    @Test fun `creating a folder during classification cannot leave an empty child after rejection`() {
        val state = state(folder("web", "online_web"))
        val before = state.toString()
        val library = library(state)
        assertThrows(Library.LocalComicException::class.java) { library.moveToNewFolder("import:uuid", "新夹", "web") }
        assertEquals(before, state.toString())
        verify(exactly = 0) { library["write"](any<JSONObject>()) }
    }

    @Test fun `merge validates both members before scanning books or creating a folder`() {
        val state = state(folder("jm", "online_jm"))
        val before = state.toString()
        val library = library(state)
        assertThrows(Library.LocalComicException::class.java) { library.mergeBooks("-1|123", "download:456", "jm") }
        assertEquals(before, state.toString())
        verify(exactly = 0) { library.books() }
        verify(exactly = 0) { library["write"](any<JSONObject>()) }
    }

    @Test fun `favoriteOnline cannot be used to reclassify a local key as online`() {
        val state = state(folder("web", "online_web"))
        val before = state.toString()
        val library = library(state)
        assertThrows(Library.LocalComicException::class.java) { library.favoriteOnline(JSONObject().put("key", "download:123"), true) }
        assertEquals(before, state.toString())
        verify(exactly = 0) { library["write"](any<JSONObject>()) }
    }

    @Test fun `normal moves still update their membership with one write`() {
        val state = state(folder("normal"))
        val library = library(state)
        library.move("download:123", "normal")
        assertEquals("normal", state.getJSONObject("entries").getJSONObject("download:123").getString("folder"))
        verify(exactly = 1) { library["write"](any<JSONObject>()) }
    }
}
