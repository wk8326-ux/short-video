package you.deepfuck.shortvideo

import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.AsmrFilter
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.media.PlaybackEndedEvent

internal data class FeedSessionState(
    val items: List<MediaEntry>,
    val activeMediaId: Long? = null,
    val nextCursor: String? = null,
    val total: Int = items.size,
    val playWhenReady: Boolean = true,
) {
    fun activeIndex(): Int = items.indexOfFirst { it.id == activeMediaId }
        .takeIf { it >= 0 }
        ?: 0

    fun storageWindow(limit: Int): FeedSessionState {
        if (limit <= 0) return copy(items = emptyList(), nextCursor = null)
        if (items.size <= limit) return this
        val activeIndex = activeIndex().coerceIn(0, items.lastIndex)
        val historySlots = (limit * 2) / 3
        val start = (activeIndex - historySlots)
            .coerceAtLeast(0)
            .coerceAtMost(items.size - limit)
        val end = (start + limit).coerceAtMost(items.size)
        return copy(
            items = items.subList(start, end),
            nextCursor = nextCursor.takeIf { end == items.size },
        )
    }
}

internal class FeedSessionRegistry {
    private data class Key(val surface: MediaSurface, val mode: FeedMode)

    private val sessions = mutableMapOf<Key, FeedSessionState>()

    fun save(surface: MediaSurface, mode: FeedMode, session: FeedSessionState) {
        sessions[Key(surface, mode)] = session
    }

    fun restore(surface: MediaSurface, mode: FeedMode): FeedSessionState? =
        sessions[Key(surface, mode)]
}

internal data class ListPosition(
    val index: Int = 0,
    val offset: Int = 0,
) {
    fun clamp(itemCount: Int): ListPosition {
        if (itemCount <= 0) return ListPosition()
        val safeIndex = index.coerceIn(0, itemCount - 1)
        return ListPosition(safeIndex, if (safeIndex == index) offset.coerceAtLeast(0) else 0)
    }
}

internal data class AsmrPlaybackState(
    val entry: MediaEntry,
    val playWhenReady: Boolean,
    val expanded: Boolean,
)

internal fun nextFeedIndex(currentIndex: Int, itemCount: Int): Int? {
    if (itemCount <= 0) return null
    return (currentIndex + 1).mod(itemCount)
}

internal fun nextAudioEntry(items: List<MediaEntry>, afterId: Long): MediaEntry? {
    val currentIndex = items.indexOfFirst { it.id == afterId }
    if (currentIndex < 0) return null
    return items.drop(currentIndex + 1).firstOrNull(MediaEntry::isAudio)
}

internal fun shouldAttachFeedPlayer(active: Boolean, entryId: Long, mediaId: Long?): Boolean =
    active && mediaId == entryId

internal fun shouldActivateFeedItem(surface: MediaSurface): Boolean = surface != MediaSurface.ASMR

internal fun shouldApplyFeedResponse(
    requestGeneration: Long,
    currentGeneration: Long,
    requestedSurface: MediaSurface,
    currentSurface: MediaSurface,
    requestedMode: FeedMode,
    currentMode: FeedMode,
): Boolean = requestGeneration == currentGeneration &&
    requestedSurface == currentSurface &&
    requestedMode == currentMode

internal fun shouldShowInitialFeedLoading(itemCount: Int, feedLoading: Boolean): Boolean =
    itemCount == 0 && feedLoading

internal fun stableAsmrAuthors(authors: List<AsmrAuthor>): List<AsmrAuthor> =
    authors.distinctBy { it.name }

internal fun stableAsmrEntries(entries: List<MediaEntry>): List<MediaEntry> =
    entries.distinctBy { it.id }

internal fun filterAsmrAuthors(
    authors: List<AsmrAuthor>,
    filter: AsmrFilter,
    query: String,
): List<AsmrAuthor> {
    val normalizedQuery = query.trim()
    return stableAsmrAuthors(authors).filter { author ->
        val kindMatches = when (filter) {
            AsmrFilter.ALL -> true
            AsmrFilter.VIDEO -> author.videoCount > 0
            AsmrFilter.AUDIO -> author.audioCount > 0
        }
        kindMatches && (normalizedQuery.isEmpty() || author.name.contains(normalizedQuery, ignoreCase = true))
    }
}

internal fun filterAsmrEntries(
    entries: List<MediaEntry>,
    filter: AsmrFilter,
    query: String,
): List<MediaEntry> {
    val normalizedQuery = query.trim()
    return stableAsmrEntries(entries).filter { entry ->
        val kindMatches = when (filter) {
            AsmrFilter.ALL -> true
            AsmrFilter.VIDEO -> !entry.isAudio
            AsmrFilter.AUDIO -> entry.isAudio
        }
        kindMatches && (normalizedQuery.isEmpty() || entry.title.contains(normalizedQuery, ignoreCase = true))
    }
}

internal fun reconcileFeedTotal(remoteTotal: Int, currentTotal: Int, loadedCount: Int): Int =
    maxOf(remoteTotal, currentTotal, loadedCount)

internal fun shouldHandlePlaybackEnded(
    event: PlaybackEndedEvent,
    engineMediaId: Long?,
    engineGeneration: Long,
    stateMediaId: Long?,
): Boolean = event.mediaId == engineMediaId &&
    event.mediaId == stateMediaId &&
    event.generation == engineGeneration

internal fun feedPrefetchCandidates(
    items: List<MediaEntry>,
    activeIndex: Int,
    count: Int = 2,
): List<MediaEntry> = items.drop((activeIndex + 1).coerceAtLeast(0)).take(count)

internal fun shouldKeepPlayingInBackground(
    surface: MediaSurface,
    media: MediaEntry?,
    videoEnabled: Boolean,
): Boolean = surface == MediaSurface.ASMR && media != null &&
    if (media.isAudio) true else videoEnabled

internal fun shouldLoadMore(
    lastVisibleIndex: Int,
    itemCount: Int,
    loading: Boolean,
    hasMore: Boolean,
    threshold: Int = 6,
): Boolean {
    if (loading || !hasMore || itemCount <= 0 || lastVisibleIndex < 0) return false
    return lastVisibleIndex >= (itemCount - threshold).coerceAtLeast(0)
}
