package you.deepfuck.shortvideo.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.data.MediaLibrarySource

/**
 * The manager shows playback sources on the left and their libraries on the
 * right, so a TVBox source's generated libraries have to stay attached to the
 * source that generated them - and an AList connection, which has no parent
 * row of its own, has to come out as one group rather than one group per
 * library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerSourceGroupTest {
    private fun source(
        id: String,
        provider: String,
        parent: String? = null,
        groupId: String? = null,
        groupName: String? = null,
    ) = MediaLibrarySource.fromJson(
        JSONObject(
            """
            {
              "id": "$id",
              "name": "$id",
              "provider": "$provider",
              "baseUrl": "https://example.com",
              "rootPath": "/",
              "section": "movie",
              "videos": 3,
              ${if (parent != null) "\"parentSourceId\": \"$parent\"," else ""}
              ${if (groupId != null) "\"sourceGroupId\": \"$groupId\"," else ""}
              ${if (groupName != null) "\"sourceGroupName\": \"$groupName\"," else ""}
              "scan": {"running": false, "directories": 0}
            }
            """
        )
    )

    @Test
    fun oneTvboxSourceKeepsItsParentAndAllGeneratedLibraries() {
        val parent = source("forest", "tvbox")
        val groups = groupSourcesForManager(
            listOf(
                parent,
                source("forest-duanju", "html", parent = "forest", groupId = "parent:forest", groupName = "森林"),
                source("forest-manju", "html", parent = "forest", groupId = "parent:forest", groupName = "森林"),
            )
        )

        assertEquals(1, groups.size)
        assertEquals("parent:forest", groups[0].groupId)
        assertEquals("森林", groups[0].name)
        assertSame(parent, groups[0].parent)
        assertEquals(
            listOf("forest-duanju", "forest-manju"),
            groups[0].libraries.map { it.id },
        )
    }

    @Test
    fun oneAlistConnectionIsASingleGroupOfItsOwnLibraries() {
        val groups = groupSourcesForManager(
            listOf(
                source("alist-1", "alist", groupId = "connection:abc", groupName = "AList"),
                source("alist-2", "alist", groupId = "connection:abc", groupName = "AList"),
                source("alist-3", "alist", groupId = "connection:abc", groupName = "AList"),
            )
        )

        assertEquals(1, groups.size)
        // An AList connection is not a source with children: it is the source.
        assertNull(groups[0].parent)
        assertEquals(3, groups[0].libraries.size)
    }

    @Test
    fun sourcesOfDifferentConnectionsStayApart() {
        val groups = groupSourcesForManager(
            listOf(
                source("alist-1", "alist", groupId = "connection:abc", groupName = "AList"),
                source("openlist-1", "openlist", groupId = "connection:def", groupName = "OpenList"),
                source("155", "tvbox"),
            )
        )

        assertEquals(3, groups.size)
        assertEquals(
            // The TVBox source has generated no library yet, so it is its own
            // group and keeps its own id as the key.
            listOf("connection:abc", "connection:def", "155"),
            groups.map { it.groupId },
        )
    }
}
