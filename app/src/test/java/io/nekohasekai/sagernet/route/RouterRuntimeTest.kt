package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterRuntimeTest {

    @Test
    fun buildsArbitraryUserNamedGroupsWithStableTags() {
        val outbounds = RouterRuntime.build(
            groups = listOf(
                RouterRuntimeGroup("router.27a", RouterRuntimeMode.SELECTOR, listOf(1), 1, name = "US 1"),
                RouterRuntimeGroup("router.91b", RouterRuntimeMode.URL_TEST, listOf(2), -1, name = "Work")
            ),
            proxyTags = mapOf(1L to "us-1", 2L to "work-1")
        )

        assertEquals(
            listOf("router.27a", "router.91b"),
            outbounds.map { it.tag }
        )
        assertEquals(
            listOf(RouterRuntimeMode.SELECTOR, RouterRuntimeMode.URL_TEST),
            outbounds.map { it.mode }
        )
    }

    @Test
    fun resolvesOnlyCurrentOutboundTagsAndSkipsEmptyGroups() {
        val outbounds = RouterRuntime.build(
            groups = listOf(
                RouterRuntimeGroup("router.us", RouterRuntimeMode.SELECTOR, listOf(1, 99, 1), 99),
                RouterRuntimeGroup("router.empty", RouterRuntimeMode.URL_TEST, listOf(404), -1)
            ),
            proxyTags = mapOf(1L to "us-1", 2L to "unrelated")
        )

        assertEquals(listOf("us-1"), outbounds[0].outbounds)
        assertEquals("us-1", outbounds[0].defaultTag)
        assertEquals(listOf("router.us"), outbounds.map { it.tag })
    }

    @Test
    fun skipsGroupsWhoseStableTagsCollideWithProfilesOrSystemOutbounds() {
        val outbounds = RouterRuntime.build(
            groups = listOf(
                RouterRuntimeGroup("profile-us-1", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("direct", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("bypass", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("block", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("proxy", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("fragment", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("mixed-in", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("dns-hosts", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("router.us", RouterRuntimeMode.SELECTOR, listOf(1), 1)
            ),
            proxyTags = mapOf(1L to "profile-us-1"),
            reservedTags = setOf(
                "profile-us-1",
                "direct",
                "bypass",
                "block",
                "proxy",
                "fragment",
                "mixed-in",
                "dns-hosts"
            )
        )

        assertEquals(listOf("router.us"), outbounds.map { it.tag })
    }

    @Test
    fun findsUrlTestGroupForTheSelectedHomeProxy() {
        val tag = RouterRuntime.findUrlTestGroupForProxy(
            groups = listOf(
                RouterRuntimeGroup("router.manual", RouterRuntimeMode.SELECTOR, listOf(1), 1),
                RouterRuntimeGroup("router.web3", RouterRuntimeMode.URL_TEST, listOf(2, 3), -1),
            ),
            selectedProxyId = 3L,
        )

        assertEquals("router.web3", tag)
    }
}
