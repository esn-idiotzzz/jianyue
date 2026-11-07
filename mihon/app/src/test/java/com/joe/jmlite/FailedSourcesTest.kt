package com.joe.jmlite

import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FailedSourcesTest {
    private fun entry(id:Long,name:String="图源",lang:String="zh",pkg:String="extension.one")=mockk<JSONObject>{
        every{getLong("id")}returns id
        every{getString("name")}returns name
        every{getString("language")}returns lang
        every{getString("packageName")}returns pkg
    }
    private fun source(id:Long,name:String="图源",lang:String="zh")=mockk<HttpSource>{
        every{this@mockk.id}returns id
        every{this@mockk.name}returns name
        every{this@mockk.lang}returns lang
    }

    @Test fun `exact failed IDs keep the repository rows in repository order`() {
        val a=entry(11);val b=entry(22);val c=entry(33)
        assertEquals(listOf(a,c),RepositoryPanel.failedEntries(listOf(a,b,c),setOf(33,11),emptyMap()))
    }

    @Test fun `a runtime ID changed from the index still displays its repository row`() {
        val row=entry(11)
        val loaded=mapOf("extension.one" to listOf(source(99)))
        assertEquals(listOf(row),RepositoryPanel.failedEntries(listOf(row),setOf(99),loaded))
    }

    @Test fun `same named sources in other packages are not listed as failed`() {
        val a=entry(11,pkg="extension.one");val b=entry(22,pkg="extension.two")
        val loaded=mapOf("extension.one" to listOf(source(99)),"extension.two" to listOf(source(88)))
        assertEquals(listOf(a),RepositoryPanel.failedEntries(listOf(a,b),setOf(99),loaded))
    }

    @Test fun `a multilingual extension maps only its failed language`() {
        val simplified=entry(11,lang="zh-Hans");val traditional=entry(22,lang="zh-Hant")
        val loaded=mapOf("extension.one" to listOf(source(77,lang="zh-Hans"),source(88,lang="zh-Hant")))
        assertEquals(listOf(traditional),RepositoryPanel.failedEntries(listOf(simplified,traditional),setOf(88),loaded))
    }

    @Test fun `no failures and ambiguous aliases do not include unrelated rows`() {
        val row=entry(11,name="失效名称")
        val loaded=mapOf("extension.one" to listOf(source(77,name="甲"),source(88,name="乙")))
        assertTrue(RepositoryPanel.failedEntries(listOf(row),emptySet(),loaded).isEmpty())
        assertTrue(RepositoryPanel.failedEntries(listOf(row),setOf(77),loaded).isEmpty())
    }
}
