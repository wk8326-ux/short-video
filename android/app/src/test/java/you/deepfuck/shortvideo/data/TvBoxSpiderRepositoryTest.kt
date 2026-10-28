package you.deepfuck.shortvideo.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvBoxSpiderRepositoryTest {
    @Test fun opaqueTokensHeadersAndCachedCatalogsStayWithinTheirSpider() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "tvbox-v1").deleteRecursively()
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = object : TvBoxSpiderTransport {
            var closed = false
            override fun request(site: TvBoxSite, params: Map<String, String>): String {
                requests += site.id to params
                return JSONObject().put("class", JSONArray().put(JSONObject().put("type_id", "films").put("type_name", "Films")))
                    .put("page", 1).put("pagecount", 1).put("total", 1).put("list", JSONArray().put(JSONObject()
                        .put("vod_id", "same-id").put("vod_name", site.name).put("vod_play_from", "test")
                        .put("vod_play_url", "Episode\$opaque-token"))).toString()
            }
            override fun player(site: TvBoxSite, flag: String, id: String): JSONObject {
                assertEquals("test", flag)
                assertEquals("opaque-token", id)
                return JSONObject().put("parse", 0).put("url", "https://${site.key}.test/video.m3u8")
                    .put("header", JSONObject().put("Referer", "https://${site.key}.test/").toString())
            }
            override fun cancel() { }
            override fun close() { closed = true }
        }
        val repository = TvBoxRepository(context, spiders = transport)
        try {
            repository.sites().forEach { repository.removeSource(it.id) }
            repository.importConfig("""{"spider":"https://plugins.test/spider.jar","sites":[
              {"key":"one","name":"One","type":3,"api":"csp_Test","ext":"one"},
              {"key":"two","name":"Two","type":3,"api":"csp_Test","ext":"two"}]}""")
            val movies = repository.sites().map { source ->
                val catalog = repository.catalog(source.id)
                assertEquals(source.name, catalog.page.items.single().title)
                assertTrue(repository.catalog(source.id).page.fromCache)
                catalog.page.items.single()
            }
            assertNotEquals(movies[0].id, movies[1].id)
            assertEquals(2, requests.size)
            movies.forEachIndexed { index, movie ->
                val detail = repository.detail(movie)
                val line = detail.playLines.single()
                val entry = repository.resolvePlayback(detail, line.id, line.episodes.single().id)
                val key = repository.sites()[index].key
                assertEquals("https://$key.test/video.m3u8", entry.playUrl)
                assertEquals("https://$key.test/", entry.requestHeaders["Referer"])
                assertEquals(entry, MediaEntry.fromJson(entry.toJson()))
            }
        } finally {
            repository.close()
            assertTrue(transport.closed)
            File(context.filesDir, "tvbox-v1").deleteRecursively()
        }
    }
}
