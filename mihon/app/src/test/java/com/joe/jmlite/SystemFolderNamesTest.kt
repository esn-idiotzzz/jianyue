package com.joe.jmlite

import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SystemFolderNamesTest {
    @AfterEach fun cleanup()=unmockkAll()
    private fun folder(system:String,hidden:Boolean=false,title:String="目录")=JSONObject().put("id","folder").put("system",system).put("privacy",hidden).put("title",title)
    private fun library(state:JSONObject):Library {
        val lib=spyk(mockk<Library>(relaxed=true),recordPrivateCalls=true)
        every{lib["read"]()}returns state
        every{lib["write"](any<JSONObject>())}answers{Unit}
        return lib
    }
    @Test fun `both shelves reject system directory renames before writing`(){
        for(hidden in listOf(false,true))for(system in listOf("favorites","online_jm","online_web")){
            val item=folder(system,hidden);val state=JSONObject().put("folders",JSONArray().put(item));val before=state.toString();val lib=library(state)
            assertThrows(java.io.IOException::class.java){lib.renameFolder("folder","新名称")}
            assertEquals(before,state.toString());verify(exactly=0){lib["write"](any<JSONObject>())}
        }
    }
    @Test fun `ordinary folders still rename normally`(){
        val item=folder("");val state=JSONObject().put("folders",JSONArray().put(item));val lib=library(state)
        assertFalse(Library.isProtectedFolder(item));lib.renameFolder("folder","自建目录")
        assertEquals("自建目录",item.getString("title"));verify(exactly=1){lib["write"](any<JSONObject>())}
    }
    @Test fun `previously renamed system directories remain protected by identity`(){
        assertTrue(Library.isProtectedFolder(folder("online_jm",title="以前改过名")))
        assertTrue(Library.isProtectedFolder(folder("online_web",true,"另一个名字")))
    }
    @Test fun `legacy online and favorites directories are protected`(){
        assertTrue(Library.isProtectedFolder(folder("online")))
        assertTrue(Library.isProtectedFolder(folder("",title=Library.FAVORITES)))
    }
}
