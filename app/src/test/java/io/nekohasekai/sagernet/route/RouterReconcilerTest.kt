package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterReconcilerTest {

    @Test
    fun reusedProfileIdDoesNotStealStableMemberOrderOrSelection() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(
                RouterNodeSnapshot(100, "replacement", "US A", subscriptionId = 10),
                RouterNodeSnapshot(200, "original", "US renamed", subscriptionId = 10),
            ),
            groups = listOf(group(1, setOf(10), "US", selectedProxyId = 100)),
            previousMembers = mapOf(1L to listOf(RouterMemberSnapshot(100, "original", 10, 7))),
        )

        assertEquals(listOf(200L, 100L), result.membersByRouterId.getValue(1).map { it.proxyId })
        assertEquals(listOf(7L, 8L), result.membersByRouterId.getValue(1).map { it.userOrder })
        assertEquals(200L, result.selectedProxyIdsByRouterId.getValue(1))
    }

    @Test
    fun selectionFollowsStableIdentityWhenExistingIdsSwapNodes() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(
                RouterNodeSnapshot(100, "b", "US A", subscriptionId = 10),
                RouterNodeSnapshot(200, "a", "US B", subscriptionId = 10),
            ),
            groups = listOf(group(1, setOf(10), "US", selectedProxyId = 100)),
            previousMembers = mapOf(1L to listOf(
                RouterMemberSnapshot(100, "a", 10, 7),
                RouterMemberSnapshot(200, "b", 10, 8),
            )),
        )

        assertEquals(200L, result.selectedProxyIdsByRouterId.getValue(1))
        assertEquals(listOf(200L, 100L), result.membersByRouterId.getValue(1).map { it.proxyId })
    }

    @Test
    fun remapsMemberAndSelectionBySourceScopedStableIdentity() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(RouterNodeSnapshot(200, "node-a", "US renamed", subscriptionId = 10)),
            groups = listOf(group(1, setOf(10), "US", selectedProxyId = 100)),
            previousMembers = mapOf(
                1L to listOf(RouterMemberSnapshot(100, "node-a", sourceGroupId = 10, userOrder = 7))
            ),
        )

        assertEquals(listOf(200L), result.membersByRouterId.getValue(1).map { it.proxyId })
        assertEquals(listOf(7L), result.membersByRouterId.getValue(1).map { it.userOrder })
        assertEquals(200L, result.selectedProxyIdsByRouterId.getValue(1))
        assertFalse(result.preservedPreviousMembers)
    }

    @Test
    fun identicalStableIdentityFromAnotherSourceIsNotUsed() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(RouterNodeSnapshot(200, "shared", "US B", subscriptionId = 20)),
            groups = listOf(group(1, setOf(10), "US")),
            previousMembers = mapOf(
                1L to listOf(RouterMemberSnapshot(100, "shared", sourceGroupId = 10, userOrder = 3))
            ),
        )

        assertTrue(result.membersByRouterId.getValue(1).isEmpty())
    }

    @Test
    fun theSameCurrentNodeIsRetainedIndependentlyByTwoGroups() {
        val current = listOf(RouterNodeSnapshot(200, "node-a", "US A", subscriptionId = 10))
        val previous = listOf(RouterMemberSnapshot(100, "node-a", sourceGroupId = 10))
        val result = RouterReconciler.reconcile(
            current,
            listOf(group(1, setOf(10), "US"), group(2, setOf(10), "A")),
            mapOf(1L to previous, 2L to previous),
        )

        assertEquals(listOf(200L), result.membersByRouterId.getValue(1).map { it.proxyId })
        assertEquals(listOf(200L), result.membersByRouterId.getValue(2).map { it.proxyId })
    }

    @Test
    fun excludeAndSourceChangesRemoveOldMembersOnSuccessfulRefresh() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(
                RouterNodeSnapshot(200, "a", "US Expired", subscriptionId = 10),
                RouterNodeSnapshot(300, "b", "US Other", subscriptionId = 20),
            ),
            groups = listOf(group(1, setOf(10), "US", exclude = "Expired")),
            previousMembers = mapOf(1L to listOf(RouterMemberSnapshot(100, "a", 10))),
        )

        assertTrue(result.membersByRouterId.getValue(1).isEmpty())
        assertFalse(result.preservedPreviousMembers)
    }

    @Test
    fun emptyOrInvalidRefreshPreservesTheLastValidSnapshot() {
        val previous = mapOf(1L to listOf(RouterMemberSnapshot(100, "a", 10)))
        val empty = RouterReconciler.reconcile(emptyList(), listOf(group(1, setOf(10), "")), previous)
        val invalid = RouterReconciler.reconcile(
            listOf(RouterNodeSnapshot(200, "b", "US", subscriptionId = 10, available = false)),
            listOf(group(1, setOf(10), "")),
            previous,
        )

        assertEquals(previous, empty.membersByRouterId)
        assertEquals(previous, invalid.membersByRouterId)
        assertTrue(empty.preservedPreviousMembers)
        assertTrue(invalid.preservedPreviousMembers)
        assertNotNull(empty.error)
        assertNotNull(invalid.error)
    }

    @Test
    fun newMembersAppendInMatcherOrderAfterSurvivingUserOrder() {
        val result = RouterReconciler.reconcile(
            currentNodes = listOf(
                RouterNodeSnapshot(300, "c", "US C", subscriptionId = 10),
                RouterNodeSnapshot(200, "a", "US A", subscriptionId = 10),
                RouterNodeSnapshot(201, "b", "US B", subscriptionId = 10),
            ),
            groups = listOf(group(1, setOf(10), "US")),
            previousMembers = mapOf(
                1L to listOf(
                    RouterMemberSnapshot(101, "b", 10, 5),
                    RouterMemberSnapshot(100, "a", 10, 20),
                )
            ),
        )

        assertEquals(listOf(201L, 200L, 300L), result.membersByRouterId.getValue(1).map { it.proxyId })
        assertEquals(listOf(5L, 20L, 21L), result.membersByRouterId.getValue(1).map { it.userOrder })
    }

    @Test
    fun identifiesDanglingMembersAfterProxyDeletion() {
        assertEquals(
            setOf(100L),
            danglingRouterMemberProxyIds(
                listOf(RouterMemberSnapshot(100, "old"), RouterMemberSnapshot(200, "current")),
                setOf(200),
            ),
        )
    }

    private fun group(
        id: Long,
        sources: Set<Long>,
        include: String,
        exclude: String = "",
        selectedProxyId: Long? = null,
    ) = RouterReconcileGroup(
        routerId = id,
        stableTag = "router.$id",
        sourceGroupIds = sources.toList(),
        filter = RouterFilterConfig(include, exclude).validate(),
        selectedProxyId = selectedProxyId,
    )
}
