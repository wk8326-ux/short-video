package you.deepfuck.shortvideo.ui

internal fun calculateSeekTarget(
    basePositionMs: Long,
    accumulatedDragPx: Float,
    widthPx: Int,
    durationMs: Long,
    maxDeltaMs: Long,
): Long {
    if (widthPx <= 0 || durationMs <= 0L) return basePositionMs.coerceAtLeast(0L)
    val deltaMs = (accumulatedDragPx / widthPx * durationMs * 0.45f).toLong()
        .coerceIn(-maxDeltaMs, maxDeltaMs)
    return (basePositionMs + deltaMs).coerceIn(0L, durationMs)
}
