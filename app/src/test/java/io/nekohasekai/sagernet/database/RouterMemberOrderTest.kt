package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterMemberOrderTest {
    @Test
    fun staleDragOrderKeepsNewMembersAndIgnoresRemovedIds() {
        val dao = MemoryMembers(mutableListOf(
            RouterMember(1, 30, 1), RouterMember(1, 20, 2), RouterMember(2, 99, 7),
        ))
        // The displayed [10,20] snapshot was replaced by [30,20] during a refresh.
        dao.updateOrders(1, listOf(10, 20))
        assertEquals(listOf(20L, 30L), dao.getByRouter(1).map { it.proxyId })
        assertEquals(listOf(1L, 2L), dao.getByRouter(1).map { it.userOrder })
        assertEquals(7L, dao.getByRouter(2).single().userOrder)
    }

    @Test
    fun duplicateUiIdsDoNotCreateOrderGaps() {
        val dao = MemoryMembers(mutableListOf(RouterMember(1, 10, 1), RouterMember(1, 20, 2)))
        dao.updateOrders(1, listOf(20, 20, 10))
        assertEquals(listOf(20L, 10L), dao.getByRouter(1).map { it.proxyId })
        assertEquals(listOf(1L, 2L), dao.getByRouter(1).map { it.userOrder })
    }

    private class MemoryMembers(private val rows: MutableList<RouterMember>) : RouterMember.Dao {
        override fun all() = rows.toList()
        override fun getByRouter(routerId: Long) = rows.filter { it.routerId == routerId }
            .sortedWith(compareBy<RouterMember> { it.userOrder }.thenBy { it.proxyId })
        override fun deleteByRouter(routerId: Long): Int = error("unused")
        override fun deleteByProxy(proxyId: Long): Int = error("unused")
        override fun updateUserOrder(routerId: Long, proxyId: Long, userOrder: Long): Int {
            val row = rows.firstOrNull { it.routerId == routerId && it.proxyId == proxyId } ?: return 0
            row.userOrder = userOrder
            return 1
        }
        override fun insert(members: List<RouterMember>) { rows.addAll(members) }
        override fun reset() { rows.clear() }
    }
}
