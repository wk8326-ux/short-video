package you.deepfuck.shortvideo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaStoreTest {
    @Test
    fun cachedSearchFindsFolderAndTitleWithoutCrossingAccounts() {
        val store = store()
        store.cacheLibraryResults("account-a", JSONArray().put(mediaResult(
            id = "asmr:7",
            title = "夜雨.mp3",
            path = "/asmr/中文音声/小苮儿/夜雨.mp3",
            author = "小苮儿",
        )))

        val found = store.search("account-a", "小苮儿", "all", 20)
        val otherAccount = store.search("account-b", "小苮儿", "all", 20)

        assertEquals(1, found.size)
        assertEquals("夜雨.mp3", found.single().getString("title"))
        assertTrue(otherAccount.isEmpty())
    }

    @Test
    fun favoriteMutationsAreLocalFirstAndRemainQueuedUntilAcknowledged() {
        val store = store()
        val item = mediaResult("short:41", "local.mp4", "/short/local.mp4", null)
        store.cacheLibraryResults("account-a", JSONArray().put(item))

        store.setFavorite("account-a", "short", "41", item, true)

        val page = store.favoritePage("account-a", "short", 0, 20)
        assertEquals(1, page.total)
        assertTrue(page.items.single().getBoolean("favorite"))
        assertEquals(true, store.pendingFavoriteChanges("account-a").single().getBoolean("favorite"))

        store.markFavoriteSynced("account-a", "short", "41", true)

        assertTrue(store.pendingFavoriteChanges("account-a").isEmpty())
        store.setFavorite("account-a", "short", "41", item, false)
        assertEquals(0, store.favoritePage("account-a", "short", 0, 20).total)
        assertFalse(store.pendingFavoriteChanges("account-a").single().getBoolean("favorite"))
        assertFalse(
            store.cachedPage("account-a", "short", 0, 20).items.single()
                .getBoolean("favorite"),
        )
    }

    @Test
    fun removingMovieFavoriteOverridesStaleServerFavoriteLocally() {
        val store = store()
        val movie = JSONObject()
            .put("id", 73)
            .put("title", "本地电影")
            .put("favorite", true)
            .put("source", "movie-source")
        store.cacheCatalogItem("account-a", "movie", movie)
        store.setFavorite("account-a", "movie", "73", null, false)

        val catalog = store.cachedPage("account-a", "movie", 0, 20).items.single()

        assertFalse(catalog.getBoolean("favorite"))
        assertFalse(catalog.getJSONObject("movie").getBoolean("favorite"))
        assertEquals(0, store.favoritePage("account-a", "movie", 0, 20).total)
    }

    @Test
    fun sourceCursorIsIndependentForEachSourceAndAccount() {
        val store = store()

        store.saveCursor("account-a", "movie-source", 82L)
        store.saveCursor("account-a", "asmr-source", 101L)
        store.saveCursor("account-b", "movie-source", 9L)

        assertEquals(82L, store.cursor("account-a", "movie-source"))
        assertEquals(101L, store.cursor("account-a", "asmr-source"))
        assertEquals(9L, store.cursor("account-b", "movie-source"))
    }

    @Test
    fun deletingASourceRemovesAllCachedItemsOwnedByThatSource() {
        val store = store()
        store.cacheSources(
            "account-a",
            listOf(
                JSONObject().put("id", "source-keep").put("name", "保留源").put("section", "movie"),
                JSONObject().put("id", "source-delete").put("name", "删除源").put("section", "movie"),
            ),
        )
        store.cacheCatalogItem(
            "account-a",
            "movie",
            JSONObject()
                .put("id", "movie-1")
                .put("title", "保留源")
                .put("sourceId", "source-keep"),
        )
        store.cacheCatalogItem(
            "account-a",
            "movie",
            JSONObject()
                .put("id", "movie-2")
                .put("title", "删除源")
                .put("sourceId", "source-delete"),
        )

        store.deleteSource("account-a", "source-delete")

        assertEquals(1, store.cachedPage("account-a", "movie", 0, 20).total)
        assertEquals("保留源", store.cachedPage("account-a", "movie", 0, 20).items.single().getString("title"))
        assertTrue(store.search("account-a", "删除源", "movie", 20).isEmpty())
        assertEquals(listOf("source-keep"), store.cachedSources("account-a", "movie").map { it.getString("id") })
        assertTrue(store.cachedSources("account-b").isEmpty())
    }

    @Test
    fun cachedPagesCanBeReadPerSource() {
        val store = store()
        store.cacheCatalogItem(
            "account-a",
            "movie",
            JSONObject().put("id", "movie-a").put("title", "源 A").put("sourceId", "source-a"),
        )
        store.cacheCatalogItem(
            "account-a",
            "movie",
            JSONObject().put("id", "movie-b").put("title", "源 B").put("sourceId", "source-b"),
        )

        val sourceA = store.cachedPage("account-a", "movie", 0, 20, sourceId = "source-a")

        assertEquals(1, sourceA.total)
        assertEquals("源 A", sourceA.items.single().getString("title"))
        assertEquals(setOf("source-a", "source-b"), store.cachedPagesBySource("account-a", "movie", 20).keys)
    }

    @Test
    fun groupedSourceCachePreservesTvboxHierarchy() {
        val store = store()
        store.cacheSources(
            "account-a",
            listOf(
                JSONObject()
                    .put("id", "tv-child")
                    .put("section", "movie")
                    .put("provider", "tvbox")
                    .put("parentSourceId", "tv-parent")
                    .put("name", "旧源名称"),
            ),
        )
        store.cacheSourceLabels(
            "account-a",
            "movie",
            JSONArray().put(
                JSONObject().put("sourceId", "tv-child").put("name", "电影分类"),
            ),
        )

        store.cacheSourceGroups(
            "account-a",
            "movie",
            JSONArray().put(
                JSONObject()
                    .put("id", "parent:tv-parent")
                    .put("name", "TVBox")
                    .put("provider", "tvbox")
                    .put("libraryIds", JSONArray().put("tv-child")),
            ),
        )

        val cached = store.cachedSources("account-a", "movie").single()

        assertEquals("电影分类", cached.getString("name"))
        assertEquals("tv-parent", cached.getString("parentSourceId"))
        assertEquals("parent:tv-parent", cached.getString("sourceGroupId"))
        assertEquals("TVBox", cached.getString("sourceGroupName"))
    }

    @Test
    fun clearingCatalogKeepsFavoritesRecentItemsAndRemovesSyncState() {
        val store = store()
        val movie = JSONObject()
            .put("id", 73)
            .put("title", "本地电影")
            .put("source", "source-a")
        store.cacheSources(
            "account-a",
            listOf(JSONObject().put("id", "source-a").put("name", "媒体源").put("section", "movie")),
        )
        store.cacheCatalogItem("account-a", "movie", movie)
        store.setFavorite("account-a", "movie", "73", null, true)
        store.recordRecent("account-a", "movie", movie)
        store.saveCursor("account-a", "source:movie:source-a", 42L)

        store.clearCatalog("account-a")

        assertTrue(store.cachedSources("account-a", "movie").isEmpty())
        assertEquals(0, store.cachedPage("account-a", "movie", 0, 20).total)
        assertTrue(store.search("account-a", "本地电影", "movie", 20).isEmpty())
        assertFalse(store.hasCursor("account-a", "source:movie:source-a"))
        assertEquals(1, store.favoritePage("account-a", "movie", 0, 20).total)
        assertEquals(1, store.recentItems("account-a", "movie", 10).size)
    }

    @Test
    fun detailFieldsSurviveLaterSummaryUpserts() {
        val store = store()
        store.cacheCatalogItem(
            "account-a",
            "drama",
            JSONObject()
                .put("id", "series-1")
                .put("title", "剧集")
                .put("sourceId", "drama-source"),
        )
        store.cacheCatalogItem(
            "account-a",
            "drama",
            JSONObject()
                .put("id", "series-1")
                .put("title", "剧集")
                .put("sourceId", "drama-source")
                .put("episodes", JSONArray().put(JSONObject().put("videoId", 7))),
        )
        store.cacheCatalogItem(
            "account-a",
            "drama",
            JSONObject()
                .put("id", "series-1")
                .put("title", "剧集更新")
                .put("sourceId", "drama-source"),
        )

        val cached = store.cachedItem("account-a", "drama", "series-1")
        val drama = cached!!.getJSONObject("drama")
        assertEquals("剧集更新", drama.getString("title"))
        assertEquals(1, drama.getJSONArray("episodes").length())
    }

    @Test
    fun recentItemsAreAccountScopedAndNewestFirst() {
        val store = store()
        store.recordRecent(
            "account-a",
            "movie",
            JSONObject().put("id", 1).put("title", "旧电影").put("source", "source-a"),
        )
        Thread.sleep(2)
        store.recordRecent(
            "account-a",
            "movie",
            JSONObject().put("id", 2).put("title", "新电影").put("source", "source-a"),
        )

        val recent = store.recentItems("account-a", "movie", 10)

        assertEquals(listOf("新电影", "旧电影"), recent.map { it.getString("title") })
        assertTrue(store.recentItems("account-b", "movie", 10).isEmpty())
    }

    private fun store(): LocalMediaStore {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("local-media-test.db")
        return LocalMediaStore(context, "local-media-test.db")
    }

    private fun mediaResult(
        id: String,
        title: String,
        path: String,
        author: String?,
    ): JSONObject = JSONObject()
        .put("type", "media")
        .put("section", id.substringBefore(':'))
        .put("id", id)
        .put("title", title)
        .put("subtitle", "ASMR · 音频")
        .put("posterUrl", JSONObject.NULL)
        .put("favorite", false)
        .put("media", JSONObject()
            .put("id", id.substringAfter(':').toLong())
            .put("sourceId", "source-default")
            .put("title", title)
            .put("size", 1)
            .put("modified", JSONObject.NULL)
            .put("duration", JSONObject.NULL)
            .put("playUrl", "/api/videos/${id.substringAfter(':')}/play")
            .put("posterUrl", JSONObject.NULL)
            .put("author", author ?: JSONObject.NULL)
            .put("path", path)
            .put("format", "mp3")
            .put("kind", "audio"))
}
