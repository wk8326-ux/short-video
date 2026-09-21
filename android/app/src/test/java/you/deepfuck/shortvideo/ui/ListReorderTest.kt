package you.deepfuck.shortvideo.ui

import org.junit.Assert.assertEquals
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
}
