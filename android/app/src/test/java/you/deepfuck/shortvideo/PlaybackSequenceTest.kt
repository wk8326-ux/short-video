package you.deepfuck.shortvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.AsmrFilter
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.LibraryScanStatus
import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.media.PlaybackEndedEvent
import you.deepfuck.shortvideo.media.PlaybackIdentity

class PlaybackSequenceTest {
    @Test
    fun `accepted source scan is tracked until its terminal state`() {
        val previous = LibraryScanStatus(
            running = false,
            lastSuccess = 10L,
            lastError = null,
            directories = 3,
        )

        assertEquals(
            SourceScanOutcome.RUNNING,
            sourceScanOutcome(
                previous,
                previous.copy(running = true),
                scanWasAccepted = true,
                runningWasObserved = false,
            ),
        )
        assertEquals(
            SourceScanOutcome.SUCCEEDED,
            sourceScanOutcome(
                previous,
                previous.copy(lastSuccess = 11L, directories = 8),
                scanWasAccepted = true,
                runningWasObserved = true,
            ),
        )
        assertEquals(
            SourceScanOutcome.FAILED,
            sourceScanOutcome(
                previous,
                previous.copy(lastError = "AList unavailable"),
                scanWasAccepted = true,
                runningWasObserved = true,
            ),
        )
    }

    @Test
    fun `ASMR library filter applies to authors and their media`() {
        val authors = listOf(
            AsmrAuthor("Both", itemCount = 5, videoCount = 2, audioCount = 3, modified = null),
            AsmrAuthor("Video only", itemCount = 2, videoCount = 2, audioCount = 0, modified = null),
            AsmrAuthor("Audio only", itemCount = 3, videoCount = 0, audioCount = 3, modified = null),
        )
        val entries = listOf(media(1L, "video"), media(2L, "audio"))

        assertEquals(
            listOf("Both", "Video only"),
            filterAsmrAuthors(authors, AsmrFilter.VIDEO, query = "").map(AsmrAuthor::name),
        )
        assertEquals(
            listOf("Both", "Audio only"),
            filterAsmrAuthors(authors, AsmrFilter.AUDIO, query = "").map(AsmrAuthor::name),
        )
        assertEquals(listOf(1L), filterAsmrEntries(entries, AsmrFilter.VIDEO, query = "").map(MediaEntry::id))
        assertEquals(listOf(2L), filterAsmrEntries(entries, AsmrFilter.AUDIO, query = "").map(MediaEntry::id))
        assertEquals(listOf("Video only"), filterAsmrAuthors(authors, AsmrFilter.ALL, "video").map(AsmrAuthor::name))
    }

    @Test
    fun `server feed total replaces a stale one-page random total`() {
        assertEquals(762, reconcileFeedTotal(remoteTotal = 762, currentTotal = 18, loadedCount = 18))
        assertEquals(18, reconcileFeedTotal(remoteTotal = 0, currentTotal = 18, loadedCount = 18))
        assertEquals(24, reconcileFeedTotal(remoteTotal = 12, currentTotal = 18, loadedCount = 24))
    }

    @Test
    fun `stale random session without a cursor still requests the remaining library`() {
        assertTrue(shouldRequestMoreFeed(nextCursor = null, loadedCount = 18, total = 762, loading = false))
        assertTrue(shouldRequestMoreFeed(nextCursor = "next", loadedCount = 18, total = 18, loading = false))
        assertFalse(shouldRequestMoreFeed(nextCursor = null, loadedCount = 18, total = 18, loading = false))
        assertFalse(shouldRequestMoreFeed(nextCursor = null, loadedCount = 18, total = 762, loading = true))
    }

    @Test
    fun feedAdvancesAndWrapsAtTheEnd() {
        assertEquals(1, nextFeedIndex(currentIndex = 0, itemCount = 3))
        assertEquals(0, nextFeedIndex(currentIndex = 2, itemCount = 3))
        assertNull(nextFeedIndex(currentIndex = 0, itemCount = 0))
    }

