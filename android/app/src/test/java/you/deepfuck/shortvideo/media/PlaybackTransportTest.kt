@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package you.deepfuck.shortvideo.media

import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.data.MediaEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlaybackTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = playbackHttpClient({ "short_session=private" })

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun directHttpHlsLoadsRedirectedMasterNestedPlaylistKeyAndByteRange() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/redirected/master.m3u8?token=master"))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            ../nested/index.m3u8?episode=7&token=variant
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="../key?slot=2&token=key",IV=0x00000000000000000000000000000001
            #EXTINF:4,
            #EXT-X-BYTERANGE:4@2
            ../media?id=7&part=1
            #EXT-X-ENDLIST
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("0123456789abcdef"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/10").setBody("2345"))

        val master = playlist(server.url("/master.m3u8?token=original").toString()) as HlsMultivariantPlaylist
        val nested = playlist(master.variants.single().url.toString()) as HlsMediaPlaylist
        val segment = nested.segments.single()
        assertEquals("0123456789abcdef", read(DataSpec(UriUtil.resolveToUri(nested.baseUri, segment.fullSegmentEncryptionKeyUri))).decodeToString())
        val segmentSpec = DataSpec.Builder()
            .setUri(UriUtil.resolveToUri(nested.baseUri, segment.url))
            .setPosition(segment.byteRangeOffset)
            .setLength(segment.byteRangeLength)
            .build()
        assertEquals("2345", read(segmentSpec).decodeToString())

        val paths = listOf(
            "/master.m3u8?token=original", "/redirected/master.m3u8?token=master",
            "/nested/index.m3u8?episode=7&token=variant", "/key?slot=2&token=key", "/media?id=7&part=1",
        )
        paths.forEachIndexed { index, path ->
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(path, request.path)
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            if (index == paths.lastIndex) assertEquals("bytes=2-5", request.getHeader("Range"))
        }
    }

    @Test
    fun backendCookieIsReevaluatedOnRedirectAndNeverSentToAnotherHost() {
        val external = MockWebServer().apply { start() }
        try {
            val otherHostUrl = external.url("/segment.ts").newBuilder().host("127.0.0.1").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherHostUrl))
            external.enqueue(MockResponse().setBody("data"))
            var cookie = "short_session=first"
            val scopedClient = playbackHttpClient({ cookie }, server.url("/"))
            val spec = DataSpec(server.url("/api/playback/token").toString().let(Uri::parse))

            assertEquals("data", read(spec, OkHttpDataSource.Factory(scopedClient).createDataSource()).decodeToString())
            assertEquals(cookie, server.takeRequest().getHeader("Cookie"))
            assertNull(external.takeRequest().getHeader("Cookie"))

            cookie = "short_session=second"
            server.enqueue(MockResponse().setBody("next"))
            read(spec, OkHttpDataSource.Factory(scopedClient).createDataSource())
            assertEquals(cookie, server.takeRequest().getHeader("Cookie"))
        } finally {
            external.shutdown()
        }
    }

    @Test
    fun backendCookieIsNotSentToAnotherPortOnTheSameHost() {
        val external = MockWebServer().apply { start() }
        try {
            external.enqueue(MockResponse().setBody("external"))
            val scopedClient = playbackHttpClient({ "short_session=private" }, server.url("/"))
            read(DataSpec(Uri.parse(external.url("/media").toString())), OkHttpDataSource.Factory(scopedClient).createDataSource())
            assertNull(external.takeRequest().getHeader("Cookie"))
        } finally {
            external.shutdown()
        }
    }

    @Test
    fun rangedReadsSkipThePrefixWhenTheOriginReturns200() {
        server.enqueue(MockResponse().setBody("0123456789"))
        val spec = DataSpec.Builder().setUri(server.url("/movie.mp4").toString())
            .setPosition(3L).setLength(4L).build()
        assertEquals("3456", read(spec).decodeToString())
        assertEquals("bytes=3-6", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun rangeAtEndOfResourceIsEofAndHttpErrorsKeepTheirResponseCodes() {
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */10"))
        val spec = DataSpec.Builder().setUri(server.url("/movie.mp4").toString()).setPosition(10L).build()
        assertArrayEquals(byteArrayOf(), read(spec))
        server.enqueue(MockResponse().setResponseCode(403))
        val error = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            read(spec)
        }
        assertEquals(403, error.responseCode)
    }

    @Test
    @Suppress("DEPRECATION")
    fun cachedProgressiveEntriesUseUrlIdentityInsteadOfCollidingNumericIds() {
        val cache = SimpleCache(
            temporaryFolder.newFolder("media-cache"), LeastRecentlyUsedCacheEvictor(1024 * 1024),
        )
        try {
            val factory = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(StableCacheKeyFactory())
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client))
            val first = media(server.url("/play?id=one&token=first").toString())
            val second = first.copy(playUrl = server.url("/play?id=two&token=first").toString())
            server.enqueue(MockResponse().setBody("one"))
            server.enqueue(MockResponse().setBody("two"))

            assertEquals("one", read(playbackDataSpec(first), factory.createDataSource()).decodeToString())
            assertEquals("two", read(playbackDataSpec(second), factory.createDataSource()).decodeToString())
            assertEquals("one", read(playbackDataSpec(first), factory.createDataSource()).decodeToString())
            assertEquals(2, server.requestCount)
            val key = StableCacheKeyFactory().buildCacheKey(playbackDataSpec(first))
            assertEquals(3L, cache.getCachedBytes(key, 0L, 3L))
            assertEquals(0L, cache.getCachedBytes("media:${first.id}", 0L, 3L))
        } finally {
            cache.release()
        }
    }

    @Test
    fun explicitCacheKeysRemainSupportedWhilePrefetchUsesThePlaybackUrlKey() {
        val entry = media("https://cms.example/play?id=one")
        val factory = StableCacheKeyFactory()
        assertEquals(factory.buildCacheKey(DataSpec(Uri.parse(entry.playUrl))), factory.buildCacheKey(playbackDataSpec(entry)))
        assertNotEquals(
            factory.buildCacheKey(playbackDataSpec(entry)),
            factory.buildCacheKey(playbackDataSpec(entry.copy(playUrl = "https://cms.example/play?id=two"))),
        )
        assertEquals("explicit", factory.buildCacheKey(playbackDataSpec(entry).buildUpon().setKey("explicit").build()))
        assertEquals("media:42", factory.buildCacheKey(playbackDataSpec(entry.copy(playUrl = "/api/videos/42/play"))))
    }

    @Test
    fun queueReuseRequiresMatchingUrlsAndMimeButAllowsUpdatedMetadata() {
        val entry = media("https://cms.example/play?id=one")
        assertTrue(playbackQueueMatches(listOf(entry), listOf(entry.copy(title = "new title"))))
        assertFalse(playbackQueueMatches(listOf(entry), listOf(entry.copy(playUrl = "https://cms.example/play?id=two"))))
        assertFalse(playbackQueueMatches(listOf(entry), listOf(entry.copy(format = "hls"))))
        assertEquals("HTTP://cms.example/movie.mp4", playbackUrl(entry.copy(playUrl = "HTTP://cms.example/movie.mp4")))
    }

    @Test
    fun manifestAllowsImportedHttpSources() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val application = document.getElementsByTagName("application").item(0)
        assertEquals("true", application.attributes.getNamedItemNS(
            "http://schemas.android.com/apk/res/android", "usesCleartextTraffic",
        ).nodeValue)
    }

    private fun playlist(url: String): HlsPlaylist {
        val source = OkHttpDataSource.Factory(client).createDataSource()
        return DataSourceInputStream(source, DataSpec(Uri.parse(url))).use {
            it.open()
            HlsPlaylistParser().parse(requireNotNull(source.uri), it)
        }
    }

    private fun read(spec: DataSpec, source: DataSource = OkHttpDataSource.Factory(client).createDataSource()): ByteArray =
        DataSourceInputStream(source, spec).use { it.readBytes() }

    private fun media(url: String) = MediaEntry(
        id = 42L, title = "movie", size = 3L, modified = null, durationSeconds = null,
        playUrl = url, posterUrl = null, format = "mp4",
    )
}
