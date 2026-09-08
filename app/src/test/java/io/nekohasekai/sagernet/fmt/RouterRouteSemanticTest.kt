package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.route.RouterRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterRouteSemanticTest {
    private val profileTags = mapOf(11L to "legacy-google", 12L to "legacy-social")
    private val routerTags = mapOf(7L to "router.custom")

    @Test
    fun routeNameNeverChangesItsLegacyOutbound() {
        assertEquals(
            TAG_BYPASS,
            resolveRouteOutbound(RuleEntity(name = "Google and AI", outbound = -1), "main", profileTags, routerTags),
        )
        assertEquals(
            "legacy-social",
            resolveRouteOutbound(RuleEntity(name = "Telegram", outbound = 12), "main", profileTags, routerTags),
        )
    }

    @Test
    fun explicitGroupReferenceUsesItsStableTag() {
        val rule = RuleEntity(name = "Any name", outbound = 0, routerGroupId = 7)
        assertEquals("router.custom", resolveRouteOutbound(rule, "main", profileTags, routerTags))
    }

    @Test
    fun mainProxyRulesStayOnLegacyTargetWhenRouterGroupsExist() {
        for (mainTag in listOf(TAG_PROXY, "legacy-google")) {
            assertEquals(
                mainTag,
                resolveRouteOutbound(RuleEntity(outbound = 0), mainTag, profileTags, routerTags, 11),
            )
            assertEquals(
                mainTag,
                resolveRouteOutbound(RuleEntity(outbound = 11), mainTag, profileTags, routerTags, 11),
            )
            assertEquals(
                "router.custom",
                resolveRouteOutbound(RuleEntity(outbound = 0, routerGroupId = 7), mainTag, profileTags, routerTags, 11),
            )
        }
    }

    @Test
    fun routeEditorTargetsAreMutuallyExclusive() {
        assertEquals(
            RouteOutboundChoice(outbound = 0L, routerGroupId = 7L),
            serializeRouteOutboundChoice(4, legacyProfileId = 99L, routerGroupId = 7L, routerChoiceValue = 4),
        )
        assertEquals(
            RouteOutboundChoice(outbound = -1L, routerGroupId = 0L),
            serializeRouteOutboundChoice(1, legacyProfileId = 99L, routerGroupId = 7L, routerChoiceValue = 4),
        )
        assertEquals(
            RouteOutboundChoice(outbound = 99L, routerGroupId = 0L),
            serializeRouteOutboundChoice(3, legacyProfileId = 99L, routerGroupId = 7L, routerChoiceValue = 4),
        )
    }

    @Test
    fun missingGroupReferenceThrowsInsteadOfFallingBack() {
        val error = assertThrows(RouterRuntimeException::class.java) {
            resolveRouteOutbound(RuleEntity(outbound = 0, routerGroupId = 404), "main", profileTags, routerTags)
        }
        assertEquals(404L, error.groupId)
        assertEquals(RouterRuntimeException.Reason.MISSING, error.reason)
    }

    @Test
    fun disabledAndEmptyReferencedGroupsHaveSpecificErrors() {
        val groups = listOf(
            RouterGroup(id = 1, stableTag = "router.disabled", name = "Disabled", enabled = false),
            RouterGroup(id = 2, stableTag = "router.empty", name = "Empty", enabled = true),
        )
        val disabled = assertThrows(RouterRuntimeException::class.java) {
            validateRouterReferences(listOf(RuleEntity(routerGroupId = 1)), groups, emptySet())
        }
        assertEquals("Disabled", disabled.groupName)
        assertEquals(RouterRuntimeException.Reason.DISABLED, disabled.reason)

        val empty = assertThrows(RouterRuntimeException::class.java) {
            validateRouterReferences(listOf(RuleEntity(routerGroupId = 2)), groups, emptySet())
        }
        assertEquals("Empty", empty.groupName)
        assertEquals(RouterRuntimeException.Reason.EMPTY, empty.reason)
    }

    @Test
    fun adBlockInvalidLoadAndMainSelectorTargetsStayUnchanged() {
        assertEquals(TAG_BLOCK, resolveRouteOutbound(RuleEntity(name = "AdBlock", outbound = -2), "main", profileTags, routerTags))
        assertEquals("legacy-google", resolveRouteOutbound(RuleEntity(name = "加载节点", outbound = 11, domains = "full:load.invalid"), "main", profileTags, routerTags))
        assertEquals(
            "main",
            resolveRouteOutbound(RuleEntity(outbound = 99), "main", mapOf(99L to "chain"), routerTags, 99),
        )
    }
}
