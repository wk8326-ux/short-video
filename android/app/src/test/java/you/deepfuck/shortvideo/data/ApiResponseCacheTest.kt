package you.deepfuck.shortvideo.data

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiResponseCacheTest {
    @Test
    fun freshEntrySurvivesAndExpiresIntoStaleWindow() {
        val directory = Files.createTempDirectory("api-cache-test").toFile()
        val cache = ApiResponseCache(directory)
        val payload = JSONObject().put("items", 3)
        cache.put("/api/search?q=x", "account-a", payload, nowMs = 1_000L)

        assertEquals(3, cache.fresh("/api/search?q=x", "account-a", 100L, nowMs = 1_050L)?.getInt("items"))
        assertNull(cache.fresh("/api/search?q=x", "account-a", 100L, nowMs = 1_101L))
        assertEquals(3, cache.stale("/api/search?q=x", "account-a", 10_000L, nowMs = 1_101L)?.getInt("items"))
        assertNull(cache.fresh("/api/search?q=x", "account-b", 10_000L, nowMs = 1_050L))
    }

    @Test
    fun invalidationOnlyRemovesMatchingAccountAndUrl() {
        val directory = Files.createTempDirectory("api-cache-test").toFile()
        val cache = ApiResponseCache(directory)
        cache.put("/api/search?q=x", "account-a", JSONObject().put("v", 1), nowMs = 1L)
        cache.put("/api/movies", "account-a", JSONObject().put("v", 2), nowMs = 1L)
        cache.put("/api/search?q=x", "account-b", JSONObject().put("v", 3), nowMs = 1L)

        cache.invalidate("account-a") { it.startsWith("/api/search") }

        assertNull(cache.fresh("/api/search?q=x", "account-a", 10_000L, nowMs = 2L))
        assertEquals(2, cache.fresh("/api/movies", "account-a", 10_000L, nowMs = 2L)?.getInt("v"))
        assertEquals(3, cache.fresh("/api/search?q=x", "account-b", 10_000L, nowMs = 2L)?.getInt("v"))
    }
}
