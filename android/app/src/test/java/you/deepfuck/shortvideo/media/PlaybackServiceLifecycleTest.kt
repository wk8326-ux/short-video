package you.deepfuck.shortvideo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import you.deepfuck.shortvideo.data.MediaEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceLifecycleTest {
    @Test
    fun sessionIsRegisteredForTheWholeServiceLifecycle() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()

        assertEquals(1, service.sessions.size)

        controller.destroy()
        assertEquals(0, service.sessions.size)
    }

    @Test
    fun asmrQueueExposesPreviousAndNextToTheMediaSession() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val engine = PlaybackEngineProvider.get(service)
        val entries = (1L..3L).map(::media)

        engine.stop()
        assertTrue(
            engine.play(
                entry = entries[1],
                resumePositionMs = 0L,
                autoPlay = false,
                queue = entries,
            ),
        )

        val sessionPlayer = service.sessions.single().player
        assertEquals(3, sessionPlayer.mediaItemCount)
        assertEquals("2", sessionPlayer.currentMediaItem?.mediaId)
        assertTrue(sessionPlayer.hasPreviousMediaItem())
        assertTrue(sessionPlayer.hasNextMediaItem())

        sessionPlayer.seekToPrevious()
        assertEquals("1", sessionPlayer.currentMediaItem?.mediaId)
        assertEquals(1L, engine.currentMedia()?.id)

        sessionPlayer.seekToNextMediaItem()
        assertEquals("2", sessionPlayer.currentMediaItem?.mediaId)
        sessionPlayer.seekToNextMediaItem()
        assertEquals("3", sessionPlayer.currentMediaItem?.mediaId)
        assertEquals(3L, engine.currentMedia()?.id)

        engine.stop()
        controller.destroy()
    }

    private fun media(id: Long) = MediaEntry(
        id = id,
        title = "video-$id",
        size = 1L,
        modified = null,
        durationSeconds = null,
        playUrl = "/api/videos/$id/play",
        posterUrl = null,
        author = "author",
        format = "mp4",
        kind = "video",
    )
}
