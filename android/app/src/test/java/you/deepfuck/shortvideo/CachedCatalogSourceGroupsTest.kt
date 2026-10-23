package you.deepfuck.shortvideo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedCatalogSourceGroupsTest {
    @Test
    fun groupsKeepAlistRootsTogetherAndHideTvboxParent() {
        val groups = cachedSourceGroups(
            sourcePayloads = listOf(
                source("alist-a", "alist", "connection:alist", "AList"),
                source("alist-b", "alist", "connection:alist", "AList"),
                source("tv-parent", "tvbox", "source:tv-parent", "TVBox"),
                source("tv-movie", "tvbox", "parent:tv-parent", "TVBox", "tv-parent"),
                source("tv-drama", "tvbox", "parent:tv-parent", "TVBox", "tv-parent"),
            ),
            pageTotals = mapOf(
                "alist-a" to 10,
                "alist-b" to 20,
                "tv-parent" to 0,
                "tv-movie" to 7,
                "tv-drama" to 5,
            ),
        )

        assertEquals(2, groups.size)
        assertEquals("connection:alist", groups[0].id)
        assertEquals(listOf("alist-a", "alist-b"), groups[0].libraryIds)
        assertEquals(30, groups[0].total)
        assertEquals("parent:tv-parent", groups[1].id)
        assertEquals(listOf("tv-drama", "tv-movie"), groups[1].libraryIds)
        assertEquals(12, groups[1].total)
        assertTrue(groups.none { it.libraryIds.contains("tv-parent") })
    }

    @Test
    fun unknownCachedPageIsNotPresentedAsAMediaSource() {
        val groups = cachedSourceGroups(
            sourcePayloads = emptyList(),
            pageTotals = mapOf("stale-internal-id" to 6),
        )

        assertTrue(groups.isEmpty())
    }

    private fun source(
        id: String,
        provider: String,
        groupId: String,
        groupName: String,
        parentId: String? = null,
    ) = JSONObject()
        .put("id", id)
        .put("provider", provider)
        .put("sourceGroupId", groupId)
        .put("sourceGroupName", groupName)
        .apply { parentId?.let { put("parentSourceId", it) } }
}
