@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.internal.platform.PlatformRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.BeforeAll
import java.util.Properties

class BrowserAdBlockTest {
    companion object {
        @BeforeAll @JvmStatic fun initializeAndroidPublicSuffixAsset(){
            val config=Properties().apply{BrowserAdBlockTest::class.java.getResourceAsStream("/com/android/tools/test_config.properties")!!.use{load(it)}}
            val path=config.getProperty("android_merged_assets")
            val directory=listOf(File(path),File("mihon/app",path),File("app",path)).first{File(it,"PublicSuffixDatabase.list").isFile}
            val context=mockk<Context>();val assets=mockk<AssetManager>()
            every{context.assets}returns assets
            every{assets.open(any())}answers{File(directory,firstArg<String>()).inputStream()}
            // The direct JVM runner uses OkHttp's JDK platform, which cannot retain Android Context.
            mockkObject(PlatformRegistry)
            every{PlatformRegistry.applicationContext}returns context
            try{"https://example.com".toHttpUrl().topPrivateDomain()}finally{unmockkObject(PlatformRegistry)}
        }
    }
    private val page="https://comic.example/books"
    @Test fun domainAnchorDoesNotBlockLookalikeOrQueryString(){
        val rules=BrowserAdBlock("||ads.example^")
        assertTrue(rules.blocks("https://cdn.ads.example/a.js",page,"script"))
        assertFalse(rules.blocks("https://ads.example.safe.test/a.js",page,"script"))
        assertFalse(rules.blocks("https://safe.test/?url=ads.example/a.js",page,"script"))
    }
    @Test fun exceptionsTakePrecedence(){
        val rules=BrowserAdBlock("||ads.example^\n@@||ads.example/login.js")
        assertFalse(rules.blocks("https://ads.example/login.js",page,"script"))
        assertTrue(rules.blocks("https://ads.example/banner.js",page,"script"))
    }
    @Test fun typeAndDomainRestrictionsAreRespected(){
        val rules=BrowserAdBlock("/ad*${'$'}script,domain=comic.example|~account.comic.example")
        assertTrue(rules.blocks("https://cdn.test/ad.js",page,"script"))
        assertFalse(rules.blocks("https://cdn.test/ad.png",page,"image"))
        assertFalse(rules.blocks("https://cdn.test/ad.js","https://other.test","script"))
        assertFalse(rules.blocks("https://cdn.test/ad.js","https://account.comic.example","script"))
    }
    @Test fun subdomainsShareTheSameSite(){
        val rules=BrowserAdBlock("/ad.js${'$'}third-party")
        assertFalse(rules.blocks("https://static.comic.example/ad.js",page,"script"))
        assertTrue(rules.blocks("https://external.test/ad.js",page,"script"))
    }
    @Test fun unknownOptionsCannotBroadenABlock(){
        assertFalse(BrowserAdBlock("/login${'$'}redirect=noopjs").blocks("https://site.test/login",page,"script"))
    }
    @Test fun mainFrameLoginIsNeverBlocked(){
        assertFalse(BrowserAdBlock("||comic.example^").blocks(page,page,"document",true))
    }
    @Test fun cosmeticExceptionsAndExcludedDomainsAreRespected(){
        val rules=BrowserAdBlock("##.advert\ncomic.example#@#.advert\n~account.comic.example##.banner\ncomic.example##.sponsor")
        val css=rules.css(page)
        assertFalse(css.contains(".advert"));assertTrue(css.contains(".banner"));assertTrue(css.contains(".sponsor"))
        assertFalse(rules.css("https://account.comic.example").contains(".banner"))
    }
    @Test fun proceduralRulesAreNotInjectedAsCssOrScript(){
        assertEquals("",BrowserAdBlock("example.test#?#div:contains(广告)\n##+js(abort-on-property-read, foo)\n##div:has-text(广告)").css(page))
    }
    @Test fun caseSensitiveAndEndAnchorsAreRespected(){
        val rules=BrowserAdBlock("|https://cdn.test/AD.js|${'$'}match-case")
        assertTrue(rules.blocks("https://cdn.test/AD.js",page,"script"))
        assertFalse(rules.blocks("https://cdn.test/ad.js",page,"script"))
        assertFalse(rules.blocks("https://cdn.test/AD.js?allow=1",page,"script"))
    }
    @Test fun negativeResourceTypesAreRespected(){
        val rules=BrowserAdBlock("/advert${'$'}~image")
        assertFalse(rules.blocks("https://cdn.test/advert.png",page,"image"))
        assertTrue(rules.blocks("https://cdn.test/advert.js",page,"script"))
    }
    @Test fun requestsUseBrowserResourceHints(){
        assertEquals("script",BrowserAdBlock.resourceType("https://cdn.test/dynamic",mapOf("Sec-Fetch-Dest" to "script")))
        assertEquals("xmlhttprequest",BrowserAdBlock.resourceType("https://site.test/api",mapOf("Accept" to "application/json")))
        assertEquals("image",BrowserAdBlock.resourceType("https://site.test/cover.webp",emptyMap()))
    }
    @Test fun bundledListActuallyBlocksAdsAndKeepsNavigation(){
        val file=listOf(File("../app/src/main/assets/browser-adblock.txt"),File("app/src/main/assets/browser-adblock.txt"),File("../../app/src/main/assets/browser-adblock.txt")).first{it.exists()}
        val rules=BrowserAdBlock(file.readText())
        assertTrue(rules.blocks("https://unionadjs.com/banner.js",page,"script"))
        assertTrue(rules.css(page).contains("#ADbox"))
        assertFalse(rules.blocks("https://comic.example/login",page,"document",true))
    }
}
