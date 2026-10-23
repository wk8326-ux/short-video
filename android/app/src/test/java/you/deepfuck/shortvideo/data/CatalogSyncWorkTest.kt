package you.deepfuck.shortvideo.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CatalogSyncWorkTest {
    @Test
    fun bootstrapWalkerFollowsEveryOffset() = runBlocking {
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
    fun changeWalkerFollowsCursorUntilServerSaysDone() = runBlocking {
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

    @Test
    fun progressDataPreservesCurrentSourceAndCounts() {
        val expected = CatalogSyncProgress(
            section = "movie",
            sourceId = "source-1",
            sourceName = "电影库",
            sourceIndex = 2,
            sourceCount = 5,
            phase = "bootstrap",
            pages = 14,
            records = 4_200,
        )

        assertEquals(expected, CatalogSyncProgress.fromData(expected.toData()))
    }

    @Test
    fun queuedRefreshIsBoundToItsAccountAndRunId() {
        val data = CatalogSyncWorker.inputData(
            account = "account-hash",
            section = "movie",
            sourceId = "source-1",
            refreshId = "refresh-1",
        )

        assertEquals("account-hash", data.getString(CatalogSyncWorker.KEY_ACCOUNT))
        assertEquals("movie", data.getString(CatalogSyncWorker.KEY_SECTION))
        assertEquals("source-1", data.getString(CatalogSyncWorker.KEY_SOURCE_ID))
        assertEquals("refresh-1", data.getString(CatalogSyncWorker.KEY_REFRESH_ID))
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
