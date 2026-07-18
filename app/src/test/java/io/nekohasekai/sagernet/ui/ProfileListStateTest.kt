package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileListStateTest {

    @Test
    fun doubleColumn_middleRowReservesEmptyPeer() {
        val stamps = stamps(
            ids = listOf(1L, 2L),
            middleRows = mapOf(1L to false, 2L to true),
            spanCount = 2,
        )

        assertTrue(stamps.getValue(1L).reserveMiddleRow)
        assertFalse(stamps.getValue(2L).reserveMiddleRow)
        assertTrue(stamps.values.all(ProfileRowStamp::doubleColumn))
    }

    @Test
    fun partnerMiddleRowChange_changesEmptyPeerStamp() {
        val ids = listOf(1L, 2L)
        val before = stamps(ids, mapOf(1L to false, 2L to false), 2)
        val after = stamps(ids, mapOf(1L to false, 2L to true), 2)

        assertNotEquals(before.getValue(1L), after.getValue(1L))
        assertEquals(before.getValue(2L), after.getValue(2L))
    }

    @Test
    fun insertion_recomputesLaterRowPairing() {
        val before = stamps(
            ids = listOf(1L, 2L, 3L),
            middleRows = mapOf(1L to false, 2L to true, 3L to false),
            spanCount = 2,
        )
        val after = stamps(
            ids = listOf(4L, 1L, 2L, 3L),
            middleRows = mapOf(1L to false, 2L to true, 3L to false, 4L to false),
            spanCount = 2,
        )

        assertNotEquals(before.getValue(1L), after.getValue(1L))
        assertNotEquals(before.getValue(3L), after.getValue(3L))
    }

    @Test
    fun singleColumn_ignoresOtherRows() {
        val stamps = stamps(
            ids = listOf(1L, 2L),
            middleRows = mapOf(1L to false, 2L to true),
            spanCount = 1,
        )

        assertFalse(stamps.getValue(1L).reserveMiddleRow)
        assertFalse(stamps.getValue(2L).reserveMiddleRow)
        assertTrue(stamps.values.none(ProfileRowStamp::doubleColumn))
    }

    @Test
    fun rowRange_handlesPartialAndInvalidRows() {
        assertEquals(2..2, profileRowRange(position = 2, itemCount = 3, spanCount = 2))
        assertTrue(profileRowRange(position = -1, itemCount = 3, spanCount = 2).isEmpty())
        assertTrue(profileRowRange(position = 3, itemCount = 3, spanCount = 2).isEmpty())
    }

    @Test
    fun findItemById_usesCurrentPosition() {
        val items = mapOf(1L to "one", 2L to "two", 3L to "three")

        assertEquals(1 to "one", findItemById(listOf(3L, 1L, 2L), items, 1L))
        assertNull(findItemById(listOf(3L, 2L), items, 1L))
    }

    private fun stamps(ids: List<Long>, middleRows: Map<Long, Boolean>, spanCount: Int) = buildProfileRowStamps(
        ids = ids,
        baseStamps = ids.associateWith { it.hashCode() },
        spanCount = spanCount,
        hasMiddleRow = { middleRows[it] == true },
    )
}
