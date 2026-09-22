package you.deepfuck.shortvideo.ui

import kotlin.math.roundToInt

/**
 * Which slot a card whose top edge sits at [draggedTopPx] belongs in.
 *
 * The reorder screen gives every row the same height, so a slot is just the
 * card's position divided by that height. Deriving the slot from the position
 * rather than from an accumulated offset is what makes the list swap cleanly
 * and stay put: a card sitting between two slots always resolves to the nearer
 * one, so it cannot flip back and forth against its neighbour.
 *
 * A list with no height yet leaves the card in the first slot instead of
 * dividing by zero.
 */
internal fun reorderSlot(
    draggedTopPx: Float,
    rowHeightPx: Float,
    count: Int,
): Int {
    if (count <= 0) return 0
    if (rowHeightPx <= 0f) return 0
    return (draggedTopPx / rowHeightPx).roundToInt().coerceIn(0, count - 1)
}

/** Moves one entry to another slot, leaving every other entry in order. */
internal fun <T> moveItem(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in items.indices || to !in items.indices) return items
    val moved = items.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}

/**
 * The id list to hand the server after one section was rearranged.
 *
 * The server assigns one position per section, so only the relative order of
 * the ids *inside* a section survives a save. Sending the rearranged section
 * ids in the slots they already occupy leaves each of those other sections in
 * exactly the order it already had, which is what "only the movie wall is
 * reordered" has to mean: nothing the user did not touch can shift because of
 * this call, and the management list itself does not jump around either.
 */
internal fun mergeReorderedSection(
    allIds: List<String>,
    sectionIds: Set<String>,
    reorderedSectionIds: List<String>,
): List<String> {
    val replacement = buildList {
        reorderedSectionIds.forEach { id -> if (id in sectionIds && id !in this) add(id) }
        // Anything the caller left out -- a library added by another screen while
        // this one was open, say -- follows in its old relative order instead of
        // being dropped from the list.
        allIds.forEach { id -> if (id in sectionIds && id !in this) add(id) }
    }
    if (replacement.isEmpty()) return allIds
    var cursor = 0
    return allIds.map { id -> if (id in sectionIds) replacement[cursor++] else id }
}
