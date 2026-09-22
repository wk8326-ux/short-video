package you.deepfuck.shortvideo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListReorderTest {
    @Test
    fun `a card sitting on its own slot stays there`() {
        assertEquals(3, reorderSlot(draggedTopPx = 3 * 120f, rowHeightPx = 120f, count = 8))
        assertEquals(3, reorderSlot(draggedTopPx = 3 * 120f + 40f, rowHeightPx = 120f, count = 8))
    }

    @Test
    fun `a card dragged past the halfway mark takes the next slot`() {
        assertEquals(4, reorderSlot(draggedTopPx = 3 * 120f + 70f, rowHeightPx = 120f, count = 8))
        assertEquals(2, reorderSlot(draggedTopPx = 3 * 120f - 70f, rowHeightPx = 120f, count = 8))
    }

    @Test
    fun `a card cannot be dragged out of the list`() {
        assertEquals(0, reorderSlot(draggedTopPx = -900f, rowHeightPx = 120f, count = 5))
        assertEquals(4, reorderSlot(draggedTopPx = 900f, rowHeightPx = 120f, count = 5))
    }

    @Test
    fun `an unmeasured or empty list resolves to the first slot`() {
        assertEquals(0, reorderSlot(draggedTopPx = 400f, rowHeightPx = 0f, count = 5))
        assertEquals(0, reorderSlot(draggedTopPx = 0f, rowHeightPx = 120f, count = 0))
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
    fun `saving the movie order leaves the other boards exactly where they were`() {
        // The server stores one position per section, so the only thing a save
        // may change is the relative order of the movie ids it was given.
        val all = listOf("feed-1", "asmr-1", "movie-1", "movie-2", "feed-2", "drama-1")
        val movies = setOf("movie-1", "movie-2")

        val merged = mergeReorderedSection(all, movies, listOf("movie-2", "movie-1"))

        // The movies swap places with each other; nothing else changes slot.
        assertEquals(listOf("feed-1", "asmr-1", "movie-2", "movie-1", "feed-2", "drama-1"), merged)
        assertEquals(listOf("feed-1", "asmr-1", "feed-2", "drama-1"), merged.filterNot { it in movies })
    }

    @Test
    fun `an untouched movie order produces the original list`() {
        val all = listOf("feed-1", "movie-1", "movie-2")
        val movies = setOf("movie-1", "movie-2")

        assertEquals(all, mergeReorderedSection(all, movies, listOf("movie-1", "movie-2")))
    }

    @Test
    fun `a movie the draft left out follows on instead of disappearing`() {
        val all = listOf("movie-1", "movie-2", "movie-3")
        val movies = setOf("movie-1", "movie-2", "movie-3")

        // A library added elsewhere while this screen was open cannot be dropped
        // by a save: it keeps its old relative order behind the ones named.
        assertEquals(
            listOf("movie-3", "movie-1", "movie-2"),
            mergeReorderedSection(all, movies, listOf("movie-3")),
        )
        assertEquals(
            listOf("movie-3", "movie-1", "movie-2"),
            mergeReorderedSection(all, movies, listOf("movie-3", "movie-1", "movie-2")),
        )
    }

    @Test
    fun `a duplicated or unknown id never lands in the list twice`() {
        val all = listOf("movie-1", "movie-2")
        val movies = setOf("movie-1", "movie-2")

        assertEquals(
            listOf("movie-2", "movie-1"),
            mergeReorderedSection(all, movies, listOf("movie-2", "movie-2", "ghost", "movie-1")),
        )
    }

    @Test
    fun `a section with no ids in the list is returned untouched`() {
        val all = listOf("feed-1")

        assertEquals(all, mergeReorderedSection(all, emptySet(), emptyList()))
    }
}
