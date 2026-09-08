package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.route.RouterRuntimeGroup
import io.nekohasekai.sagernet.route.RouterRuntimeMode
import io.nekohasekai.sagernet.route.RouterFilterConfig
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SelectorOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_URLTestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterOutboundConfigTest {

    @Test
    fun translatesRuntimeSpecsIntoSelectorAndUrlTestOptions() {
        val outbounds = buildRouterOutbounds(
            groups = listOf(
                RouterRuntimeGroup("router.us", RouterRuntimeMode.SELECTOR, listOf(1), 1,
                    id = 1, name = "US1", filter = RouterFilterConfig()),
                RouterRuntimeGroup("router.sg", RouterRuntimeMode.URL_TEST, listOf(2), -1,
                    id = 2, name = "SG1", filter = RouterFilterConfig(testUrl = "https://example.com/204", intervalSeconds = 120, toleranceMs = 75))
            ),
            proxyTags = mapOf(1L to "us-1", 2L to "sg-1")
        )

        val selector = outbounds[0] as Outbound_SelectorOptions
        assertEquals("router.us", selector.tag)
        assertEquals(listOf("us-1"), selector.outbounds)
        assertEquals("us-1", selector.default_)

        val urlTest = outbounds[1] as Outbound_URLTestOptions
        assertEquals("router.sg", urlTest.tag)
        assertEquals(listOf("sg-1"), urlTest.outbounds)
        assertEquals("https://example.com/204", urlTest.url)
        assertEquals("120s", urlTest.interval)
        assertEquals(75, urlTest.tolerance)
    }

    @Test
    fun excludesRouterOutboundsForPortableExport() {
        val outbounds = buildRouterOutbounds(
            groups = listOf(
                RouterRuntimeGroup("router.us", RouterRuntimeMode.SELECTOR, listOf(1), 1, id = 1, name = "US1")
            ),
            proxyTags = mapOf(1L to "us-1"),
            includeRouterGroups = false
        )

        assertTrue(outbounds.isEmpty())
    }

    @Test
    fun skipsRouterTagReservedByAnExistingProfile() {
        val outbounds = buildRouterOutbounds(
            groups = listOf(
                RouterRuntimeGroup("profile-us-1", RouterRuntimeMode.SELECTOR, listOf(1), 1, id = 1, name = "US1")
            ),
            proxyTags = mapOf(1L to "profile-us-1"),
            reservedTags = setOf("profile-us-1")
        )

        assertTrue(outbounds.isEmpty())
    }

    @Test
    fun skipsRouterTagReservedByAnAlreadyBuiltInternalOutboundMapTag() {
        val internalOutbound = Outbound().apply {
            _hack_config_map["tag"] = "g-123"
        }
        assertEquals("g-123", internalOutbound.asMap()["tag"])
        val outbounds = buildRouterOutbounds(
            groups = listOf(
                RouterRuntimeGroup("g-123", RouterRuntimeMode.SELECTOR, listOf(1), 1, id = 1, name = "US1")
            ),
            proxyTags = mapOf(1L to "g-123"),
            reservedTags = routerReservedTags(listOf(internalOutbound))
        )

        assertTrue(outbounds.isEmpty())
    }
}
