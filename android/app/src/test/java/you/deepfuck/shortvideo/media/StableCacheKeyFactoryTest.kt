package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun signedCdnUrlsKeepChangingQueryWithoutAnExplicitTrustedScope() {
        val first = StableCacheKeyFactory.stableCacheKey(
            "https://cdn.example/path/segment.ts?sign=first&expires=1",
        )
        val second = StableCacheKeyFactory.stableCacheKey(
            "https://cdn.example/path/segment.ts?sign=second&expires=2",
        )
        assertNotEquals(first, second)
        assertEquals("url:v2:https://cdn.example/path/segment.ts?sign=first&expires=1", first)
    }

    @Test
    fun queryValuesDistinguishEpisodesVariantsSegmentsAndEncryptionKeys() {
        for (name in listOf("id", "episode", "quality", "segment", "key", "token", "sign", "expires", "refresh")) {
            assertNotEquals(
                name,
                StableCacheKeyFactory.stableCacheKey("https://cms.example/play?$name=1"),
                StableCacheKeyFactory.stableCacheKey("https://cms.example/play?$name=2"),
            )
        }
    }

    @Test
    fun backendRefreshStrippingRetainsEveryOtherQueryValue() {
        assertEquals(
            "media:42?quality=1080&token=one",
            StableCacheKeyFactory.stableCacheKey(
                "https://short.deepfuck.you/api/videos/42/play?quality=1080&refresh=true&token=one#player",
            ),
        )
        assertNotEquals(
            StableCacheKeyFactory.stableCacheKey("https://short.deepfuck.you/api/videos/42/play?token=one"),
            StableCacheKeyFactory.stableCacheKey("https://short.deepfuck.you/api/videos/42/play?token=two"),
        )
        for (url in listOf(
                "http://short.deepfuck.you/api/videos/42/play",
                "https://short.deepfuck.you:8443/api/videos/42/play",
                "https://short.deepfuck.you.other.example/api/videos/42/play",
                "https://cms.example/api/videos/42/play",
            )
        ) {
            assertNotEquals(
                StableCacheKeyFactory.stableCacheKey("$url?refresh=one"),
                StableCacheKeyFactory.stableCacheKey("$url?refresh=two"),
            )
        }
    }

    @Test
    fun encodedPathsQueryOrderAndDuplicateValuesRemainDistinct() {
        for ((first, second) in listOf(
                "https://cdn.example/a%2Fb.ts" to "https://cdn.example/a/b.ts",
                "https://cms.example/play?id=one%26quality%3Dhigh" to "https://cms.example/play?id=one&quality=high",
                "https://cms.example/play?id=1&id=2" to "https://cms.example/play?id=2&id=1",
            )
        ) {
            assertNotEquals(
                StableCacheKeyFactory.stableCacheKey(first),
                StableCacheKeyFactory.stableCacheKey(second),
            )
        }
    }

    @Test
    fun invalidUrlsKeepQueryIdentityAndOnlyDiscardFragments() {
        assertEquals(
            "url:v2:https://cms.example/a b?id=1",
            StableCacheKeyFactory.stableCacheKey("https://cms.example/a b?id=1#player"),
        )
        assertNotEquals(
            StableCacheKeyFactory.stableCacheKey("https://cms.example/a b?id=1"),
            StableCacheKeyFactory.stableCacheKey("https://cms.example/a b?id=2"),
        )
        assertEquals(
            StableCacheKeyFactory.stableCacheKey("https://cdn.example/a.ts?id=1#first"),
            StableCacheKeyFactory.stableCacheKey("https://cdn.example/a.ts?id=1#second"),
        )
    }
}
