package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShelfOrderTest {
    private val books = listOf("a", "b", "c", "d", "e", "f", "g")

    @Test fun `left edge inserts before target after removing earlier source`() {
        assertEquals(listOf("b", "c", "a", "d", "e", "f", "g"), ShelfOrder.insert(books, "a", "d", false))
    }
    @Test fun `right edge inserts after target after removing earlier source`() {
        assertEquals(listOf("b", "c", "d", "a", "e", "f", "g"), ShelfOrder.insert(books, "a", "d", true))
    }
    @Test fun `later source can be placed on either side of earlier target`() {
        assertEquals(listOf("a", "f", "b", "c", "d", "e", "g"), ShelfOrder.insert(books, "f", "b", false))
        assertEquals(listOf("a", "b", "f", "c", "d", "e", "g"), ShelfOrder.insert(books, "f", "b", true))
    }
    @Test fun `cross row insertion shifts the complete contiguous sequence`() {
        val result = ShelfOrder.insert(books, "g", "b", false)
        assertEquals(listOf("a", "g", "b", "c", "d", "e", "f"), result)
        assertEquals(books.toSet(), result.toSet())
        assertEquals(books.size, result.size)
    }
    @Test fun `same and adjacent positions remain valid no ops`() {
        assertEquals(books, ShelfOrder.insert(books, "b", "b", true))
        assertEquals(books, ShelfOrder.insert(books, "b", "c", false))
        assertEquals(books, ShelfOrder.insert(books, "b", "a", true))
    }
    @Test fun `preview and cancellation do not mutate original order`() {
        val original = books.toMutableList()
        val preview = ShelfOrder.insert(original, "a", "f", true)
        assertNotEquals(original, preview)
        assertEquals(books, original)
    }
    @Test fun `book hover zones distinguish both edges from folder preview`() {
        assertEquals(ShelfOrder.BEFORE, ShelfOrder.zone(10f, 100f, false, false))
        assertEquals(ShelfOrder.MERGE, ShelfOrder.zone(50f, 100f, false, false))
        assertEquals(ShelfOrder.AFTER, ShelfOrder.zone(90f, 100f, false, false))
    }
    @Test fun `folders only sort with folders and never merge`() {
        assertEquals(ShelfOrder.BEFORE, ShelfOrder.zone(30f, 100f, true, true))
        assertEquals(ShelfOrder.AFTER, ShelfOrder.zone(70f, 100f, true, true))
        assertEquals(ShelfOrder.NONE, ShelfOrder.zone(50f, 100f, true, false))
        assertEquals(ShelfOrder.INTO, ShelfOrder.zone(50f, 100f, false, true))
    }
    @Test fun `out of bounds hover and unknown sibling do not create an operation`() {
        assertEquals(ShelfOrder.NONE, ShelfOrder.zone(-1f, 100f, false, false))
        assertEquals(ShelfOrder.NONE, ShelfOrder.zone(101f, 100f, false, false))
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.insert(books, "external", "b", false) }
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.insert(listOf("a", "a"), "a", "a", false) }
    }
    @Test fun `saved ordering ignores deleted entries and appends new books once`() {
        assertEquals(listOf("c", "a", "b", "new"), ShelfOrder.restore(listOf("a", "b", "c", "new"), listOf("c", "deleted", "a", "a")))
    }
    @Test fun `merge plan contains exactly target and source without moving other siblings`() {
        assertEquals(listOf("d", "b"), ShelfOrder.mergeMembers(books, "b", "d"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), books)
    }
    @Test fun `merge rejects same book and books outside current directory before mutation`() {
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.mergeMembers(books, "b", "b") }
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.mergeMembers(books, "external", "b") }
    }
}
