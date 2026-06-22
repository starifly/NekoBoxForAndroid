package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonParser
import moe.matsuri.nb4a.SingBoxOptions.Outbound_URLTestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancerOptionsTest {

    @Test
    fun serializesDurationAndStrategyOptionsForSingBox() {
        val outbound = Outbound_URLTestOptions().apply {
            type = "urltest"
            tag = "waterfall"
            outbounds = listOf("first", "fastest")
            url = "http://www.gstatic.com/generate_204"
            interval = "10m"
            idle_timeout = "30m"
            timeout = "3000ms"
            tolerance = 50
            strategy = "priority"
            managed_by_parent = true
        }

        val json = JsonParser.parseString(Gson().toJson(outbound)).asJsonObject

        assertEquals("10m", json["interval"].asString)
        assertEquals("30m", json["idle_timeout"].asString)
        assertEquals("3000ms", json["timeout"].asString)
        assertEquals("priority", json["strategy"].asString)
        assertTrue(json["managed_by_parent"].asBoolean)
    }
}
