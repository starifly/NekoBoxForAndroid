package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterMembershipTest {

    @Test
    fun filtersUnavailableAndDuplicateMembersInCandidateOrder() {
        val plan = RouterMembership.plan(
            availableProxyIds = listOf(30L, 10L, 20L),
            requestedProxyIds = listOf(20L, 99L, 20L, 30L),
            currentSelectedProxyId = 20L,
        )

        assertEquals(listOf(30L, 20L), plan.memberProxyIds)
        assertEquals(20L, plan.selectedProxyId)
    }

    @Test
    fun selectsFirstRemainingMemberWhenCurrentSelectionWasRemoved() {
        val plan = RouterMembership.plan(
            availableProxyIds = listOf(30L, 10L, 20L),
            requestedProxyIds = listOf(20L, 30L),
            currentSelectedProxyId = 10L,
        )

        assertEquals(listOf(30L, 20L), plan.memberProxyIds)
        assertEquals(30L, plan.selectedProxyId)
    }

    @Test
    fun clearsSelectionWhenNoMembersRemain() {
        val plan = RouterMembership.plan(
            availableProxyIds = listOf(30L, 10L),
            requestedProxyIds = emptyList(),
            currentSelectedProxyId = 10L,
        )

        assertEquals(emptyList<Long>(), plan.memberProxyIds)
        assertNull(plan.selectedProxyId)
    }

    @Test
    fun permitsMembersThatMayAlsoBelongToOtherGroups() {
        val plan = RouterMembership.plan(
            availableProxyIds = listOf(10L, 20L, 30L),
            requestedProxyIds = listOf(10L, 20L, 30L),
            currentSelectedProxyId = 20L,
        )

        assertEquals(listOf(10L, 20L, 30L), plan.memberProxyIds)
        assertEquals(20L, plan.selectedProxyId)
    }
}
