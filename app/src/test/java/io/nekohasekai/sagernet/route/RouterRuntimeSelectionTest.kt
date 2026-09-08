package io.nekohasekai.sagernet.route

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterRuntimeSelectionTest {

    @Test
    fun mapsCoreWinnerToItsActualProfileInsteadOfTheFirstMember() {
        val selections = RouterRuntimeSelection.resolve(
            routerTags = linkedMapOf(10L to "router.web3"),
            profileTags = linkedMapOf(1L to "node-a", 2L to "node-b"),
            currentOutbound = { "node-b" },
        )

        assertArrayEquals(longArrayOf(10L, 2L), selections)
    }

    @Test
    fun leavesGroupUnselectedUntilCoreHasAResult() {
        val selections = RouterRuntimeSelection.resolve(
            routerTags = linkedMapOf(10L to "router.web3"),
            profileTags = linkedMapOf(1L to "node-a", 2L to "node-b"),
            currentOutbound = { "" },
        )

        assertArrayEquals(longArrayOf(), selections)
    }

    @Test
    fun parsesSelectionPairsWithoutAcceptingDanglingValues() {
        assertEquals(
            linkedMapOf(10L to 2L, 20L to 4L),
            RouterRuntimeSelection.toMap(longArrayOf(10L, 2L, 20L, 4L, 99L)),
        )
    }
}
