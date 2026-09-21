package you.deepfuck.shortvideo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListReorderTest {
    @Test
    fun `a drag shorter than one row stays in place`() {
        assertEquals(3, dragTargetIndex(index = 3, dragOffsetPx = 40f, stridePx = 120f, count = 8))
    }

    @Test
    fun `a drag past a row lands in the next slot`() {
        assertEquals(4, dragTargetIndex(index = 3, dragOffsetPx = 130f, stridePx = 120f, count = 8))
        assertEquals(2, dragTargetIndex(index = 3, dragOffsetPx = -130f, stridePx = 120f, count = 8))
    }

    @Test
    fun `a drag cannot leave the list`() {
        assertEquals(0, dragTargetIndex(index = 1, dragOffsetPx = -900f, stridePx = 120f, count = 5))
        assertEquals(4, dragTargetIndex(index = 1, dragOffsetPx = 900f, stridePx = 120f, count = 5))
    }

    @Test
    fun `an unmeasured row leaves the card where it is`() {
        assertEquals(2, dragTargetIndex(index = 2, dragOffsetPx = 400f, stridePx = 0f, count = 5))
        assertEquals(0, dragTargetIndex(index = 0, dragOffsetPx = 0f, stridePx = 120f, count = 0))
    }

    @Test
    fun `moving an entry keeps every other entry in order`() {
        val items = listOf("a", "b", "c", "d")

        assertEquals(listOf("b", "c", "a", "d"), moveItem(items, from = 0, to = 2))
        assertEquals(listOf("d", "a", "b", "c"), moveItem(items, from = 3, to = 0))
        assertEquals(items, moveItem(items, from = 1, to = 1))
        assertEquals(items, moveItem(items, from = 0, to = 9))
    }

    @Test
    fun `a drag stays inside the section the card started in`() {
        // Rows 0-1 are the feed section, rows 2-4 are movies. The server stores
        // one order per section, so the first card may only reach slot 1.
        assertEquals(1, dragTargetIndex(index = 0, dragOffsetPx = 900f, stridePx = 100f, count = 5, minIndex = 0, maxIndex = 1))
        assertEquals(0, dragTargetIndex(index = 1, dragOffsetPx = -900f, stridePx = 100f, count = 5, minIndex = 0, maxIndex = 1))
        // The last section behaves the same way at its own top edge.
        assertEquals(2, dragTargetIndex(index = 4, dragOffsetPx = -900f, stridePx = 100f, count = 5, minIndex = 2, maxIndex = 4))
    }

    @Test
    fun `an index outside the section is pulled back inside it`() {
        assertEquals(2, dragTargetIndex(index = 0, dragOffsetPx = 0f, stridePx = 100f, count = 5, minIndex = 2, maxIndex = 4))
        assertEquals(4, dragTargetIndex(index = 9, dragOffsetPx = 0f, stridePx = 100f, count = 5, minIndex = 2, maxIndex = 4))
    }

    @Test
    fun `sectionRange covers exactly the run of matching entries`() {
        val rows = listOf("feed", "feed", "movie", "movie", "movie", "asmr")

        assertEquals(0..1, sectionRange(rows, 1) { it })
        assertEquals(2..4, sectionRange(rows, 3) { it })
        assertEquals(5..5, sectionRange(rows, 5) { it })
        assertNull(sectionRange(rows, 9) { it })
    }

    @Test
    fun `sectionRange groups by the value the caller projects`() {
        val rows = listOf(1 to "feed", 2 to "feed", 3 to "movie")

        assertEquals(0..1, sectionRange(rows, 0) { it.second })
        assertEquals(2..2, sectionRange(rows, 2) { it.second })
    }
}
