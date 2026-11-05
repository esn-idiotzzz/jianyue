package com.joe.jmlite

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceBrowserTest {
    private fun source(id:Long=1L,name:String="测试图源",lang:String="zh"):HttpSource=mockk {
        every{this@mockk.id}returns id;every{this@mockk.name}returns name;every{this@mockk.lang}returns lang
        every{baseUrl}returns "https://source.example";every{supportsLatest}returns true
        every{getFilterList()}returns FilterList()
    }
    private fun result(title:String="热门书",next:Boolean=true)=MangasPage(listOf(SManga.create().apply{this.title=title;url="/book/$title"}),next)
    @Test fun exactIdSelectsOnlyRequestedSource(){val a=source(1);val b=source(2);assertSame(b,SourceBrowser.select(listOf(a,b),2,"",""))}
    @Test fun staleIndexMatchesRuntimeNameAndLanguage(){val a=source(11,"漫画","zh-Hans");val b=source(12,"漫画","zh-Hant");assertSame(b,SourceBrowser.select(listOf(a,b),99,"漫画","zh-Hant"))}
    @Test fun renamedSingleSourceCanBeResolvedWithinItsPackage(){val a=source(3,"新名称");assertSame(a,SourceBrowser.select(listOf(a),90,"旧名称","zh"))}
    @Test fun ambiguousEntryDoesNotSearchOtherSources(){assertThrows(IllegalStateException::class.java){SourceBrowser.select(listOf(source(1,"甲"),source(2,"乙")),99,"失效名称","zh")}}
    @Test fun emptyInputLoadsPopularWithoutSearching()=runTest{val s=source();coEvery{s.getPopularManga(1)}returns result();val page=SourceBrowser.Session(s).page("",1);assertEquals("热门书",page.items.single().title);assertTrue(page.hasNext);coVerify(exactly=0){s.getSearchManga(any(),any(),any())}}
    @Test fun popularCanLoadFollowingPages()=runTest{val s=source();coEvery{s.getPopularManga(1)}returns result();coEvery{s.getPopularManga(2)}returns result("第二页",false);val session=SourceBrowser.Session(s);session.page("",1);val page=session.page("",2);assertEquals("第二页",page.items.single().title);assertFalse(page.hasNext)}
    @Test fun unsupportedPopularUsesLatestAndKeepsThatModeForNextPage()=runTest{val s=source();coEvery{s.getPopularManga(1)}throws UnsupportedOperationException();coEvery{s.getLatestUpdates(1)}returns result("更新");coEvery{s.getLatestUpdates(2)}returns result("更新第二页",false);val session=SourceBrowser.Session(s);assertEquals("更新",session.page("",1).items.single().title);assertEquals("更新第二页",session.page("",2).items.single().title);coVerify(exactly=0){s.getPopularManga(2)}}
    @Test fun emptyPopularCanFallBackToLatest()=runTest{val s=source();coEvery{s.getPopularManga(1)}returns MangasPage(emptyList(),false);coEvery{s.getLatestUpdates(1)}returns result("更新");assertEquals("更新",SourceBrowser.Session(s).page("",1).items.single().title)}
    @Test fun typedSearchUsesTheChosenSourceAndDefaultFilters()=runTest{val s=source();val filters=FilterList(object:Filter.Text("语言","中文"){});every{s.getFilterList()}returns filters;coEvery{s.getSearchManga(1,"漫画名",filters)}returns result("搜索书",false);val page=SourceBrowser.Session(s).page("漫画名",1);assertEquals(1L,page.items.single().sourceId);coVerify(exactly=1){s.getSearchManga(1,"漫画名",filters)};coVerify(exactly=0){s.getPopularManga(any())}}
    @Test fun clearingSearchReturnsToPopular()=runTest{val s=source();coEvery{s.getSearchManga(1,"漫画",any())}returns result("搜索");coEvery{s.getPopularManga(1)}returns result("热门");val session=SourceBrowser.Session(s);session.page("漫画",1);assertEquals("热门",session.page("",1).items.single().title)}
    @Test fun searchOnlySourceCanBrowseWithoutTyping()=runTest{val s=source();every{s.supportsLatest}returns false;coEvery{s.getPopularManga(1)}throws UnsupportedOperationException();coEvery{s.getSearchManga(1,"",any())}returns result("目录");assertEquals("目录",SourceBrowser.Session(s).page("",1).items.single().title)}
    @Test fun cancellationIsNotConvertedToFallbackRequest()=runTest{val s=source();coEvery{s.getPopularManga(1)}throws CancellationException("closed");val failure=runCatching{SourceBrowser.Session(s).page("",1)}.exceptionOrNull();assertTrue(failure is CancellationException);coVerify(exactly=0){s.getLatestUpdates(any())}}
}
