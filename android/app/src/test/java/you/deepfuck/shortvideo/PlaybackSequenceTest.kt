package you.deepfuck.shortvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.media.PlaybackEndedEvent
import you.deepfuck.shortvideo.media.PlaybackIdentity

class PlaybackSequenceTest {
    @Test
    fun feedAdvancesAndWrapsAtTheEnd() {
        assertEquals(1, nextFeedIndex(currentIndex = 0, itemCount = 3))
        assertEquals(0, nextFeedIndex(currentIndex = 2, itemCount = 3))
        assertNull(nextFeedIndex(currentIndex = 0, itemCount = 0))
    }

    @Test
    fun audioSequenceSkipsVideosAndStopsAfterTheLastAudio() {
        val first = media(id = 1, kind = "audio")
        val video = media(id = 2, kind = "video")
        val second = media(id = 3, kind = "audio")
        val items = listOf(first, video, second)

        assertEquals(second, nextAudioEntry(items, afterId = first.id))
        assertNull(nextAudioEntry(items, afterId = second.id))
    }

    @Test
    fun feedPlayerOnlyAttachesToTheActiveMatchingMedia() {
        assertTrue(shouldAttachFeedPlayer(active = true, entryId = 2L, mediaId = 2L))
        assertFalse(shouldAttachFeedPlayer(active = true, entryId = 2L, mediaId = 1L))
        assertFalse(shouldAttachFeedPlayer(active = false, entryId = 2L, mediaId = 2L))
    }

    @Test
    fun asmrPagingWaitsUntilTheUserApproachesTheEnd() {
        assertFalse(shouldLoadMore(lastVisibleIndex = 8, itemCount = 24, loading = false, hasMore = true))
        assertTrue(shouldLoadMore(lastVisibleIndex = 18, itemCount = 24, loading = false, hasMore = true))
        assertFalse(shouldLoadMore(lastVisibleIndex = 23, itemCount = 24, loading = true, hasMore = true))
        assertFalse(shouldLoadMore(lastVisibleIndex = 23, itemCount = 24, loading = false, hasMore = false))
        assertFalse(shouldLoadMore(lastVisibleIndex = -1, itemCount = 0, loading = false, hasMore = true))
    }

    @Test
    fun staleFeedCallbacksCannotRestartPlaybackInsideAsmr() {
        assertTrue(shouldActivateFeedItem(MediaSurface.SHORT))
        assertTrue(shouldActivateFeedItem(MediaSurface.LONG))
        assertFalse(shouldActivateFeedItem(MediaSurface.ASMR))
    }

    @Test
    fun onlyTheCurrentPlaybackGenerationCanAdvanceAfterPlaybackEnds() {
        val current = PlaybackEndedEvent(mediaId = 7L, generation = 4L)
        val stale = PlaybackEndedEvent(mediaId = 7L, generation = 3L)

        assertTrue(
            shouldHandlePlaybackEnded(
                event = current,
                engineMediaId = 7L,
                engineGeneration = 4L,
                stateMediaId = 7L,
            ),
        )
        assertFalse(
            shouldHandlePlaybackEnded(
                event = stale,
                engineMediaId = 7L,
                engineGeneration = 4L,
                stateMediaId = 7L,
            ),
        )
        assertFalse(
            shouldHandlePlaybackEnded(
                event = current,
                engineMediaId = null,
                engineGeneration = 4L,
                stateMediaId = 7L,
            ),
        )
        assertFalse(
            shouldHandlePlaybackEnded(
                event = current,
                engineMediaId = 7L,
                engineGeneration = 5L,
                stateMediaId = 7L,
            ),
        )
    }

    @Test
    fun playbackIdentityIsInvalidBeforeStopCanDispatchCallbacks() {
        val identity = PlaybackIdentity()
        identity.activate(mediaId = 7L)
        var eventDuringStop: PlaybackEndedEvent? = null

        identity.invalidateThen {
            eventDuringStop = identity.endedEvent()
        }

        assertNull(eventDuringStop)
        assertEquals(2L, identity.generation)
    }

    @Test
    fun feedPrefetchStartsAfterTheActiveItem() {
        val items = (1L..5L).map { media(id = it, kind = "video") }

        assertEquals(listOf(items[2], items[3]), feedPrefetchCandidates(items, activeIndex = 1))
        assertEquals(listOf(items[4]), feedPrefetchCandidates(items, activeIndex = 3))
        assertTrue(feedPrefetchCandidates(items, activeIndex = 4).isEmpty())
    }

    @Test
    fun asmrAudioAlwaysKeepsPlayingWhileVideoRemainsOptIn() {
        val audio = media(id = 1L, kind = "audio")
        val video = media(id = 2L, kind = "video")

        assertFalse(shouldKeepPlayingInBackground(MediaSurface.SHORT, video, videoEnabled = true))
        assertTrue(shouldKeepPlayingInBackground(MediaSurface.ASMR, audio, videoEnabled = false))
        assertTrue(shouldKeepPlayingInBackground(MediaSurface.ASMR, video, videoEnabled = true))
        assertFalse(shouldKeepPlayingInBackground(MediaSurface.ASMR, video, videoEnabled = false))
        assertFalse(shouldKeepPlayingInBackground(MediaSurface.ASMR, null, videoEnabled = true))
    }

    private fun media(id: Long, kind: String) = MediaEntry(
        id = id,
        title = "media-$id",
        size = 1L,
        modified = null,
        durationSeconds = null,
        playUrl = "/api/videos/$id/play",
        posterUrl = null,
        author = "Author",
        format = if (kind == "audio") "mp3" else "mp4",
        kind = kind,
    )
}
