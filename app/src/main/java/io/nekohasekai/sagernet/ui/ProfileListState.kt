package io.nekohasekai.sagernet.ui

internal data class ProfileRowStamp(
    val content: Int,
    val reserveMiddleRow: Boolean,
    val doubleColumn: Boolean,
)

internal fun profileRowRange(position: Int, itemCount: Int, spanCount: Int): IntRange {
    if (position !in 0 until itemCount || spanCount <= 0) return IntRange.EMPTY
    val start = position - position % spanCount
    return start..minOf(start + spanCount - 1, itemCount - 1)
}

internal fun buildProfileRowStamps(
    ids: List<Long>,
    baseStamps: Map<Long, Int>,
    spanCount: Int,
    hasMiddleRow: (Long) -> Boolean,
): Map<Long, ProfileRowStamp> {
    val doubleColumn = spanCount > 1
    return ids.mapIndexed { position, id ->
        val ownHasMiddleRow = hasMiddleRow(id)
        val rowHasMiddleRow = if (doubleColumn) {
            profileRowRange(position, ids.size, spanCount).any { hasMiddleRow(ids[it]) }
        } else {
            ownHasMiddleRow
        }
        id to ProfileRowStamp(
            content = baseStamps.getValue(id),
            reserveMiddleRow = !ownHasMiddleRow && rowHasMiddleRow,
            doubleColumn = doubleColumn,
        )
    }.toMap()
}

internal fun <T> findItemById(ids: List<Long>, items: Map<Long, T>, id: Long): Pair<Int, T>? {
    val index = ids.indexOf(id)
    if (index < 0) return null
    return index to (items[id] ?: return null)
}
