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
    @Test fun `lifting a tile removes it from the flow without changing the saved order`() {
        assertEquals(listOf("b", "c", "d", "e", "f", "g"), ShelfOrder.without(books, "a"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), ShelfOrder.without(books, "g"))
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.without(books, "external") }
    }
    @Test fun `landing reinserts the lifted tile at the gap so the original slot fills`() {
        assertEquals(listOf("b", "c", "a", "d", "e", "f", "g"), ShelfOrder.landing(books, "a", 2))
        assertEquals(listOf("b", "c", "d", "e", "f", "g", "a"), ShelfOrder.landing(books, "a", 6))
        assertEquals(listOf("g", "a", "b", "c", "d", "e", "f"), ShelfOrder.landing(books, "g", 0))
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.landing(books, "a", 7) }
    }
    @Test fun `adapter boundaries after the source shift by one when compacted`() {
        assertEquals(0, ShelfOrder.compactIndex(books, "a", 0))
        assertEquals(2, ShelfOrder.compactIndex(books, "a", 3))
        assertEquals(2, ShelfOrder.compactIndex(books, "d", 2))
        assertThrows(IllegalArgumentException::class.java) { ShelfOrder.compactIndex(books, "a", 8) }
    }
    @Test fun `books can only land after every folder`() {
        assertEquals(3, ShelfOrder.clampGap(0, 6, 3, false))
        assertEquals(6, ShelfOrder.clampGap(9, 6, 3, false))
        assertEquals(2, ShelfOrder.clampGap(9, 6, 2, true))
        assertEquals(0, ShelfOrder.clampGap(-2, 6, 2, true))
    }
    @Test fun `dropping below the last book anchors the insert after the last book`() {
        val compact = ShelfOrder.without(books, "a")
        assertEquals(5, ShelfOrder.dropTargetIndex(compact, 6, false, 0))
        assertTrue(ShelfOrder.dropAfter(6, 6, 0, false))
        assertEquals(2, ShelfOrder.dropTargetIndex(compact, 2, false, 0))
        assertFalse(ShelfOrder.dropAfter(2, 6, 0, false))
    }
    @Test fun `folders append after the last folder instead of before the first book`() {
        val order = listOf("f1", "f2", "b1", "b2")
        val compact = ShelfOrder.without(order, "f1")
        assertEquals(0, ShelfOrder.dropTargetIndex(compact, 1, true, 1))
        assertTrue(ShelfOrder.dropAfter(1, 3, 1, true))
        assertEquals(0, ShelfOrder.dropTargetIndex(compact, 0, true, 1))
        assertFalse(ShelfOrder.dropAfter(0, 3, 1, true))
        assertEquals(-1, ShelfOrder.dropTargetIndex(listOf("b1"), 0, true, 0))
    }
    @Test fun `a book cannot borrow a folder as its landing anchor`() {
        val compact = ShelfOrder.without(listOf("f1", "b1"), "b1")
        assertEquals(-1, ShelfOrder.dropTargetIndex(compact, 1, false, 1))
    }
}
