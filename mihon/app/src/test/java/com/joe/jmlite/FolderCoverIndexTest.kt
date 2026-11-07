package com.joe.jmlite

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FolderCoverIndexTest {
    private fun book(id:String,folder:String="f",thumb:String="")=JSONObject().put("key",id).put("folder",folder).put("thumbnail",thumb)
    private fun folder(id:String,parent:String="",title:String=id)=JSONObject().put("id",id).put("parent",parent).put("title",title)
    @Test fun `folder membership retains book order and does not include root or other folders`(){
        val one=book("one");val two=book("two")
        val index=FolderCoverIndex.from(listOf(one,book("root",""),book("other","g"),two))
        assertEquals(listOf(one,two),index.members("f"));assertEquals(1,index.members("g").size);assertTrue(index.members("").isEmpty())
    }
    @Test fun `visible cover changes invalidate the fingerprint`(){
        val before=FolderCoverIndex.from(listOf(book("one",thumb="old")))
        val after=FolderCoverIndex.from(listOf(book("one",thumb="new")))
        assertNotEquals(before.signature("f"),after.signature("f"))
    }
    @Test fun `unseen fifth cover changes preserve the face while member count changes invalidate it`(){
        val firstFour=(1..4).map{book(it.toString())}
        val one=FolderCoverIndex.from(firstFour+book("five",thumb="old"))
        val two=FolderCoverIndex.from(firstFour+book("other-five",thumb="new"))
        assertEquals(one.signature("f"),two.signature("f"))
        assertNotEquals(one.signature("f"),FolderCoverIndex.from(firstFour).signature("f"))
    }
    @Test fun `cached membership is reused and detached from input list mutation`(){
        val books=mutableListOf(book("one"));val index=FolderCoverIndex.from(books);val group=index.members("f")
        books.clear();assertEquals(1,group.size);assertSame(group,index.members("f"))
        assertThrows(UnsupportedOperationException::class.java){group.add(book("two"))}
    }
    @Test fun `a refreshed snapshot cannot leak membership from a previous shelf`(){
        val public=FolderCoverIndex.from(listOf(book("one","public")))
        val private=FolderCoverIndex.from(listOf(book("two","private")))
        assertTrue(public.members("private").isEmpty());assertTrue(private.members("public").isEmpty());assertEquals("",private.signature("public"))
    }
    @Test fun `nested folders are visible members of their parent preview`(){
        val child=folder("child","root")
        val index=FolderCoverIndex.from(listOf(book("one","child")),listOf(folder("root"),child))
        val members=index.members("root")
        assertEquals(1,members.size);assertTrue(members[0].getBoolean("book_folder"));assertEquals("child",members[0].getString("id"))
        assertEquals(listOf("one"),index.members("child").map{it.getString("key")})
    }
    @Test fun `folders appear before books exactly like the shelf grid`(){
        val index=FolderCoverIndex.from(listOf(book("one","root")),listOf(folder("root"),folder("child","root")))
        val members=index.members("root")
        assertTrue(members[0].getBoolean("book_folder"));assertEquals("one",members[1].getString("key"))
    }
    @Test fun `changes inside a nested folder invalidate the outer preview fingerprint`(){
        val folders=listOf(folder("root"),folder("child","root"))
        val empty=FolderCoverIndex.from(emptyList<JSONObject>(),folders)
        val filled=FolderCoverIndex.from(listOf(book("one","child")),folders)
        assertNotEquals(empty.signature("root"),filled.signature("root"))
        val retitled=FolderCoverIndex.from(listOf(book("one","child",thumb="new")),folders)
        assertNotEquals(filled.signature("root"),retitled.signature("root"))
    }
    @Test fun `root level folders never leak into a preview`(){
        val index=FolderCoverIndex.from(emptyList<JSONObject>(),listOf(folder("top")))
        assertTrue(index.members("").isEmpty());assertTrue(index.members("missing").isEmpty())
    }
}
