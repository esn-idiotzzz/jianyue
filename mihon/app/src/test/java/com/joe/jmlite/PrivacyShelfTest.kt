package com.joe.jmlite

import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivacyShelfTest {
    @AfterEach fun cleanup()=unmockkAll()
    private fun folder(id:String,system:String="",parent:String="",hidden:Boolean=false)=JSONObject().put("id",id).put("title",id).put("system",system).put("parent",parent).put("privacy",hidden)
    private fun state(vararg folders:JSONObject)=JSONObject().put("folders",JSONArray(folders.toList())).put("entries",JSONObject())
    private fun library(state:JSONObject,hidden:Boolean=false):Library {
        val library=spyk(mockk<Library>(relaxed=true),recordPrivateCalls=true)
        Library::class.java.getDeclaredField("privateShelf").apply{isAccessible=true}.setBoolean(library,hidden)
        every{library["read"]()}returns state
        every{library["write"](any<JSONObject>())}answers{Unit}
        return library
    }
    @Test fun `moving a root book changes membership without deleting its media metadata`(){
        val state=state();val entry=JSONObject().put("title","保留名称");state.getJSONObject("entries").put("download:1",entry)
        library(state).transfer("download:1",false,true)
        assertTrue(entry.getBoolean("privacy"));assertEquals("",entry.getString("folder"));assertEquals("保留名称",entry.getString("title"))
    }
    @Test fun `a favorite enters a separate private favorites directory`(){
        val state=state(folder("public","favorites"));state.getJSONObject("entries").put("download:1",JSONObject().put("folder","public"))
        library(state).transfer("download:1",false,true)
        val entry=state.getJSONObject("entries").getJSONObject("download:1")
        assertNotEquals("public",entry.getString("folder"));assertTrue(entry.getBoolean("favorite"))
        assertFalse(state.getJSONArray("folders").getJSONObject(0).getBoolean("privacy"))
        assertTrue(state.getJSONArray("folders").getJSONObject(1).getBoolean("privacy"))
    }
    @Test fun `system folder reuse is limited to the destination shelf`(){
        val state=state(folder("public","online_jm"),folder("private","online_jm",hidden=true));state.getJSONObject("entries").put("-1|1",JSONObject().put("folder","public"))
        library(state).transfer("-1|1",false,true)
        assertEquals("private",state.getJSONObject("entries").getJSONObject("-1|1").getString("folder"));assertEquals(2,state.getJSONArray("folders").length())
    }
    @Test fun `ordinary folder membership is not copied across shelves for an individual book`(){
        val state=state(folder("normal"));state.getJSONObject("entries").put("download:1",JSONObject().put("folder","normal"))
        library(state).transfer("download:1",false,true)
        assertEquals("",state.getJSONObject("entries").getJSONObject("download:1").getString("folder"))
    }
    @Test fun `transferring a folder includes descendants but excludes unrelated books`(){
        val state=state(folder("root"),folder("child",parent="root"),folder("other"));val entries=state.getJSONObject("entries")
        entries.put("download:1",JSONObject().put("folder","child"));entries.put("download:2",JSONObject().put("folder","other"))
        library(state).transfer("root",true,true)
        assertTrue(entries.getJSONObject("download:1").getBoolean("privacy"));assertFalse(entries.getJSONObject("download:2").optBoolean("privacy"))
        assertTrue(state.getJSONArray("folders").getJSONObject(1).getBoolean("privacy"));assertFalse(state.getJSONArray("folders").getJSONObject(2).getBoolean("privacy"))
    }
    @Test fun `classification cannot cross shelf boundaries`(){
        val state=state(folder("private",hidden=true));val lib=library(state)
        assertThrows(java.io.IOException::class.java){lib.move("download:1","private")}
        verify(exactly=0){lib["write"](any<JSONObject>())}
    }
    @Test fun `returning to the public shelf preserves favorite status`(){
        val state=state();state.getJSONObject("entries").put("download:1",JSONObject().put("privacy",true).put("favorite",true))
        library(state,true).transfer("download:1",false,false)
        assertFalse(state.getJSONObject("entries").getJSONObject("download:1").getBoolean("privacy"));assertTrue(state.getJSONObject("entries").getJSONObject("download:1").getBoolean("favorite"))
    }
    @Test fun `moving a system directory merges its members into the existing destination directory`(){
        val state=state(folder("public","favorites"),folder("private","favorites",hidden=true))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","public"))
        library(state).transfer("public",true,true)
        assertEquals(1,state.getJSONArray("folders").length())
        assertEquals("private",state.getJSONObject("entries").getJSONObject("download:1").getString("folder"))
        assertTrue(state.getJSONObject("entries").getJSONObject("download:1").getBoolean("privacy"))
    }
    @Test fun `public favorite actions do not relocate a private online book`(){
        val state=state(folder("private","online_jm",hidden=true))
        state.getJSONObject("entries").put("-1|1",JSONObject().put("folder","private").put("privacy",true))
        library(state).favoriteOnline(JSONObject().put("key","-1|1"),true)
        val entry=state.getJSONObject("entries").getJSONObject("-1|1")
        assertTrue(entry.getBoolean("privacy"));assertEquals("private",entry.getString("folder"))
        assertTrue(entry.getBoolean("favorite"));assertEquals(1,state.getJSONArray("folders").length())
    }
    @Test fun `sorting private books retains the public shelf saved order`(){
        val state=state().put("bookOrder",JSONArray(listOf("download:public2","download:public1","download:p1","download:p2")))
        state.getJSONObject("entries").put("download:p1",JSONObject().put("privacy",true)).put("download:p2",JSONObject().put("privacy",true))
        val lib=library(state,true)
        every{lib.books()}returns listOf(JSONObject().put("key","download:p1"),JSONObject().put("key","download:p2"))
        lib.reorderBook("download:p2","download:p1",false)
        val order=state.getJSONArray("bookOrder")
        assertEquals(listOf("download:public2","download:public1","download:p2","download:p1"),(0 until order.length()).map{order.getString(it)})
    }
    @Test fun `public classification cannot move a private book into the public root`(){
        val state=state();state.getJSONObject("entries").put("download:1",JSONObject().put("privacy",true))
        val lib=library(state)
        assertThrows(java.io.IOException::class.java){lib.move("download:1","")}
        verify(exactly=0){lib["write"](any<JSONObject>())}
    }
    @Test fun `favorite status is readable without sharing directory membership`(){
        val state=state();state.getJSONObject("entries").put("download:1",JSONObject().put("privacy",true).put("favorite",true))
        assertTrue(library(state).isFavorite(JSONObject().put("key","download:1")))
        assertTrue(state.getJSONObject("entries").getJSONObject("download:1").getBoolean("privacy"))
    }
    @Test fun `public delete actions cannot remove a private book or its media`(){
        val state=state();state.getJSONObject("entries").put("download:1",JSONObject().put("privacy",true))
        val lib=library(state)
        assertThrows(java.io.IOException::class.java){lib.delete("download:1")}
        verify(exactly=0){lib["write"](any<JSONObject>())}
    }
}
