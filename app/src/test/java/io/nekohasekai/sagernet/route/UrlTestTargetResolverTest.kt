package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlTestTargetResolverTest {

    @Test
    fun routerModeUsesCurrentRouterMembersWithoutReadingCurrentProxyGroup() {
        var normalGroupReads = 0

        val targets = UrlTestTargetResolver.resolve(
            routerGroupId = 7L,
            loadRouterTargets = { routerId ->
                assertEquals(7L, routerId)
                listOf(101L, 102L)
            },
            loadNormalGroupTargets = {
                normalGroupReads++
                listOf(1L)
            },
        )

        assertEquals(listOf(101L, 102L), targets)
        assertEquals(0, normalGroupReads)
    }

    @Test
    fun normalModeKeepsUsingCurrentProxyGroup() {
        var routerReads = 0

        val targets = UrlTestTargetResolver.resolve(
            routerGroupId = null,
            loadRouterTargets = {
                routerReads++
                emptyList()
            },
            loadNormalGroupTargets = { listOf(1L, 2L) },
        )

        assertEquals(listOf(1L, 2L), targets)
        assertEquals(0, routerReads)
    }

    @Test
    fun emptyRouterGroupDoesNotFallBackToCurrentProxyGroup() {
        var normalGroupReads = 0

        val targets = UrlTestTargetResolver.resolve(
            routerGroupId = 8L,
            loadRouterTargets = { emptyList() },
            loadNormalGroupTargets = {
                normalGroupReads++
                listOf(1L)
            },
        )

        assertEquals(emptyList<Long>(), targets)
        assertEquals(0, normalGroupReads)
    }
}
