package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoverDiskCacheTest {
    @TempDir lateinit var directory:File

    @Test fun `encoded cover survives recreating cache and memory eviction`() {
        CoverDiskCache(directory,1024).write("1|https://site/cover",byteArrayOf(1,2,3))
        assertArrayEquals(byteArrayOf(1,2,3),CoverDiskCache(directory,1024).read("1|https://site/cover"))
    }
    @Test fun `same url on different sources keeps separate cache entries`() {
        val cache=CoverDiskCache(directory,1024)
        cache.write("1|https://site/cover",byteArrayOf(1));cache.write("2|https://site/cover",byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1),cache.read("1|https://site/cover"))
        assertArrayEquals(byteArrayOf(2),cache.read("2|https://site/cover"))
    }
    @Test fun `url cannot escape cache directory`() {
        val cache=CoverDiskCache(directory,1024)
        cache.write("1|../../cover?secret=token",byteArrayOf(1))
        assertEquals(1,directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.single().name.matches(Regex("[a-f0-9]{64}\\.cover")))
    }
    @Test fun `cache evicts old covers within byte limit and keeps newest`() {
        val cache=CoverDiskCache(directory,5)
        cache.write("old",byteArrayOf(1,2,3))
        directory.listFiles()!!.single().setLastModified(1)
        cache.write("new",byteArrayOf(4,5,6))
        assertNull(cache.read("old"));assertArrayEquals(byteArrayOf(4,5,6),cache.read("new"))
        assertTrue(directory.listFiles()!!.sumOf{it.length()}<=5)
    }
    @Test fun `invalid cached image can be removed and replaced`() {
        val cache=CoverDiskCache(directory,1024)
        cache.write("cover",byteArrayOf(1));cache.remove("cover");assertNull(cache.read("cover"))
        cache.write("cover",byteArrayOf(2));assertArrayEquals(byteArrayOf(2),cache.read("cover"))
    }
    @Test fun `unavailable cache directory does not prevent cover delivery`() {
        val blocked=File(directory,"blocked").apply{writeText("file")}
        val cache=CoverDiskCache(blocked,1024)
        assertDoesNotThrow{cache.write("cover",byteArrayOf(1))};assertNull(cache.read("cover"))
    }
    @Test fun `empty and oversized writes do not displace valid cover`() {
        val cache=CoverDiskCache(directory,3)
        cache.write("cover",byteArrayOf(1));cache.write("cover",byteArrayOf());cache.write("cover",ByteArray(4))
        assertArrayEquals(byteArrayOf(1),cache.read("cover"))
        assertFalse(directory.listFiles()!!.any{it.extension=="part"})
    }
}
