package you.deepfuck.shortvideo.data

import java.nio.file.Files
import java.io.IOException
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
    fun transientReadFailureRetriesOnceAndReturnsTheSuccessfulResponse() {
        var calls = 0
        val delays = mutableListOf<Long>()

        val result = withTransientReadRetry(
            sleep = { delays += it },
        ) {
            calls += 1
            if (calls == 1) throw IOException("temporary network failure")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(listOf(450L), delays)
    }

    @Test
    fun nonTransientHttpFailureIsNotReplayed() {
        var calls = 0

        try {
            withTransientReadRetry(
                sleep = { error("unexpected retry") },
            ) {
                calls += 1
                throw ApiException(404, "not found")
            }
            throw AssertionError("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.statusCode)
        }

        assertEquals(1, calls)
    }

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

    @Test
    fun clearInvalidatesAnInFlightWriterGeneration() {
        val directory = Files.createTempDirectory("api-cache-test").toFile()
        val cache = ApiResponseCache(directory)
        val generation = cache.generation("account-a")

        cache.clear("account-a")
        cache.put(
            "/api/movies",
            "account-a",
            JSONObject().put("v", "old"),
            nowMs = 1L,
            expectedGeneration = generation,
        )

        assertNull(cache.fresh("/api/movies", "account-a", 10_000L, nowMs = 2L))
    }
}
