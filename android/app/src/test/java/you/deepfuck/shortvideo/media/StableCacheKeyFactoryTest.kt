package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Test

class StableCacheKeyFactoryTest {
    @Test
    fun apiPlayUrlsUseMediaId() {
        assertEquals(
            "media:42",
            StableCacheKeyFactory.stableCacheKey(
                "https://short.deepfuck.you/api/videos/42/play?refresh=true",
            ),
        )
    }

    @Test
    fun signedCdnUrlsIgnoreChangingQuery() {
        val first = StableCacheKeyFactory.stableCacheKey(
            "https://cdn.example/path/segment.ts?sign=first&expires=1",
        )
        val second = StableCacheKeyFactory.stableCacheKey(
            "https://cdn.example/path/segment.ts?sign=second&expires=2",
        )
        assertEquals(first, second)
        assertEquals("https://cdn.example/path/segment.ts", first)
    }
}
