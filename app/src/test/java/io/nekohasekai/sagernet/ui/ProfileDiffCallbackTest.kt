package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileDiffCallbackTest {

    @Test
    fun equalLists_emitNoUpdates() {
        assertEquals(emptyList<String>(), updates(listOf(1L, 2L), listOf(1L, 2L)))
    }

    @Test
    fun changedStamp_emitsChange() {
        assertEquals(
            listOf("change:0:1"),
            updates(
                oldIds = listOf(1L),
                newIds = listOf(1L),
                oldStamps = mapOf(1L to stamp(1)),
                newStamps = mapOf(1L to stamp(2)),
            ),
        )
    }

    @Test
    fun rowPartnerStateChange_emitsPeerChange() {
        val ids = listOf(1L, 2L)
        val baseStamps = ids.associateWith { 1 }
        val before = buildProfileRowStamps(ids, baseStamps, 2) { false }
        val after = buildProfileRowStamps(ids, baseStamps, 2) { it == 2L }

        assertEquals(
            listOf("change:0:1"),
            updates(ids, ids, before, after),
        )
    }

    @Test
    fun insertedId_emitsInsert() {
        assertEquals(listOf("insert:1:1"), updates(listOf(1L), listOf(1L, 2L)))
    }

    @Test
    fun removedId_emitsRemove() {
        assertEquals(listOf("remove:1:1"), updates(listOf(1L, 2L), listOf(1L)))
    }

    @Test
    fun reorderedIds_emitMove() {
        val updates = updates(listOf(1L, 2L), listOf(2L, 1L))

        assertEquals(1, updates.size)
        assertTrue(updates.single().startsWith("move:"))
    }

    private fun updates(
        oldIds: List<Long>,
        newIds: List<Long>,
        oldStamps: Map<Long, ProfileRowStamp> = oldIds.associateWith { stamp(1) },
        newStamps: Map<Long, ProfileRowStamp> = newIds.associateWith { stamp(1) },
    ): List<String> {
        val updates = mutableListOf<String>()
        DiffUtil.calculateDiff(ProfileDiffCallback(oldIds, newIds, oldStamps, newStamps))
            .dispatchUpdatesTo(
                object : ListUpdateCallback {
                    override fun onInserted(position: Int, count: Int) {
                        updates += "insert:$position:$count"
                    }

                    override fun onRemoved(position: Int, count: Int) {
                        updates += "remove:$position:$count"
                    }

                    override fun onMoved(fromPosition: Int, toPosition: Int) {
                        updates += "move:$fromPosition:$toPosition"
                    }

                    override fun onChanged(position: Int, count: Int, payload: Any?) {
                        updates += "change:$position:$count"
                    }
                },
            )
        return updates
    }

    private fun stamp(content: Int) = ProfileRowStamp(
        content = content,
        reserveMiddleRow = false,
        doubleColumn = false,
    )
}
