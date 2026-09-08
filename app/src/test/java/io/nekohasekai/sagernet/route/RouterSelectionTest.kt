package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterSelectionTest {

    @Test
    fun selectsOnlyTheRequestedRouterUsingItsSelectorMapping() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = "router.sg",
                proxyId = 20L,
                mode = RouterRuntimeMode.SELECTOR,
            ),
            routerSelectorTags = mapOf(
                "router.us" to "selector-us",
                "router.sg" to "selector-sg",
            ),
            routerMemberIds = mapOf(
                "router.us" to setOf(10L),
                "router.sg" to setOf(20L),
            ),
            profileTags = mapOf(10L to "node-us", 20L to "node-sg"),
            selectorGroupId = -1L,
        )

        assertEquals(
            RouterSelectionPlan.HotSwitch(
                routerTag = "router.sg",
                selectorTag = "selector-sg",
                targetTag = "node-sg",
            ),
            result,
        )
    }

    @Test
    fun keepsLegacySelectorGroupIdSelectionAvailable() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = null,
                proxyId = 10L,
                mode = RouterRuntimeMode.SELECTOR,
            ),
            routerSelectorTags = emptyMap(),
            routerMemberIds = emptyMap(),
            profileTags = mapOf(10L to "node-us"),
            selectorGroupId = 42L,
        )

        assertEquals(
            RouterSelectionPlan.HotSwitch(
                routerTag = null,
                selectorTag = "proxy",
                targetTag = "node-us",
            ),
            result,
        )
    }

    @Test
    fun missingRouterTagDoesNotProduceASelectorCall() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = "router.missing",
                proxyId = 20L,
                mode = RouterRuntimeMode.SELECTOR,
            ),
            routerSelectorTags = mapOf("router.us" to "selector-us"),
            routerMemberIds = mapOf("router.us" to setOf(10L)),
            profileTags = mapOf(20L to "node-sg"),
            selectorGroupId = -1L,
        )

        assertEquals(RouterSelectionPlan.IgnoreMissingRouter, result)
    }

    @Test
    fun disabledRouterDoesNotProduceASelectorCall() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = "router.us",
                proxyId = 10L,
                mode = RouterRuntimeMode.SELECTOR,
                routerEnabled = false,
            ),
            routerSelectorTags = mapOf("router.us" to "selector-us"),
            routerMemberIds = mapOf("router.us" to setOf(10L)),
            profileTags = mapOf(10L to "node-us"),
            selectorGroupId = -1L,
        )

        assertEquals(RouterSelectionPlan.IgnoreMissingRouter, result)
    }

    @Test
    fun blankRouterTagDoesNotProduceASelectorCall() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = "",
                proxyId = 10L,
                mode = RouterRuntimeMode.SELECTOR,
            ),
            routerSelectorTags = mapOf("" to "selector-empty"),
            routerMemberIds = mapOf("" to setOf(10L)),
            profileTags = mapOf(10L to "node-us"),
            selectorGroupId = -1L,
        )

        assertEquals(RouterSelectionPlan.IgnoreMissingRouter, result)
    }

    @Test
    fun automaticRouterSelectionUsesFullReloadInsteadOfHotSwitch() {
        val result = RouterSelection.plan(
            request = RouterSelectionRequest(
                routerTag = "router.us-low",
                proxyId = 30L,
                mode = RouterRuntimeMode.URL_TEST,
            ),
            routerSelectorTags = mapOf("router.us-low" to "selector-us-low"),
            routerMemberIds = mapOf("router.us-low" to setOf(30L)),
            profileTags = mapOf(30L to "node-us-low"),
            selectorGroupId = -1L,
        )

        assertEquals(RouterSelectionPlan.Reload, result)
    }
}
