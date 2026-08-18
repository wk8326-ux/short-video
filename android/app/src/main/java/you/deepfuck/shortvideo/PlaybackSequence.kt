package you.deepfuck.shortvideo

import you.deepfuck.shortvideo.data.MediaEntry
import you.deepfuck.shortvideo.data.MediaSurface

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

internal fun shouldHandlePlaybackEnded(
    endedMediaId: Long?,
    engineMediaId: Long?,
    stateMediaId: Long?,
): Boolean = endedMediaId != null && endedMediaId == engineMediaId && endedMediaId == stateMediaId

internal fun feedPrefetchCandidates(
    items: List<MediaEntry>,
    activeIndex: Int,
    count: Int = 2,
): List<MediaEntry> = items.drop((activeIndex + 1).coerceAtLeast(0)).take(count)

internal fun shouldKeepPlayingInBackground(
    surface: MediaSurface,
    media: MediaEntry?,
    audioEnabled: Boolean,
    videoEnabled: Boolean,
): Boolean = surface == MediaSurface.ASMR && media != null &&
    if (media.isAudio) audioEnabled else videoEnabled

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
