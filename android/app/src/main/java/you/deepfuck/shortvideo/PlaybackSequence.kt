package you.deepfuck.shortvideo

import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MovieItem
import you.deepfuck.shortvideo.data.movieSortIsCover
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.AsmrAuthor
import you.deepfuck.shortvideo.data.AsmrFilter
import you.deepfuck.shortvideo.data.LibraryScanStatus
import you.deepfuck.shortvideo.data.MediaSurface
import you.deepfuck.shortvideo.media.PlaybackEndedEvent

internal data class FeedSessionState(
    val items: List<MediaEntry>,
    val activeMediaId: Long? = null,
    val nextCursor: String? = null,
    val total: Int = items.size,
    val playWhenReady: Boolean = true,
    val excludedIds: List<Long> = emptyList(),
    val startId: Long? = null,
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
        require(surface.isFeed) { "Only feed surfaces can own feed sessions" }
        sessions[Key(surface, mode)] = session
    }

    fun restore(surface: MediaSurface, mode: FeedMode): FeedSessionState? =
        if (surface.isFeed) sessions[Key(surface, mode)] else null

    fun clear() = sessions.clear()
}

internal enum class SourceScanOutcome { WAITING, RUNNING, SUCCEEDED, FAILED }

internal fun sourceScanOutcome(
    previous: LibraryScanStatus?,
    current: LibraryScanStatus,
    scanWasAccepted: Boolean,
    runningWasObserved: Boolean,
): SourceScanOutcome {
    if (current.running) return SourceScanOutcome.RUNNING
    val completed = scanWasAccepted ||
        runningWasObserved ||
        current.lastSuccess != previous?.lastSuccess ||
        current.lastError != previous?.lastError
    if (!completed) return SourceScanOutcome.WAITING
    return if (current.lastError == null) SourceScanOutcome.SUCCEEDED else SourceScanOutcome.FAILED
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

internal fun asmrPlaybackQueue(items: List<MediaEntry>, current: MediaEntry): List<MediaEntry> {
    val author = current.author ?: return listOf(current)
    val queue = stableAsmrEntries(items).filter { entry ->
        entry.author == author && entry.isAudio == current.isAudio
    }
    return if (queue.any { it.id == current.id }) queue else listOf(current) + queue
}

internal fun nextAsmrEntry(items: List<MediaEntry>, afterId: Long): MediaEntry? {
    val currentIndex = items.indexOfFirst { it.id == afterId }
    if (currentIndex < 0) return null
    return items.getOrNull(currentIndex + 1)
}

internal fun shouldAttachFeedPlayer(active: Boolean, entryId: Long, mediaId: Long?): Boolean =
    active && mediaId == entryId

internal fun shouldActivateFeedItem(
    surface: MediaSurface,
    showManagement: Boolean,
): Boolean = surface.isFeed && !showManagement

internal fun shouldRefreshFeedAfterScan(
    surface: MediaSurface,
    showManagement: Boolean,
): Boolean = surface.isFeed && !showManagement

internal class FeedLibraryRefreshGate {
    private var pending = false

    fun markPending() {
        pending = true
    }

    fun consumeIfVisible(surface: MediaSurface, showManagement: Boolean): Boolean {
        if (!pending || !shouldRefreshFeedAfterScan(surface, showManagement)) return false
        pending = false
        return true
    }
}

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

internal fun mergeMoviePages(
    current: List<MovieItem>,
    incoming: List<MovieItem>,
    reset: Boolean,
    sort: String = "cover",
): List<MovieItem> {
    if (reset) return orderMoviePage(incoming.distinctBy(MovieItem::id), sort)
    val ids = current.mapTo(mutableSetOf(), MovieItem::id)
    return orderMoviePage(current + incoming.filter { ids.add(it.id) }, sort)
}

/**
 * Keeps "cover first" true across pages, not just inside one.
 *
 * The server already orders each page this way, so in the healthy case this
 * changes nothing. It matters when a page arrives with a cover the client had
 * already written off, or when the first page was seeded from the wall: the
 * titles without artwork then end up at the front of the grid, which is exactly
 * the ordering the user asked for. Other sorts are left exactly as sent, because
 * only the server knows the timestamps and titles they are based on.
 */
private fun orderMoviePage(items: List<MovieItem>, sort: String): List<MovieItem> {
    if (!movieSortIsCover(sort)) return items
    // sortedBy is stable, so titles that share a rank keep the order they
    // arrived in and the grid does not reshuffle on every page.
    return items.sortedBy { if (it.wallUrl != null || it.posterUrl != null) 0 else 1 }
}

internal fun shouldApplyMovieResponse(
    requestGeneration: Long,
    currentGeneration: Long,
    requestedQuery: String,
    currentQuery: String,
    requestedSort: String,
    currentSort: String,
    currentSurface: MediaSurface,
): Boolean = requestGeneration == currentGeneration &&
    requestedQuery == currentQuery &&
    requestedSort == currentSort &&
    currentSurface == MediaSurface.MOVIE

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

internal fun shouldRequestMoreFeed(
    nextCursor: String?,
    loadedCount: Int,
    total: Int,
    loading: Boolean,
): Boolean = !loading && (nextCursor != null || loadedCount < total)

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
