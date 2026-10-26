package you.deepfuck.shortvideo.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvBoxRepositoryTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var repository: TvBoxRepository
    private var clock = 1_000_000L
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "tvbox-v1").deleteRecursively()
        server = MockWebServer()
        server.start()
        repository = newRepository()
        repository.importConfig(configuration())
    }

    @After
    fun tearDown() {
        server.shutdown()
        File(context.filesDir, "tvbox-v1").deleteRecursively()
    }

    @Test
    fun constructionLoadsBundledDefaultsOfflineAndNeverOverwritesAnImport() {
        val stored = repository.config()
        assertEquals(stored, newRepository().config())
        assertEquals(0, server.requestCount)

        File(context.filesDir, "tvbox-v1").deleteRecursively()
        val bundled = newRepository().config()
        val available = context.assets.list("").orEmpty().contains("tvbox.json")
        if (available) {
            assertEquals("asset:tvbox.json", bundled?.origin)
            assertEquals(5, bundled?.sites?.size)
        } else assertNull(bundled)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun catalogUsesOneRequestAndPreservesSparseStringCategoryIds() {
        enqueue(catalogPayload(total = 850))
        val site = repository.sites().first()
        val result = repository.catalog(site.id)

        assertEquals(listOf("17", "904", "anime-special"), result.categories.map { it.id })
        assertEquals(850, result.source.total)
        assertEquals(3, result.source.libraryCount)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("list", request.requestUrl?.queryParameter("ac"))
        assertEquals("1", request.requestUrl?.queryParameter("pg"))
        assertNull(request.getHeader("Cookie"))
        assertEquals(result.categories, newRepository().categories(site.id))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun searchAndCategoryCountsCannotOverwriteSourceTotalsOrEachOther() {
        val sources = repository.sites()
        enqueue(catalogPayload(850))
        repository.catalog(sources[0].id)
        enqueue(pagePayload(total = 80, pageCount = 4))
        repository.categoryPage(sources[0].id, "904")
        enqueue(pagePayload(total = 3, pageCount = 1))
        repository.search(sources[0].id, "term")
        enqueue(catalogPayload(25))
        repository.catalog(sources[1].id)

        val counts = newRepository().sourceSummaries().associate { it.id to it.total }
        assertEquals(850, counts[sources[0].id])
        assertEquals(25, counts[sources[1].id])
        assertEquals(4, server.requestCount)
    }

    @Test
    fun unknownTotalsRemainUnknownEvenWhenAResponseContainsItems() {
        val source = repository.sites().first()
        assertEquals(-1, repository.sourceSummaries().first().total)
        enqueue(JSONObject().put("class", JSONArray()).put("list", JSONArray().put(vod("42"))))
        val catalog = repository.catalog(source.id)
        assertEquals(-1, catalog.source.total)
        assertEquals(-1, catalog.page.total)
        assertEquals(-1, catalog.page.pageCount)
        assertEquals(2, catalog.page.nextPage)
    }

    @Test
    fun pagingTransmitsRemoteCategoryAndQueryAndCachesEachPageIndependently() {
        val source = repository.sites().first()
        enqueue(pagePayload(60, 3, page = 2))
        val middle = repository.categoryPage(source.id, "anime-special", 2, "name & extra")
        val request = server.takeRequest()
        assertEquals("detail", request.requestUrl?.queryParameter("ac"))
        assertEquals("anime-special", request.requestUrl?.queryParameter("t"))
        assertEquals("name & extra", request.requestUrl?.queryParameter("wd"))
        assertEquals("2", request.requestUrl?.queryParameter("pg"))
        assertEquals(3, middle.nextPage)

        enqueue(pagePayload(60, 3, page = 3, id = "43"))
        assertNull(repository.categoryPage(source.id, "anime-special", 3, "name & extra").nextPage)
        assertTrue(repository.categoryPage(source.id, "anime-special", 2, "name & extra").fromCache)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun emptyPagesStopAndPaginationCanBeInferredFromLimit() {
        val source = repository.sites().first()
        enqueue(JSONObject().put("total", "41").put("limit", "20").put("page", "3")
            .put("list", JSONArray().put(vod("41"))))
        val finalPage = repository.categoryPage(source.id, "17", 3)
        assertEquals(3, finalPage.pageCount)
        assertNull(finalPage.nextPage)
        enqueue(JSONObject().put("page", 4).put("list", JSONArray()))
        assertNull(repository.categoryPage(source.id, "17", 4).nextPage)
    }

    @Test
    fun equalRemoteIdsHaveDifferentStableNegativeIdsAcrossSourcesAndReimports() {
        val sources = repository.sites()
        enqueue(pagePayload())
        val first = repository.categoryPage(sources[0].id, null).items.single()
        enqueue(pagePayload())
        val second = repository.categoryPage(sources[1].id, null).items.single()

        assertTrue(first.id < 0L)
        assertTrue(first.videoId < 0L)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.videoId, second.videoId)
        repository.importConfig(configuration().replace("First", "Renamed"))
        assertEquals(first.id, newRepository().categoryPage(sources[0].id, null).items.single().id)
    }

    @Test
    fun detailPreservesEveryPlayLineAndResolvesTheSelectedEpisodeWithoutRequests() {
        val (movie, detail) = loadDetail()
        assertEquals(movie.id, detail.movie.id)
        assertEquals(listOf("hls", "backup"), detail.playLines.map { it.name })
        assertEquals(listOf("One", "Two"), detail.playLines[0].episodes.map { it.name })
        assertEquals(1, detail.playLines[1].episodes.size)
        val line = detail.playLines[0]
        val episode = line.episodes[1]
        val media = repository.resolvePlayback(detail, line.id, episode.id)
        assertEquals("https://video.test/two.m3u8?token=a\$b", media.playUrl)
        assertEquals("m3u8", media.format)
        assertEquals(episode.id, media.id)
        assertTrue(media.isHls)
        assertEquals(2, server.requestCount)
        val listRequest = server.takeRequest()
        val detailRequest = server.takeRequest()
        assertNull(listRequest.requestUrl?.queryParameter("ids"))
        assertEquals("42", detailRequest.requestUrl?.queryParameter("ids"))
    }

    @Test
    fun invalidPlaybackUrlsFailClearlyAndDoNotContactAParserOrBackend() {
        val (_, detail) = loadDetail(playUrl = "First\$magnet:?xt=test")
        val line = detail.playLines.single()
        val error = expectFailure<TvBoxPlaybackException> {
            repository.resolvePlayback(detail, line.id, line.episodes.single().id)
        }
        assertTrue(error.message.orEmpty().contains("HTTP(S)"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun pageWritesPersistLocalMovieMappingAfterUpdatingSourceTotal() {
        val source = repository.sites().first()
        enqueue(pagePayload())
        val movie = repository.categoryPage(source.id, null).items.single()
        val state = TvBoxStore(context).state(source.id)
        assertEquals(1, state.getInt("total"))
        assertEquals("42", state.getJSONObject("items").getJSONObject(movie.id.toString())
            .getJSONObject("payload").getString("vod_id"))
        enqueue(JSONObject().put("list", JSONArray().put(vod("42"))))
        assertEquals(movie.id, newRepository().detail(movie.id).movie.id)
    }

    @Test
    fun detailAlwaysRefreshesExpiringUrlsAndNeverFallsBackOffline() {
        val (movie, first) = loadDetail()
        val refreshedPayload = vod("42").put("vod_play_url", "First\$https://video.test/fresh.m3u8?token=new")
        enqueue(JSONObject().put("list", JSONArray().put(refreshedPayload)))
        val refreshed = repository.detail(movie.id)
        assertFalse(refreshed.fromCache)
        assertNotEquals(first.playLines.first().episodes.first().url, refreshed.playLines.first().episodes.first().url)
        server.enqueue(MockResponse().setResponseCode(503))
        expectFailure<TvBoxHttpException> { repository.detail(movie.id, forceRefresh = true) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun favoritesRecentSettingsAndPerEpisodeProgressSurviveRecreationAndRemainSourceScoped() {
        val (movie, detail) = loadDetail()
        val line = detail.playLines[1]
        val episode = line.episodes.single()
        repository.setFavorite(movie.id, true)
        repository.saveProgress(movie.id, line.id, episode.id, 32_000L, 100_000L)
        repository.saveSettings(TvBoxSettings(detail.sourceId, MovieWallView.ROWS, false, "paper-ink"))
        val otherSource = repository.sites()[1]
        enqueue(pagePayload())
        val other = repository.categoryPage(otherSource.id, null).items.single()

        val restored = newRepository()
        assertEquals(listOf(movie.id), restored.favorites().map { it.id })
        assertEquals(listOf(movie.id), restored.recent(detail.sourceId).map { it.id })
        assertTrue(restored.favorites(otherSource.id).isEmpty())
        assertNull(restored.progress(other.id))
        assertEquals(episode.id, restored.progress(movie.id)?.episodeId)
        assertEquals(line.id, restored.progress(movie.id)?.lineId)
        assertEquals(32_000L, restored.recent().single().resumePositionMs)
        assertEquals(episode.id, restored.recent().single().videoId)
        assertTrue(restored.recent().single().favorite)
        assertEquals(TvBoxSettings(detail.sourceId, MovieWallView.ROWS, false, "paper-ink"), restored.settings())
        restored.setFavorite(movie.id, false)
        assertTrue(newRepository().favorites().isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun invalidConfigOrFailedSubscriptionNeverReplacesStoredConfig() {
        val previous = repository.config()
        expectFailure<TvBoxConfigException> { repository.importConfig(configuration().replace("\"type\":1", "\"type\":3")) }
        assertEquals(previous, repository.config())
        enqueue(JSONObject().put("sites", JSONArray()))
        expectFailure<TvBoxConfigException> { repository.importConfig(server.url("/bad-config").toString()) }
        server.enqueue(MockResponse().setResponseCode(503))
        expectFailure<TvBoxHttpException> { repository.importConfig(server.url("/down-config").toString()) }
        assertEquals(previous, newRepository().config())
    }

    @Test
    fun configurationRefreshUsesItsSubscriptionAndSourceControlsPersist() {
        server.enqueue(MockResponse().setBody(configuration()))
        repository.importConfig(server.url("/subscription").toString())
        val sources = repository.sites()
        repository.setSourceEnabled(sources[0].id, false)
        repository.moveSource(sources[1].id, -1)
        val restored = newRepository()
        assertEquals(listOf(sources[1].id, sources[0].id), restored.sites().map { it.id })
        assertEquals(setOf(sources[0].id), restored.disabledSourceIds())
        assertEquals(listOf(sources[1].id), restored.sourceSummaries().map { it.id })
        restored.removeSource(sources[0].id)
        assertEquals(listOf(sources[1].id), newRepository().sites().map { it.id })
        restored.removeSource(sources[1].id)
        assertTrue(newRepository().sites().isEmpty())
        server.enqueue(MockResponse().setBody(configuration().replace("First", "Updated")))
        assertEquals("Updated", restored.refreshConfig().sites.first().name)
        assertEquals("/subscription", server.takeRequest().path)
        assertEquals("/subscription", server.takeRequest().path)
    }

    @Test
    fun subscriptionRefreshPreservesLocalOrderAndEnabledStateForMatchingSources() {
        server.enqueue(MockResponse().setBody(configuration()))
        repository.importConfig(server.url("/subscription").toString())
        val original = repository.sites()
        repository.setSourceEnabled(original[0].id, false)
        repository.moveSource(original[1].id, -1)

        val updatedSubscription = JSONObject().put("sites", JSONArray()
            .put(JSONObject().put("key", "first").put("name", "First updated").put("type", 1)
                .put("api", server.url("/first/api")))
            .put(JSONObject().put("key", "second").put("name", "Second updated").put("type", 1)
                .put("api", server.url("/second/api")))
            .put(JSONObject().put("key", "third").put("name", "Third").put("type", 1)
                .put("api", server.url("/third/api"))))
            .toString()
        server.enqueue(MockResponse().setBody(updatedSubscription))

        val refreshed = repository.refreshConfig()

        assertEquals(listOf(original[1].id, original[0].id, TvBoxConfigCodec.decode(updatedSubscription).sites[2].id),
            refreshed.sites.map { it.id })
        assertEquals(listOf("Second updated", "First updated", "Third"), refreshed.sites.map { it.name })
        assertEquals(listOf(true, false, true), refreshed.sites.map { it.enabled })
        assertEquals("/subscription", server.takeRequest().path)
        assertEquals("/subscription", server.takeRequest().path)
    }

    @Test
    fun transientNetworkFailureFallsBackOnlyToTheMatchingPersistedPage() {
        val source = repository.sites().first()
        enqueue(pagePayload())
        val original = repository.categoryPage(source.id, "17")
        clock += 6 * 60_000L
        server.enqueue(MockResponse().setResponseCode(503))
        val fallback = newRepository().categoryPage(source.id, "17")
        assertTrue(fallback.fromCache)
        assertEquals(original.items, fallback.items)
        server.enqueue(MockResponse().setResponseCode(503))
        expectFailure<TvBoxHttpException> { repository.categoryPage(source.id, "904") }
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, expectFailure<TvBoxHttpException> { repository.categoryPage(source.id, "17") }.statusCode)
    }

    @Test
    fun socketFailureIsAnIOExceptionAndDoesNotCreateAnEmptySuccessfulPage() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        expectFailure<IOException> { repository.categoryPage(repository.sites().first().id, "17") }
        assertEquals(-1, repository.sourceSummaries().first().total)
    }

    @Test
    fun malformedAndErrorPayloadsDoNotPoisonAGoodCachedPage() {
        val source = repository.sites().first()
        enqueue(pagePayload())
        repository.categoryPage(source.id, "17")
        server.enqueue(MockResponse().setBody("<html>error</html>"))
        expectFailure<TvBoxProtocolException> { repository.categoryPage(source.id, "17", forceRefresh = true) }
        enqueue(JSONObject().put("code", 0).put("msg", "bad request").put("list", JSONArray()))
        expectFailure<TvBoxProtocolException> { repository.categoryPage(source.id, "17", forceRefresh = true) }
        assertEquals(1, newRepository().categoryPage(source.id, "17").items.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun cacheClearPreservesFavoritesProgressSettingsAndConfiguration() {
        val (movie, detail) = loadDetail()
        val line = detail.playLines.first()
        repository.setFavorite(movie.id, true)
        val progress = repository.saveProgress(movie.id, line.id, line.episodes.first().id, 10L, 20L)
        val config = repository.config()
        val settings = repository.settings()
        repository.clearCache()
        val restored = newRepository()
        assertEquals(config, restored.config())
        assertEquals(settings, restored.settings())
        assertEquals(progress, restored.progress(movie.id))
        assertEquals(movie.id, restored.favorites().single().id)
        enqueue(pagePayload())
        assertFalse(restored.categoryPage(detail.sourceId, null).fromCache)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun inFlightRequestsCannotRepopulateClearedCaches() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return MockResponse().setBody(pagePayload().toString())
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = repository.sites().first()
            val pending = executor.submit<TvBoxPage> { repository.categoryPage(source.id, "17") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repository.clearCache()
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            val cacheFiles = File(context.filesDir, "tvbox-v1").walkTopDown()
                .filter { it.isFile && it.parentFile?.name == "responses" }.toList()
            assertTrue(cacheFiles.isEmpty())
            assertFalse(repository.categoryPage(source.id, "17").fromCache)
            assertEquals(2, server.requestCount)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun catalogRecordsAreBoundedAndProtectedFavoritesSurviveEviction() {
        val (movie, detail) = loadDetail()
        repository.setFavorite(movie.id, true)
        val list = JSONArray()
        repeat(2_100) { list.put(vod("new-$it")) }
        enqueue(JSONObject().put("list", list))
        repository.categoryPage(detail.sourceId, "904")
        val state = TvBoxStore(context).state(detail.sourceId)
        assertEquals(2_000, state.getJSONObject("items").length())
        assertEquals(movie.id, newRepository().favorites().single().id)
    }

    @Test
    fun displayedMovieCanLoadFreshDetailAfterPagingEvictsItsLocalMapping() {
        val displayed = loadDisplayedMoviesThenEvict().first()
        val ref = requireNotNull(displayed.tvBoxRef)
        assertEquals(displayed, MovieItem.fromJson(displayed.toJson()))
        val restored = newRepository()
        val requestsBeforeDetail = server.requestCount
        val refreshed = vod(ref.remoteId)
            .put("vod_play_from", "hls\$\$\$backup")
            .put("vod_play_url", "One\$https://video.test/fresh.m3u8?token=new#Two\$https://video.test/two.m3u8\$\$\$Full\$https://video.test/full.mp4")
        enqueue(JSONObject().put("list", JSONArray().put(refreshed)))

        val detail = restored.detail(MovieItem.fromJson(displayed.toJson()), forceRefresh = true)

        assertEquals(displayed.id, detail.movie.id)
        assertEquals(ref.sourceId, detail.sourceId)
        assertEquals(ref.remoteId, detail.remoteId)
        assertFalse(detail.fromCache)
        assertEquals(listOf(2, 1), detail.playLines.map { it.episodes.size })
        val line = detail.playLines.first()
        val entry = restored.resolvePlayback(detail, line.id, line.episodes.first().id)
        assertEquals("https://video.test/fresh.m3u8?token=new", entry.playUrl)
        assertTrue(entry.isHls)
        assertEquals(requestsBeforeDetail + 1, server.requestCount)
        val detailRequest = (0 until server.requestCount).map { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }.last()
        assertEquals("/first/api", detailRequest.requestUrl?.encodedPath)
        assertEquals("detail", detailRequest.requestUrl?.queryParameter("ac"))
        assertEquals(ref.remoteId, detailRequest.requestUrl?.queryParameter("ids"))
        assertEquals(2_000, TvBoxStore(context).state(ref.sourceId).getJSONObject("items").length())
        assertEquals(ref.remoteId, TvBoxStore(context).state(ref.sourceId).getJSONObject("items")
            .getJSONObject(displayed.id.toString()).getJSONObject("payload").getString("vod_id"))
    }

    @Test
    fun evictedDisplayedMovieCanBeFavoritedOfflineAndSurvivesRecreationAndCacheClear() {
        val displayed = loadDisplayedMoviesThenEvict().last()
        val sourceId = requireNotNull(displayed.tvBoxRef).sourceId
        val otherSourceId = repository.sites().last().id
        val requestsBeforeFavorite = server.requestCount
        val restored = newRepository()

        val favorite = restored.setFavorite(displayed, true)

        assertTrue(favorite.favorite)
        assertEquals(displayed.id, favorite.id)
        assertEquals(displayed.title, favorite.title)
        assertEquals(displayed.overview, favorite.overview)
        assertEquals(displayed.posterUrl, favorite.posterUrl)
        assertEquals(2_000, TvBoxStore(context).state(sourceId).getJSONObject("items").length())
        assertEquals(listOf(displayed.id), newRepository().favorites(sourceId).map { it.id })
        assertTrue(newRepository().favorites(otherSourceId).isEmpty())
        assertEquals(requestsBeforeFavorite, server.requestCount)

        restored.clearCache()
        assertEquals(listOf(displayed.id), newRepository().favorites(sourceId).map { it.id })
        assertEquals(1, TvBoxStore(context).state(sourceId).getJSONObject("items").length())
        assertFalse(newRepository().setFavorite(displayed, false).favorite)
        assertTrue(newRepository().favorites(sourceId).isEmpty())
        assertEquals(requestsBeforeFavorite, server.requestCount)
    }

    @Test
    fun displayedMovieReferenceCannotRedirectAnIdToAnotherSourceOrRemoteMovie() {
        val (displayed, _) = loadDetail()
        val ref = requireNotNull(displayed.tvBoxRef)
        val otherSourceId = repository.sites().last().id
        val requestsBeforeInvalidReference = server.requestCount
        val wrongSource = displayed.copy(tvBoxRef = ref.copy(sourceId = otherSourceId))
        expectFailure<IllegalArgumentException> { repository.detail(wrongSource) }
        expectFailure<IllegalArgumentException> { repository.setFavorite(wrongSource, true) }
        val wrongPayload = displayed.copy(tvBoxRef = ref.copy(payloadJson = vod("different").toString()))
        expectFailure<TvBoxProtocolException> { repository.detail(wrongPayload) }
        expectFailure<TvBoxProtocolException> { repository.setFavorite(wrongPayload, true) }
        assertEquals(requestsBeforeInvalidReference, server.requestCount)
        assertTrue(repository.favorites().isEmpty())
    }

    @Test
    fun displayedMovieProgressAndRecentSurviveEvictionBeforePlaybackStarts() {
        val displayed = loadDisplayedMoviesThenEvict().first()
        val ref = requireNotNull(displayed.tvBoxRef)
        val line = "0:hls"
        val episode = tvBoxId(ref.sourceId, "episode", "${ref.remoteId}\n$line\n0")
        assertNull(repository.progress(displayed))
        val progress = repository.saveProgress(displayed, line, episode, 4_000, 30_000)
        assertEquals(progress, newRepository().progress(displayed))
        repository.recordRecent(displayed)
        assertEquals(displayed.id, newRepository().recent().first().id)
        assertEquals(2_000, TvBoxStore(context).state(ref.sourceId).getJSONObject("items").length())
        repository.clearCache()
        assertEquals(progress, newRepository().progress(displayed))
    }

    /** Real CMS pagination retains the original UI objects while overflowing the 2,000-record disk catalog. */
    private fun loadDisplayedMoviesThenEvict(): List<MovieItem> {
        val sourceId = repository.sites().first().id
        enqueue(pagePayload(total = 2_102, pageCount = 4).put("list", JSONArray().put(vod("42")).put(vod("43"))))
        val displayed = repository.categoryPage(sourceId, "17").items
        repeat(3) { index ->
            clock += 1_000L
            val records = JSONArray()
            repeat(700) { offset -> records.put(vod("new-${index * 700 + offset}")) }
            enqueue(JSONObject().put("code", 1).put("page", index + 2).put("pagecount", 4)
                .put("total", 2_102).put("limit", 700).put("list", records))
            val page = repository.categoryPage(sourceId, "17", page = index + 2)
            assertEquals(700, page.items.size)
            assertEquals((index + 3).takeIf { index < 2 }, page.nextPage)
        }
        val cachedItems = TvBoxStore(context).state(sourceId).getJSONObject("items")
        assertEquals(2_000, cachedItems.length())
        displayed.forEach { assertFalse("the original displayed movie must actually be evicted", cachedItems.has(it.id.toString())) }
        return displayed
    }

    private fun newRepository() = TvBoxRepository(context, client) { clock }

    private fun configuration(): String = JSONObject().put("sites", JSONArray()
        .put(JSONObject().put("key", "first").put("name", "First").put("type", 1).put("api", server.url("/first/api")))
        .put(JSONObject().put("key", "second").put("name", "Second").put("type", 1).put("api", server.url("/second/api"))))
        .toString()

    private fun enqueue(payload: JSONObject) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(payload.toString()))
    }

    private fun catalogPayload(total: Int): JSONObject = pagePayload(total, 43).put("class", JSONArray()
        .put(JSONObject().put("type_id", 17).put("type_name", "Movies"))
        .put(JSONObject().put("type_id", "904").put("type_name", "Series"))
        .put(JSONObject().put("type_id", "anime-special").put("type_name", "Anime"))
        .put(JSONObject().put("type_id", "").put("type_name", "No id"))
        .put(JSONObject().put("type_id", "904").put("type_name", "Duplicate")))

    private fun pagePayload(total: Int = 1, pageCount: Int = 1, page: Int = 1, id: String = "42"): JSONObject =
        JSONObject().put("code", 1).put("page", page).put("pagecount", pageCount).put("total", total)
            .put("limit", 20).put("list", JSONArray().put(vod(id)))

    private fun vod(id: String): JSONObject = JSONObject().put("vod_id", id).put("vod_name", "Movie $id")
        .put("vod_pic", "https://images.test/$id.jpg").put("vod_year", "2026").put("vod_content", "<p>Overview</p>")
        .put("vod_play_from", "hls").put("vod_play_url", "First\$https://video.test/$id.m3u8")

    private fun loadDetail(playUrl: String? = null): Pair<MovieItem, TvBoxDetail> {
        val source = repository.sites().first()
        enqueue(pagePayload())
        val movie = repository.categoryPage(source.id, null).items.single()
        val detail = vod("42").put("vod_play_from", if (playUrl == null) "hls\$\$\$backup" else "hls")
            .put("vod_play_url", playUrl ?: "One\$https://video.test/one.m3u8#Two\$https://video.test/two.m3u8?token=a\$b\$\$\$Full\$https://video.test/full.mp4")
        enqueue(JSONObject().put("list", JSONArray().put(detail)))
        return movie to repository.detail(movie.id)
    }

    private inline fun <reified T : Throwable> expectFailure(action: () -> Unit): T {
        try {
            action()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
    }
}
