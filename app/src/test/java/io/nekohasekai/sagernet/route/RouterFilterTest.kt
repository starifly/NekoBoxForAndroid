package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterFilterTest {
    @Test
    fun jsonRoundTripPreservesOnlySupportedFields() {
        val config = RouterFilterConfig("US", "Expired", "https://example.com/204", 120, 75)
        assertEquals(config, RouterFilterConfig.fromJson(config.toJson()))
    }

    @Test
    fun oldOrEmptyJsonUsesSafeDefaults() {
        assertEquals(RouterFilterConfig(), RouterFilterConfig.fromJson("{}"))
        assertEquals(RouterFilterConfig(), RouterFilterConfig.fromJson(""))
    }
}
