package you.deepfuck.shortvideo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovieModelsTest {
    @Test
    fun movieJsonParsesCatalogAndDetailFields() {
        val movie = MovieItem.fromJson(
            JSONObject(
                """
                {
                  "id": 7,
                  "videoId": 42,
                  "title": "Arrival",
                  "originalTitle": "Arrival",
                  "year": 2016,
                  "overview": "First contact.",
                  "posterUrl": "/poster.jpg",
                  "backdropUrl": "https://image.example/backdrop.jpg",
                  "wallUrl": "/api/movies/7/backdrop",
                  "rating": 7.6,
                  "runtimeMinutes": 116,
                  "matchStatus": "matched",
                  "matchConfidence": 0.94,
                  "playUrl": "/api/videos/42/play",
                  "modified": "2026-08-21T00:00:00Z",
                  "duration": 6960.0,
                  "source": "movies",
                  "path": "/Arrival.2016.mkv",
                  "size": 1234,
                  "format": "mkv"
                }
                """.trimIndent(),
            ),
        )

        assertEquals(7L, movie.id)
        assertEquals(42L, movie.videoId)
        assertEquals(2016, movie.year)
        assertEquals(7.6, movie.rating ?: 0.0, 0.001)
        assertEquals("mkv", movie.format)
        assertEquals("/api/movies/7/backdrop", movie.wallUrl)
        assertEquals(42L, movie.asMediaEntry().id)
        assertFalse(movie.asMediaEntry().isAudio)
    }

    @Test
    fun nullableMovieMetadataStaysNullable() {
        val movie = MovieItem.fromJson(
            JSONObject(
                """{"id":1,"videoId":2,"title":"Unknown","overview":"","posterUrl":null,"backdropUrl":null,"rating":null,"runtimeMinutes":null,"matchStatus":"pending","playUrl":"/api/videos/2/play","duration":null}""",
            ),
        )

        assertNull(movie.year)
        assertNull(movie.rating)
        assertNull(movie.durationSeconds)
        assertEquals(0f, movie.resumeFraction)
    }

    @Test
    fun movieSourcesKeepTheirOwnSectionAndTreeScanRule() {
        val source = MediaLibrarySource.fromJson(
            JSONObject(
                """{"id":"movie-1","name":"Movies","provider":"alist","baseUrl":"https://alist.example","rootPath":"/movies","section":"movie","scanMode":"tree","anonymous":false,"enabled":true,"videos":12,"bytes":100,"scan":{},"movieMetadata":{"total":12,"pending":3,"matched":7,"ambiguous":1,"unmatched":1,"lastSuccess":1787280000}}""",
            ),
        )

        assertEquals(LibrarySection.MOVIE, source.section)
        assertTrue(source.section.usesTreeScan)
        assertFalse(MediaSurface.MOVIE.isFeed)
        assertEquals(7, source.movieMetadata?.matched)
        assertEquals(2, source.movieMetadata?.needsReview)
        assertEquals(1787280000L, source.movieMetadata?.lastSuccess)
    }
}
