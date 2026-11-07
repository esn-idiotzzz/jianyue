package com.joe.jmlite

import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 书架目录规则：拆除书籍夹会连同夹中夹一起拆除，我的收藏内的所有书籍保持已收藏。 */
class ShelfFavoriteScopeTest {
    @AfterEach fun cleanup()=unmockkAll()

    private fun folder(id:String,system:String="",parent:String="",title:String=id)=
        JSONObject().put("id",id).put("system",system).put("parent",parent).put("title",title)

    private fun state(vararg folders:JSONObject)=
        JSONObject().put("folders",JSONArray(folders.toList())).put("entries",JSONObject())

    private fun library(state:JSONObject,hidden:Boolean=false):Library {
        val library=spyk(mockk<Library>(relaxed=true),recordPrivateCalls=true)
        Library::class.java.getDeclaredField("privateShelf").apply{isAccessible=true}.setBoolean(library,hidden)
        every{library["read"]()}returns state
        every{library["write"](any<JSONObject>())}answers{Unit}
        return library
    }

    private fun entry(state:JSONObject,key:String="download:1")=state.getJSONObject("entries").getJSONObject(key)

    private fun folderIds(state:JSONObject):Set<String> {
        val folders=state.getJSONArray("folders")
        return (0 until folders.length()).map{folders.getJSONObject(it).getString("id")}.toSet()
    }

    @Test fun `deleting a nested folder returns its books to the parent folder`(){
        val state=state(folder("root"),folder("child",parent="root"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","child"))
        library(state).deleteFolder("child")
        assertEquals("root",entry(state).getString("folder"))
    }

    @Test fun `dismantling a folder removes every nested folder instead of lifting it`(){
        val state=state(folder("root"),folder("child",parent="root"),folder("grand",parent="child"),folder("keep",parent="root"))
        library(state).deleteFolder("child")
        assertEquals(setOf("root","keep"),folderIds(state))
    }

    @Test fun `books from every nested folder land in the dismantled folder parent`(){
        val state=state(folder("root"),folder("child",parent="root"),folder("grand",parent="child"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","child"))
        state.getJSONObject("entries").put("download:2",JSONObject().put("folder","grand"))
        library(state).deleteFolder("child")
        assertEquals("root",entry(state,"download:1").getString("folder"))
        assertEquals("root",entry(state,"download:2").getString("folder"))
        assertEquals(setOf("root"),folderIds(state))
    }

    @Test fun `deleting a shelf level folder still returns its books to the shelf`(){
        val state=state(folder("root"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","root"))
        library(state).deleteFolder("root")
        assertFalse(entry(state).has("folder"))
    }

    @Test fun `dismantling a shelf folder recursively returns nested books to the shelf`(){
        val state=state(folder("root"),folder("child",parent="root"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","root"))
        state.getJSONObject("entries").put("download:2",JSONObject().put("folder","child"))
        library(state).deleteFolder("root")
        assertFalse(entry(state,"download:1").has("folder"))
        assertFalse(entry(state,"download:2").has("folder"))
        assertTrue(folderIds(state).isEmpty())
    }

    @Test fun `deleting a folder inside favorites keeps its books favorited`(){
        val state=state(folder("fav","favorites"),folder("child",parent="fav"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","child"))
        library(state).deleteFolder("child")
        assertEquals("fav",entry(state).getString("folder"));assertTrue(entry(state).getBoolean("favorite"))
    }

    @Test fun `books from nested folders inside favorites stay favorited`(){
        val state=state(folder("fav","favorites"),folder("child",parent="fav"),folder("grand",parent="child"))
        state.getJSONObject("entries").put("download:1",JSONObject().put("folder","grand"))
        library(state).deleteFolder("child")
        assertEquals("fav",entry(state).getString("folder"));assertTrue(entry(state).getBoolean("favorite"))
    }

    @Test fun `favorites scope covers every nested folder`(){
        val state=state(folder("fav","favorites"),folder("child",parent="fav"),folder("grand",parent="child"),folder("other"))
        assertTrue(Library.inFavorites(state,"fav"));assertTrue(Library.inFavorites(state,"grand"))
        assertTrue(Library.favoriteFolders(state).contains("grand"));assertFalse(Library.inFavorites(state,"other"));assertFalse(Library.inFavorites(state,""))
    }

    @Test fun `the favorites folder is identified by its title as well as its system id`(){
        val state=state(folder("fav",title=Library.FAVORITES),folder("child",parent="fav"))
        assertTrue(Library.inFavorites(state,"child"))
    }

    @Test fun `moving a book into a nested favorites folder marks it favorited`(){
        val state=state(folder("fav","favorites"),folder("child",parent="fav"))
        library(state).move("download:1","child")
        assertEquals("child",entry(state).getString("folder"));assertTrue(entry(state).getBoolean("favorite"))
    }

    @Test fun `moving a book into the favorites folder itself marks it favorited`(){
        val state=state(folder("fav","favorites"))
        library(state).move("download:1","fav")
        assertTrue(entry(state).getBoolean("favorite"))
    }

    @Test fun `moving a book into an ordinary folder does not invent a favorite flag`(){
        val state=state(folder("normal"))
        library(state).move("download:1","normal")
        assertFalse(entry(state).has("favorite"))
    }

    @Test fun `a folder created inside favorites keeps its book favorited`(){
        val state=state(folder("fav","favorites"))
        val id=library(state).moveToNewFolder("download:1","新夹","fav")
        assertEquals(id,entry(state).getString("folder"));assertTrue(entry(state).getBoolean("favorite"))
    }

    @Test fun `deleting a folder writes metadata exactly once`(){
        val state=state(folder("root"),folder("child",parent="root"),folder("grand",parent="child"))
        val lib=library(state)
        lib.deleteFolder("child")
        verify(exactly=1){lib["write"](any<JSONObject>())}
    }
}
