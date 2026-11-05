package com.joe.jmlite

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FeatureLogicTest {
    @ParameterizedTest
    @CsvSource("海贼王,海贼王,0", "海贼王,海贼王 外传,1", "进击巨人,进击的巨人,1", "ONE PIECE,one-piece,0", "鬼灭之刃,鬼灭,1", "龙珠,火影忍者,2", "龙珠,龙珠Z,1")
    fun ranking(query:String,title:String,rank:Int){assertEquals(rank,SearchRanking.rank(query,title))}
    private fun manga()=SManga.create().apply {url="/book";title="漫画"}
    @Test fun emptyConfirmed()=runTest {val source=mockk<HttpSource>();val m=manga();coEvery{source.getMangaUpdate(any(),any(),false,true)} returns SMangaUpdate(m,emptyList());assertEquals(true,ChapterAvailability.confirmedEmpty(source,m))}
    @Test fun chaptersExist()=runTest {val source=mockk<HttpSource>();val m=manga();coEvery{source.getMangaUpdate(any(),any(),false,true)} returns SMangaUpdate(m,listOf(SChapter.create().apply{url="/chapter";name="第一话"}));assertEquals(false,ChapterAvailability.confirmedEmpty(source,m))}
    @Test fun failureIsNotEmpty()=runTest {val source=mockk<HttpSource>();coEvery{source.getMangaUpdate(any(),any(),false,true)} throws java.io.IOException("offline");assertNull(ChapterAvailability.confirmedEmpty(source,manga()))}
    @Test fun timeoutIsNotEmpty()=runTest {val source=mockk<HttpSource>();coEvery{source.getMangaUpdate(any(),any(),false,true)} coAnswers{awaitCancellation()};assertNull(ChapterAvailability.confirmedEmpty(source,manga()))}
    @Test fun browseUsesPopular()=runTest {
        val source=mockk<HttpSource>();every{source.id}returns 1L;every{source.name}returns "测试";every{source.baseUrl}returns "https://example.org"
        coEvery{source.getPopularManga(1)}returns MangasPage(listOf(manga()),false)
        val items=mutableListOf<OnlineRepository.Item>();val result=OnlineRepository.searchSources(listOf(source),"",{},items::add)
        assertEquals(1,result.succeeded);assertEquals(1,items.size);coVerify(exactly=0){source.getSearchManga(any(),any(),any())}
    }
}
