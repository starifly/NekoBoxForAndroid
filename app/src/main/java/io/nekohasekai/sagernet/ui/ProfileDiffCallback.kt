package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.DiffUtil

internal class ProfileDiffCallback(
    private val oldIds: List<Long>,
    private val newIds: List<Long>,
    private val oldStamps: Map<Long, ProfileRowStamp>,
    private val newStamps: Map<Long, ProfileRowStamp>,
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldIds.size

    override fun getNewListSize() = newIds.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldIds[oldItemPosition] == newIds[newItemPosition]

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldStamps[oldIds[oldItemPosition]] == newStamps[newIds[newItemPosition]]
}
