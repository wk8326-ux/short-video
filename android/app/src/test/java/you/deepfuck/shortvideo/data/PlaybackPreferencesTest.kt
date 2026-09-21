package you.deepfuck.shortvideo.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.FeedSessionState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPreferencesTest {
    @Test
    fun muteStateIsIsolatedByMediaSurfaceAndMigratesTheLegacyFeedValue() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("muted", true)
            .commit()
        val preferences = PlaybackPreferences(context)

        assertTrue(preferences.muted(MediaSurface.SHORT))
        assertTrue(preferences.muted(MediaSurface.LONG))
        assertFalse(preferences.muted(MediaSurface.ASMR))
        assertFalse(preferences.muted(MediaSurface.MOVIE))

        preferences.setMuted(MediaSurface.ASMR, true)
        preferences.setMuted(MediaSurface.SHORT, false)

        assertFalse(preferences.muted(MediaSurface.SHORT))
        assertTrue(preferences.muted(MediaSurface.LONG))
        assertTrue(preferences.muted(MediaSurface.ASMR))
        assertFalse(preferences.muted(MediaSurface.MOVIE))
    }

    @Test
    fun randomFeedRequestContextSurvivesProcessRestart() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PlaybackPreferences(context)
        val session = FeedSessionState(
            items = listOf(media(1L), media(2L)),
            activeMediaId = 2L,
            nextCursor = "cursor-2",
            total = 762,
            excludedIds = listOf(40L, 41L),
            startId = 2L,
        )

        preferences.saveFeedSession(MediaSurface.SHORT, FeedMode.SHUFFLE, session)
        val restored = PlaybackPreferences(context).feedSession(MediaSurface.SHORT, FeedMode.SHUFFLE)

        assertEquals("cursor-2", restored?.nextCursor)
        assertEquals(762, restored?.total)
        assertEquals(listOf(40L, 41L), restored?.excludedIds)
        assertEquals(2L, restored?.startId)
    }

    @Test
    fun asmrAuthorIndexCanBeInvalidatedAfterLibraryScan() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PlaybackPreferences(context)
        preferences.saveAsmrAuthorIndex(
            listOf(AsmrAuthor("old-author", 2, 1, 1, null)),
            total = 1,
        )

        preferences.clearAsmrAuthorIndex()

        assertNull(PlaybackPreferences(context).asmrAuthorIndex())
    }

    @Test
    fun movieSurfaceAndGridPositionSurviveProcessRestart() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PlaybackPreferences(context)

        preferences.surface = MediaSurface.MOVIE
        preferences.saveMovieListPosition(you.deepfuck.shortvideo.ListPosition(27, 14))

        val restored = PlaybackPreferences(context)
        assertEquals(MediaSurface.MOVIE, restored.surface)
        assertEquals(you.deepfuck.shortvideo.ListPosition(27, 14), restored.movieListPosition())
    }

    @Test
    fun eachLibraryKeepsItsOwnGridPositionAndSort() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("short_video", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PlaybackPreferences(context)

        preferences.saveMovieGroupListPosition("source-julia", you.deepfuck.shortvideo.ListPosition(9, 3))
        preferences.saveMovieGroupSort("source-julia", "title")

        val restored = PlaybackPreferences(context)
        assertEquals(
            you.deepfuck.shortvideo.ListPosition(9, 3),
            restored.movieGroupListPosition("source-julia"),
        )
        assertEquals("title", restored.movieGroupSort("source-julia"))
        // A library nobody has opened yet starts at the top with no sort saved.
        assertEquals(you.deepfuck.shortvideo.ListPosition(0, 0), restored.movieGroupListPosition("source-new"))
        assertEquals("", restored.movieGroupSort("source-new"))
    }

    private fun media(id: Long) = MediaEntry(
        id = id,
        title = "video-$id",
        size = 1L,
        modified = null,
        durationSeconds = 10.0,
        playUrl = "/video/$id",
        posterUrl = null,
    )
}
