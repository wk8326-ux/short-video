package you.deepfuck.shortvideo.data

import android.content.Context
import org.junit.Assert.assertEquals
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
