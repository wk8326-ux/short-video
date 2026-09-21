package you.deepfuck.shortvideo.ui

import kotlin.math.roundToInt

/**
 * Which slot a card held [dragOffsetPx] away from its own wants to land in.
 *
 * The library list gives every card the same height, so the slot a drag points
 * at is just its own index shifted by however many whole strides the finger has
 * travelled. Keeping this a pure function means the arithmetic that decides
 * where a card lands can be tested without a device.
 *
 * [minIndex] and [maxIndex] fence the drag inside the block the card started in.
 * The server stores one order per section and the management list is grouped by
 * section, so a card dragged past a neighbour from another section would be put
 * back on the next reload; fencing it keeps the drag honest about what it can
 * actually change.
 */
internal fun dragTargetIndex(
    index: Int,
    dragOffsetPx: Float,
    stridePx: Float,
    count: Int,
    minIndex: Int = 0,
    maxIndex: Int = count - 1,
): Int {
    if (count <= 0) return 0
    val lower = minIndex.coerceIn(0, count - 1)
    val upper = maxIndex.coerceIn(lower, count - 1)
    val safeIndex = index.coerceIn(lower, upper)
    if (stridePx <= 0f) return safeIndex
    val shifted = safeIndex + dragOffsetPx / stridePx
    return shifted.roundToInt().coerceIn(lower, upper)
}

/**
 * The run of slots around [index] that belongs to the same section.
 *
 * The manager shows every section in one list and the server keeps one order
 * per section, so the entries of a section arrive as one contiguous block.
 * Returning that block lets a drag stop at its own boundary instead of lifting
 * a card over a neighbour it cannot actually be stored next to.
 */
internal fun <T, K> sectionRange(
    items: List<T>,
    index: Int,
    sectionOf: (T) -> K,
): IntRange? {
    if (index !in items.indices) return null
    val section = sectionOf(items[index])
    // Expanding from the dragged card rather than taking the first and last
    // match keeps the range a contiguous run even if a section ever arrives in
    // more than one block.
    var start = index
    while (start > 0 && sectionOf(items[start - 1]) == section) start--
    var end = index
    while (end < items.lastIndex && sectionOf(items[end + 1]) == section) end++
    return start..end
}

/** Moves one entry to another slot, leaving every other entry in order. */
internal fun <T> moveItem(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in items.indices || to !in items.indices) return items
    val moved = items.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}