    @Test
    fun audioQueueSkipsVideosAndStopsAfterTheLastAudio() {
        val first = media(id = 1, kind = "audio")
        val video = media(id = 2, kind = "video")
        val second = media(id = 3, kind = "audio")
        val queue = asmrPlaybackQueue(listOf(first, video, second), first)

        assertEquals(second, nextAsmrEntry(queue, afterId = first.id))
        assertNull(nextAsmrEntry(queue, afterId = second.id))
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
    fun feedSessionKeepsItsSequenceAndActiveItem() {
        val items = (1L..5L).map { media(id = it, kind = "video") }
        val session = FeedSessionState(
            items = items,
            activeMediaId = 3L,
            nextCursor = "cursor-2",
            total = 20,
            playWhenReady = true,
            excludedIds = listOf(9L, 10L),
            startId = 3L,
        )

        assertEquals(items, session.items)
        assertEquals(2, session.activeIndex())
        assertEquals("cursor-2", session.nextCursor)
        assertEquals(listOf(9L, 10L), session.excludedIds)
        assertEquals(3L, session.startId)
    }

    @Test
    fun shortAndLongFeedSessionsRemainIndependent() {
        val shortItems = (1L..4L).map { media(id = it, kind = "video") }
        val longItems = (11L..14L).map { media(id = it, kind = "video") }
        val sessions = FeedSessionRegistry()

        sessions.save(MediaSurface.SHORT, FeedMode.SHUFFLE, FeedSessionState(shortItems, 3L))
        sessions.save(MediaSurface.LONG, FeedMode.SHUFFLE, FeedSessionState(longItems, 12L))

        assertEquals(shortItems, sessions.restore(MediaSurface.SHORT, FeedMode.SHUFFLE)?.items)
        assertEquals(2, sessions.restore(MediaSurface.SHORT, FeedMode.SHUFFLE)?.activeIndex())
        assertEquals(longItems, sessions.restore(MediaSurface.LONG, FeedMode.SHUFFLE)?.items)
        assertEquals(1, sessions.restore(MediaSurface.LONG, FeedMode.SHUFFLE)?.activeIndex())
    }

    @Test
    fun persistedFeedWindowAlwaysContainsTheCurrentItemAndHistory() {
        val items = (1L..400L).map { media(id = it, kind = "video") }
        val stored = FeedSessionState(items = items, activeMediaId = 251L, nextCursor = "next")
            .storageWindow(limit = 180)

        assertEquals(180, stored.items.size)
        assertTrue(stored.items.any { it.id == 251L })
        assertTrue(stored.activeIndex() >= 100)
        assertNull(stored.nextCursor)
    }

    @Test
    fun staleFeedResponseCannotReplaceTheCurrentSurfaceSession() {
        assertTrue(
            shouldApplyFeedResponse(
                requestGeneration = 8L,
                currentGeneration = 8L,
                requestedSurface = MediaSurface.SHORT,
                currentSurface = MediaSurface.SHORT,
                requestedMode = FeedMode.SHUFFLE,
                currentMode = FeedMode.SHUFFLE,
            ),
        )
        assertFalse(
            shouldApplyFeedResponse(
                requestGeneration = 7L,
                currentGeneration = 8L,
                requestedSurface = MediaSurface.SHORT,
                currentSurface = MediaSurface.SHORT,
                requestedMode = FeedMode.SHUFFLE,
                currentMode = FeedMode.SHUFFLE,
            ),
        )
        assertFalse(
            shouldApplyFeedResponse(
                requestGeneration = 8L,
                currentGeneration = 8L,
                requestedSurface = MediaSurface.SHORT,
                currentSurface = MediaSurface.ASMR,
                requestedMode = FeedMode.SHUFFLE,
                currentMode = FeedMode.SHUFFLE,
            ),
        )
    }

    @Test
    fun feedLoadingOnlyOwnsTheEmptyStateNotASecondPlaybackSpinner() {
        assertTrue(shouldShowInitialFeedLoading(itemCount = 0, feedLoading = true))
        assertFalse(shouldShowInitialFeedLoading(itemCount = 4, feedLoading = true))
        assertFalse(shouldShowInitialFeedLoading(itemCount = 0, feedLoading = false))
    }

    @Test
    fun restoredAsmrScrollPositionIsClampedToTheAvailableList() {
        assertEquals(ListPosition(index = 4, offset = 18), ListPosition(4, 18).clamp(itemCount = 9))
        assertEquals(ListPosition(index = 2, offset = 0), ListPosition(20, 18).clamp(itemCount = 3))
        assertEquals(ListPosition(), ListPosition(20, 18).clamp(itemCount = 0))
    }

    @Test
    fun rapidAsmrListChangesNeverRestoreAnOutOfRangePosition() {
        var position = ListPosition(index = 2_000, offset = 32)
        for (itemCount in (0..250).toList() + (250 downTo 0)) {
            position = position.clamp(itemCount)
            if (itemCount == 0) {
                assertEquals(ListPosition(), position)
            } else {
                assertTrue(position.index in 0 until itemCount)
                assertTrue(position.offset >= 0)
            }
            position = ListPosition(position.index + 17, position.offset + 3)
        }
    }

    @Test
    fun duplicateAsmrKeysAreRemovedBeforeLazyListRendering() {
        val author = AsmrAuthor("same", 1, 1, 0, null)
        assertEquals(1, stableAsmrAuthors(listOf(author, author)).size)
        assertEquals(2, stableAsmrEntries(listOf(media(1, "video"), media(1, "video"), media(2, "audio"))).size)
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

    @Test
    fun asmrPlaybackQueueKeepsAuthorOrderAndMediaKind() {
        val authorAudio1 = media(id = 1L, kind = "audio", author = "author-a")
        val authorVideo1 = media(id = 2L, kind = "video", author = "author-a")
        val otherVideo = media(id = 3L, kind = "video", author = "author-b")
        val authorVideo2 = media(id = 4L, kind = "video", author = "author-a")
        val authorAudio2 = media(id = 5L, kind = "audio", author = "author-a")

        assertEquals(
            listOf(authorVideo1, authorVideo2),
            asmrPlaybackQueue(
                listOf(authorAudio1, authorVideo1, otherVideo, authorVideo2, authorAudio2),
                authorVideo1,
            ),
        )
        assertEquals(
            listOf(authorAudio1, authorAudio2),
            asmrPlaybackQueue(
                listOf(authorAudio1, authorVideo1, otherVideo, authorVideo2, authorAudio2),
                authorAudio1,
            ),
        )
        assertEquals(authorVideo2, nextAsmrEntry(listOf(authorVideo1, authorVideo2), authorVideo1.id))

        val restoredVideo = media(id = 6L, kind = "video", author = "author-a")
        assertEquals(
            listOf(restoredVideo, authorVideo1, authorVideo2),
            asmrPlaybackQueue(listOf(authorVideo1, otherVideo, authorVideo2), restoredVideo),
        )
    }

    private fun media(id: Long, kind: String, author: String = "Author") = MediaEntry(
        id = id,
        title = "media-$id",
        size = 1L,
        modified = null,
        durationSeconds = null,
        playUrl = "/api/videos/$id/play",
        posterUrl = null,
        author = author,
        format = if (kind == "audio") "mp3" else "mp4",
        kind = kind,
    )
}
