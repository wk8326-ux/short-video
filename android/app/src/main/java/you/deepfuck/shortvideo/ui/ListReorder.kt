package you.deepfuck.shortvideo.ui

import kotlin.math.roundToInt

/**
 * Which slot a card held [dragOffsetPx] away from its own wants to land in.
 *
 * The library list gives every card the same height, so the slot a drag points
 * at is just its own index shifted by however many whole strides the finger has
 * travelled. Keeping this a pure function means the arithmetic that decides
 * where a card lands can be tested without a device.
 */
internal fun dragTargetIndex(
    index: Int,
    dragOffsetPx: Float,
    stridePx: Float,
    count: Int,
): Int {
    if (count <= 0) return 0
    val safeIndex = index.coerceIn(0, count - 1)
    if (stridePx <= 0f) return safeIndex
    val shifted = safeIndex + dragOffsetPx / stridePx
    return shifted.roundToInt().coerceIn(0, count - 1)
}

/** Moves one entry to another slot, leaving every other entry in order. */
internal fun <T> moveItem(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in items.indices || to !in items.indices) return items
    val moved = items.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}
