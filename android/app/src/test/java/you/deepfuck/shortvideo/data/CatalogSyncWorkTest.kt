package you.deepfuck.shortvideo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSyncWorkTest {
    @Test
    fun bootstrapWalkerFollowsEveryOffset() {
        val requestedOffsets = mutableListOf<Int>()
        val consumedOffsets = mutableListOf<Int>()

        walkCatalogBootstrapPages(
            fetch = { offset ->
                requestedOffsets += offset
                syncPage(nextOffset = if (offset == 0) 300 else null)
            },
            consume = { page -> consumedOffsets += (page.nextOffset ?: -1) },
        )

        assertEquals(listOf(0, 300), requestedOffsets)
        assertEquals(listOf(300, -1), consumedOffsets)
    }

    @Test
    fun changeWalkerFollowsCursorUntilServerSaysDone() {
        val requestedCursors = mutableListOf<Long>()
        val consumedCursors = mutableListOf<Long>()

        val finalCursor = walkCatalogChangePages(
            initialCursor = 10,
            fetch = { cursor ->
                requestedCursors += cursor
                when (cursor) {
                    10L -> syncPage(nextCursor = 20, hasMore = true)
                    20L -> syncPage(nextCursor = 30, hasMore = true)
                    else -> syncPage(nextCursor = 30, hasMore = false)
                }
            },
            consume = { page -> consumedCursors += page.nextCursor },
        )

        assertEquals(listOf(10L, 20L, 30L), requestedCursors)
        assertEquals(listOf(20L, 30L, 30L), consumedCursors)
        assertEquals(30L, finalCursor)
    }

    private fun syncPage(
        nextOffset: Int? = null,
        nextCursor: Long = 0L,
        hasMore: Boolean = false,
    ): CatalogSyncPage = CatalogSyncPage(
        serverRevision = maxOf(nextCursor, 30L),
        nextCursor = nextCursor,
        nextOffset = nextOffset,
        hasMore = hasMore,
        sources = emptyList(),
        upserts = emptyList(),
        deletes = emptyList(),
    )
}
