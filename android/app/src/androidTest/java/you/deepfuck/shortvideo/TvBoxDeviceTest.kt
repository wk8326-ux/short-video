@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package you.deepfuck.shortvideo

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import you.deepfuck.shortvideo.data.TvBoxRepository

/** Runs with an external test CMS supplied by the test invocation, never our application backend. */
@RunWith(AndroidJUnit4::class)
class TvBoxDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test
    fun bundledSourcesBrowseAndRenderVideoDirectly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicSites") == "true")
        val repository = TvBoxRepository(app)
        val onlySites = InstrumentationRegistry.getArguments().getString("siteKeys")?.split(',')
        val sites = repository.useBundledConfig().sites.filter { onlySites == null || it.key in onlySites }
        val failures = mutableListOf<String>()
        ActivityScenario.launch(TvBoxActivity::class.java).use { scenario ->
            lateinit var model: TvBoxMovieViewModel
            scenario.onActivity { model = ViewModelProvider(it)[TvBoxMovieViewModel::class.java] }
            await("initial sources") { model.state.value.sites.isNotEmpty() }
            sites.forEach { site ->
                try {
                    onMain { model.selectSource(site.id) }
                    await("${site.name} catalog", 45_000) {
                        val state = model.state.value.catalog
                        state.movieGroups.any { it.items.isNotEmpty() } || (!model.state.value.refreshing && state.movieError != null)
                    }
                    val state = model.state.value.catalog
                    val movie = state.movieGroups.firstOrNull { it.items.isNotEmpty() }?.items?.first()
                        ?: error(state.movieError ?: "Empty CMS catalog")
                    onMain { model.selectMovie(movie) }
                    await("${site.name} detail", 35_000) {
                        !model.state.value.catalog.movieDetailLoading
                    }
                    check(model.state.value.detail != null) { model.state.value.catalog.movieError ?: "Missing detail" }
                    onMain { model.play() }
                    await("${site.name} rendered frames", 90_000) {
                        onMain {
                            model.playback.refreshPosition()
                            val counters = model.playback.player.videoDecoderCounters
                            counters?.ensureUpdated()
                            (counters?.renderedOutputBufferCount ?: 0) > 10 && model.playback.player.currentPosition > 1_000
                        }
                    }
                    println("TVBOX_SMOKE ${site.name}: PASS categories=${state.movieGroups.size} total=${state.movieTotal} rendered=true")
                } catch (error: Throwable) {
                    val reason = "${site.name}: ${error.message}; CMS=${model.state.value.catalog.movieError}; player=${model.playback.snapshot.value.error}"
                    failures += reason
                    println("TVBOX_SMOKE FAIL $reason")
                } finally {
                    onMain { model.closeDetail() }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun importBrowseDecodeSeekFavoriteAndClearCacheWithoutBackend() {
        val url = InstrumentationRegistry.getArguments().getString("cmsConfig")
            ?: "http://127.0.0.1:18765/config.json"
        val repository = TvBoxRepository(app)
        val original = repository.config()
        repository.importConfig(url)
        try {
            ActivityScenario.launch(TvBoxActivity::class.java).use { scenario ->
                lateinit var model: TvBoxMovieViewModel
                scenario.onActivity { model = ViewModelProvider(it)[TvBoxMovieViewModel::class.java] }
                await("category preview") {
                    model.state.value.catalog.movieGroups.any { it.items.isNotEmpty() }
                }
                assertEquals(1, model.state.value.catalog.movieTotal)
                assertEquals(1, model.state.value.catalog.movieSources.single().libraryCount)
                val movie = model.state.value.catalog.movieGroups.first().items.first()
                onMain { model.selectMovie(movie) }
                await("fresh detail") { model.state.value.detail != null }
                assertTrue(model.state.value.detail!!.playLines.single().episodes.isNotEmpty())
                onMain { model.play() }
                await("rendered HLS frames", 35_000) {
                    onMain {
                        model.playback.refreshPosition()
                        val counters = model.playback.player.videoDecoderCounters
                        counters?.ensureUpdated()
                        (counters?.renderedOutputBufferCount ?: 0) > 10 &&
                            model.playback.snapshot.value.positionMs > 1_000
                    }
                }
                onMain { model.playback.pause() }
                val pausedAt = onMain { model.playback.player.currentPosition }
                Thread.sleep(350)
                assertTrue(kotlin.math.abs(onMain { model.playback.player.currentPosition } - pausedAt) < 200)
                onMain { model.playback.seekTo(6_000); model.playback.togglePlayback() }
                await("seeked playback") {
                    onMain { model.playback.player.currentPosition > 6_500 && model.playback.player.isPlaying }
                }
                if (model.state.value.detail!!.movie.favorite) {
                    onMain { model.toggleFavorite(model.state.value.detail!!.movie) }
                    await("reset fixture favorite") {
                        !model.state.value.detail!!.movie.favorite && repository.favorites().none { it.id == movie.id }
                    }
                }
                onMain { model.toggleFavorite(model.state.value.detail!!.movie) }
                await("saved favorite") {
                    model.state.value.detail!!.movie.favorite && repository.favorites().any { it.id == movie.id }
                }
                onMain { model.stopPlayback() }
                await("saved progress") { (repository.progress(movie.id)?.positionMs ?: 0) > 6_000 }
                onMain { model.clearCache() }
                await("cleared and reloaded cache") { !model.state.value.importing && !model.state.value.refreshing }
                assertTrue(repository.favorites().any { it.id == movie.id })
                assertTrue((repository.progress(movie.id)?.positionMs ?: 0) > 6_000)
                assertEquals(1, model.state.value.catalog.movieTotal)
                val before = repository.config()
                onMain { model.importConfig("{broken") }
                await("invalid import feedback") { model.state.value.importError != null }
                assertEquals(before, repository.config())
            }
        } finally {
            if (original?.origin == "asset:tvbox.json") repository.useBundledConfig()
        }
    }

    private fun await(description: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out: $description")
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
}
