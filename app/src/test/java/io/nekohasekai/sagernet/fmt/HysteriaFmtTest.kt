package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Hysteria 2 JSON config import (Plan 022). Uses Robolectric because org.json
 * is an Android-framework class (stubbed on the bare JVM). Pinned to the app's targetSdk,
 * which Robolectric 4.16 bundles (4.16 supports SDK 36 / Baklava).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HysteriaFmtTest {

    @Test
    fun parseHysteria2Json_mapsCoreFields() {
        val json = JSONObject(
            """
            {
              "server": "example.com:8443",
              "auth": "my-secret-auth",
              "tls": { "sni": "cdn.example.com", "insecure": true },
              "bandwidth": { "up": 50, "down": 200 }
            }
            """.trimIndent(),
        )
        val bean = json.parseHysteria2Json()
        assertEquals(2, bean.protocolVersion)
        assertEquals("example.com", bean.serverAddress)
        assertEquals("8443", bean.serverPorts)
        assertEquals("my-secret-auth", bean.authPayload)
        assertEquals(HysteriaBean.TYPE_STRING, bean.authPayloadType)
        assertEquals("cdn.example.com", bean.sni)
        assertEquals(true, bean.allowInsecure)
        assertEquals(50, bean.uploadMbps)
        assertEquals(200, bean.downloadMbps)
    }

    @Test
    fun parseHysteria2Json_salamanderObfs() {
        val json = JSONObject(
            """
            {
              "server": "1.2.3.4:443",
              "auth": "pw",
              "obfs": { "type": "salamander", "salamander": { "password": "obfspw" } }
            }
            """.trimIndent(),
        )
        val bean = json.parseHysteria2Json()
        assertEquals(HysteriaBean.OBFS_SALAMANDER, bean.hysteria2ObfsType)
        assertEquals("obfspw", bean.obfuscation)
    }

    @Test
    fun parseHysteria2Json_geckoObfs() {
        val json = JSONObject(
            """
            {
              "server": "1.2.3.4:443",
              "auth": "pw",
              "obfs": { "type": "gecko", "gecko": { "password": "g", "min_packet_size": 100, "max_packet_size": 900 } }
            }
            """.trimIndent(),
        )
        val bean = json.parseHysteria2Json()
        assertEquals(HysteriaBean.OBFS_GECKO, bean.hysteria2ObfsType)
        assertEquals("g", bean.obfuscation)
        assertEquals(100, bean.geckoMinPacketSize)
        assertEquals(900, bean.geckoMaxPacketSize)
    }

    @Test
    fun parseHysteria2Json_bareHostDefaultsPort443() {
        val bean = JSONObject("""{ "server": "example.com", "auth": "a" }""").parseHysteria2Json()
        assertEquals("example.com", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
    }

    @Test
    fun parseHysteria2Json_bandwidthStringUnits() {
        val bean = JSONObject(
            """{ "server": "h:443", "auth": "a", "bandwidth": { "up": "100 mbps", "down": "1 gbps" } }""",
        ).parseHysteria2Json()
        assertEquals(100, bean.uploadMbps)
        assertEquals(1000, bean.downloadMbps)
    }
}
