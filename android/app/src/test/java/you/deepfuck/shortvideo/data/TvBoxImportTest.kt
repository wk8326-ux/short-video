package you.deepfuck.shortvideo.data

import java.io.File
import java.util.concurrent.Executors
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvBoxImportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TvBoxRepository
    @Before fun setup() {
        File(context.filesDir, "tvbox-v1").deleteRecursively()
        repository = TvBoxRepository(context)
    }
    @After fun cleanup() { File(context.filesDir, "tvbox-v1").deleteRecursively() }

    private fun config(key: String, api: String = "https://$key.test/api", name: String = key) =
        JSONObject().put("sites", JSONArray().put(JSONObject().put("key", key).put("name", name)
            .put("type", 1).put("api", api))).toString()

    @Test fun importAddsToBundledSourcesAndRepeatedImportPreservesUserState() {
        val bundled = repository.sites()
        val added = repository.importConfig(config("one")).sites.last()
        repository.setSourceEnabled(added.id, false)
        repository.moveSource(added.id, -100)
        repository.saveSettings(TvBoxSettings(selectedSourceId = bundled.first().id))
        val order = repository.sites().map { it.id }
        repeat(3) { repository.importConfig(config("one", name = "Renamed")) }
        assertEquals(bundled.size + 1, repository.sites().size)
        assertEquals(order, repository.sites().map { it.id })
        assertFalse(repository.sites().first().enabled)
        assertEquals("Renamed", repository.sites().first().name)
        assertEquals(bundled.first().id, repository.settings().selectedSourceId)
        assertEquals(repository.config(), TvBoxRepository(context).config())
    }

    @Test fun subscriptionsMergeWithoutRemovingTextSitesAndRefreshRespectsDeletion() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(config("one")))
            repository.importConfig(server.url("/one.json").toString())
            server.enqueue(MockResponse().setBody(config("two")))
            repository.importConfig(server.url("/two.json").toString())
            repository.importConfig(config("manual"))
            val deleted = repository.sites().first { it.key == "one" }
            repository.removeSource(deleted.id)
            val before = repository.sites().map { it.id }
            server.enqueue(MockResponse().setBody(config("one", name = "One refreshed")))
            server.enqueue(MockResponse().setBody(config("two", name = "Two refreshed")))
            val refreshed = repository.refreshConfig()
            assertEquals(before, refreshed.sites.map { it.id })
            assertTrue(refreshed.sites.any { it.key == "manual" })
            assertTrue(refreshed.sites.none { it.id == deleted.id })
            assertEquals("Two refreshed", refreshed.sites.first { it.key == "two" }.name)
            server.enqueue(MockResponse().setBody(config("one")))
            assertTrue(repository.importConfig(server.url("/one.json").toString()).sites.any { it.id == deleted.id })
        }
    }

    @Test fun importsFromSeparateRepositoriesCannotLoseEachOthersSites() {
        val before = repository.sites().size
        val other = TvBoxRepository(context)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { repeat(10) { repository.importConfig(config("one")) } }
            val second = executor.submit { repeat(10) { other.importConfig(config("two")) } }
            first.get(); second.get()
            assertEquals(before + 2, TvBoxRepository(context).sites().size)
        } finally { executor.shutdownNow() }
    }

    @Test fun differentSubscriptionsCanReuseKeyAndSameEndpointAliasesKeepMovieIdentity() {
        repository.importConfig(config("same", "https://one.test/api"))
        repository.importConfig(config("same", "https://two.test/api"))
        val first = repository.sites().first { it.api == "https://one.test/api" }
        val size = repository.sites().size
        repository.importConfig(config("alias", "https://one.test/api"))
        assertEquals(size, repository.sites().size)
        assertEquals(first.id, repository.sites().first { it.api == first.api }.id)
        assertEquals(repository.config(), TvBoxRepository(context).config())
    }
}
