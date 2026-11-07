package com.joe.jmlite

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import java.nio.file.Path
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibrarySnapshotTest {
    @TempDir lateinit var files:Path
    @AfterEach fun cleanup()=unmockkAll()

    private fun library(state:JSONObject):Library {
        val context=mockk<Context>()
        val preferences=mockk<SharedPreferences>()
        every{context.filesDir}returns files.toFile()
        every{context.applicationContext}returns context
        every{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}returns preferences
        every{preferences.getString("folders","[]")}returns "[]"
        val library=spyk(mockk<Library>(relaxed=true),recordPrivateCalls=true)
        Library::class.java.getDeclaredField("context").apply{isAccessible=true}.set(library,context)
        every{library["read"]()}returns state
        return library
    }
    private fun book(id:String,status:String="complete")=JSONObject().put("id",id).put("status",status).put("title","JM$id")

    @Test fun `a supplied download snapshot avoids a second Store scan and retains shelf metadata and order`() {
        val state=JSONObject().put("entries",JSONObject().put("download:1",JSONObject().put("title","重命名").put("folder","folder-a")))
            .put("online",JSONArray().put(JSONObject().put("key","99|/web").put("sourceId",99).put("url","/web")))
            .put("bookOrder",JSONArray().put("99|/web").put("download:1"))
        val library=library(state)
        mockkConstructor(Store::class)
        every{anyConstructed<Store>().list()}throws AssertionError("must reuse supplied snapshot")
        val result=library.books(listOf(book("1"),book("2","paused"),book("3")))
        assertEquals(listOf("99|/web","download:1","download:3"),result.map{Library.key(it)})
        assertEquals("重命名",result[1].getString("title"))
        assertEquals("folder-a",result[1].getString("folder"))
        verify(exactly=0){anyConstructed<Store>().list()}
    }
    @Test fun `existing books entry point still reads Store once`() {
        val library=library(JSONObject())
        mockkConstructor(Store::class)
        every{anyConstructed<Store>().list()}returns listOf(book("7"))
        assertEquals("download:7",library.books().single().getString("key"))
        verify(exactly=1){anyConstructed<Store>().list()}
    }
    @Test fun `a book inside a nested favorites folder is reported as favorite`() {
        val state=JSONObject()
            .put("folders",JSONArray().put(JSONObject().put("id","fav").put("title",Library.FAVORITES).put("system","favorites"))
                .put(JSONObject().put("id","child").put("title","子夹").put("parent","fav")))
            .put("entries",JSONObject().put("download:1",JSONObject().put("folder","child")))
        val result=library(state).books(listOf(book("1")))
        assertTrue(result.single().getBoolean("favorite"))
    }
}
