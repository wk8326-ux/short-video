package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedPlayUrlCacheTest {
    @Test
    fun `resolved URL is reused until it expires`() {
        var nowMs = 1_000L
        var resolutions = 0
        val cache = ResolvedPlayUrlCache(ttlMs = 120_000L, clock = { nowMs })

        val first = cache.resolve("media:42") { "https://cdn.example/first-${++resolutions}" }
        nowMs += 119_999L
        val cached = cache.resolve("media:42") { "https://cdn.example/second-${++resolutions}" }
        nowMs += 2L
        val refreshed = cache.resolve("media:42") { "https://cdn.example/third-${++resolutions}" }

        assertEquals(first, cached)
        assertEquals("https://cdn.example/third-2", refreshed)
        assertEquals(2, resolutions)
    }

    @Test
    fun `invalidating a resolved URL forces the next resolution`() {
        var resolutions = 0
        val cache = ResolvedPlayUrlCache(ttlMs = 120_000L)

        cache.resolve("media:7") { "https://cdn.example/${++resolutions}" }
        cache.invalidate("media:7")
        val refreshed = cache.resolve("media:7") { "https://cdn.example/${++resolutions}" }

        assertEquals("https://cdn.example/2", refreshed)
        assertEquals(2, resolutions)
    }
}
