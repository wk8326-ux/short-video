package you.deepfuck.shortvideo

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.data.TvBoxRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvBoxMovieViewModelTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var repository: TvBoxRepository
    private var viewModelStore: ViewModelStore? = null
    private var viewModel: TvBoxMovieViewModel? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "tvbox-v1").deleteRecursively()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        firstCatalogMayContinue.countDown()
        viewModel?.playback?.setOnPlaybackEndedListener(null)
        viewModel?.playback?.setOnMediaTransitionListener(null)
        viewModel?.playback?.stop()
        viewModelStore?.clear()
        server.shutdown()
        File(context.filesDir, "tvbox-v1").deleteRecursively()
    }

    @Test
    fun standaloneCatalogLoadsWithoutBackendSessionOrBackendRequests() {
        installConfig("first")
        server.dispatcher = cmsDispatcher(mapOf("first" to "Movie from CMS"))

        val model = createViewModel()
        awaitState("the CMS category preview loads") { state ->
            !state.refreshing && state.catalog.movieGroups.singleOrNull()?.items?.any { it.title == "Movie from CMS" } == true
        }

        assertEquals(AuthenticationState.SIGNED_IN, model.state.value.catalog.authentication)
        assertNull(PlaybackPreferences(context).sessionCookie)
        val requests = listOf(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)),
            requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)))
        assertTrue(requests.all { it.getHeader("Cookie") == null })
        assertTrue(requests.all { it.requestUrl?.encodedPath == "/first/api" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun delayedOldSourceCatalogCannotReplaceTheSelectedSource() {
        installConfig("first", "second")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val source = request.requestUrl?.encodedPath?.removePrefix("/")?.substringBefore("/")
                    ?: return MockResponse().setResponseCode(404)
                if (source == "first" && request.requestUrl?.queryParameter("ac") == "list") {
                    firstCatalogMayContinue.await(8, TimeUnit.SECONDS)
                }
                return cmsResponse(source, mapOf("first" to "Old source movie", "second" to "Current source movie"))
            }
        }

        val model = createViewModel()
        awaitState("the initial catalog request is loading") { it.refreshing }
        val initialRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("initial request must use first source", "/first/api", initialRequest?.requestUrl?.encodedPath)
        val secondSourceId = model.state.value.catalog.movieSources.single { it.name == "second" }.id
        model.selectSource(secondSourceId)

        awaitState("the selected source preview loads") { state ->
            state.catalog.selectedMovieSourceId == secondSourceId &&
                state.catalog.movieGroups.singleOrNull()?.items?.any { it.title == "Current source movie" } == true
        }
        firstCatalogMayContinue.countDown()
        awaitState("both source requests settle") { !it.refreshing }

        val state = model.state.value
        assertEquals(secondSourceId, state.catalog.selectedMovieSourceId)
        assertEquals(listOf("$secondSourceId/films"), state.catalog.movieGroups.map { it.sourceId })
        assertEquals("Current source movie", state.catalog.movieGroups.single().items.single().title)
        assertTrue("all requested content should come directly from the configured CMS",
            generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.all { it.getHeader("Cookie") == null })
    }

    private val firstCatalogMayContinue = CountDownLatch(1)

    @Test
    fun playbackRetryReloadsTheSameEpisodeWithFreshUrl() {
        installConfig("first")
        val detailRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = cmsResponse("first", mapOf("first" to "Movie"))
                val token = if (request.requestUrl?.queryParameter("ids") != null) detailRequests.incrementAndGet() else 0
                val payload = JSONObject(response.getBody()!!.readUtf8())
                payload.getJSONArray("list").getJSONObject(0)
                    .put("vod_play_url", "Episode\$https://video.test/movie.m3u8?token=$token")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(payload.toString())
            }
        }
        val model = createViewModel()
        awaitState("catalog") { !it.refreshing && it.catalog.movieGroups.any { group -> group.items.isNotEmpty() } }
        model.selectMovie(model.state.value.catalog.movieGroups.first().items.first())
        awaitState("detail") { it.detail != null }
        model.play()
        awaitState("initial playback") { it.catalog.nowPlaying?.playUrl?.endsWith("token=1") == true }
        val episodeId = model.state.value.catalog.nowPlaying!!.id

        model.retryPlayback()
        awaitState("fresh playback URL") { it.catalog.nowPlaying?.playUrl?.endsWith("token=2") == true }

        assertEquals(2, detailRequests.get())
        assertEquals(episodeId, model.state.value.catalog.nowPlaying!!.id)
    }

    private fun installConfig(vararg sources: String) {
        val sites = JSONArray().apply {
            sources.forEach { source ->
                put(JSONObject().put("key", source).put("name", source).put("type", 1)
                    .put("api", server.url("/$source/api").toString()))
            }
        }
        repository = TvBoxRepository(context, okhttp3.OkHttpClient())
        repository.importConfig(JSONObject().put("sites", sites).toString())
        assertEquals(sources.toList(), repository.sites().map { it.name })
    }

    private fun cmsDispatcher(titles: Map<String, String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val source = request.requestUrl?.encodedPath?.removePrefix("/")?.substringBefore("/")
                ?: return MockResponse().setResponseCode(404)
            return cmsResponse(source, titles)
        }
    }

    private fun cmsResponse(source: String, titles: Map<String, String>): MockResponse {
        val title = titles[source] ?: return MockResponse().setResponseCode(404)
        val payload = if (source.isNotEmpty()) {
            JSONObject().put("code", 1).put("total", 1).put("page", 1).put("pagecount", 1).put("limit", 20)
                .put("class", JSONArray().put(JSONObject().put("type_id", "films").put("type_name", "Films")))
                .put("list", JSONArray().put(JSONObject().put("vod_id", "movie")
                    .put("vod_name", title).put("vod_pic", "https://images.test/movie.jpg")
                    .put("vod_play_from", "direct").put("vod_play_url", "Episode\$https://video.test/movie.mp4")))
        } else JSONObject()
        return MockResponse().setHeader("Content-Type", "application/json").setBody(payload.toString())
    }

    private fun createViewModel(): TvBoxMovieViewModel {
        val store = ViewModelStore()
        viewModelStore = store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TvBoxMovieViewModel(context as android.app.Application, repository) as T
        }
        val model = ViewModelProvider(store, factory)
            .get(TvBoxMovieViewModel::class.java)
        viewModel = model
        return model
    }

    private fun awaitState(description: String, predicate: (TvBoxUiState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel?.state?.value?.let(predicate) == true) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description; last state=${viewModel?.state?.value}")
    }
}
