package you.deepfuck.shortvideo

import you.deepfuck.shortvideo.data.MediaEntry

internal fun nextFeedIndex(currentIndex: Int, itemCount: Int): Int? {
    if (itemCount <= 0) return null
    return (currentIndex + 1).mod(itemCount)
}

internal fun nextAudioEntry(items: List<MediaEntry>, afterId: Long): MediaEntry? {
    val currentIndex = items.indexOfFirst { it.id == afterId }
    if (currentIndex < 0) return null
    return items.drop(currentIndex + 1).firstOrNull(MediaEntry::isAudio)
}
