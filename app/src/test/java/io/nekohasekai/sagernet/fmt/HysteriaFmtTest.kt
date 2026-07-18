package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2Json
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    // Structurally valid ECHConfigList using an X25519 public key and example.com public name.
    private val echConfig = "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA"

    private fun hysteria2Bean() = HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = "example.com"
        serverPorts = "443"
        authPayload = "password"
        sni = "real.example.com"
        initializeDefaultValues()
    }

    private fun buildHysteria2(bean: HysteriaBean) =
        buildSingBoxOutboundHysteriaBean(bean) as SingBoxOptions.Outbound_Hysteria2Options

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

    @Test
    fun parseHysteria2Json_mapsExplicitEchConfig() {
        val bean = JSONObject(
            """{ "server": "h:443", "auth": "a", "tls": { "sni": "real.example.com", "ech": "$echConfig" } }""",
        ).parseHysteria2Json()

        assertEquals(true, bean.enableECH)
        assertEquals(echConfig, bean.echConfig)
    }

    @Test
    fun parseHysteria2Uri_roundTripsExplicitEchConfig() {
        // Some producers place standard base64 directly in the query, including an
        // unescaped '+'. Preserve it rather than applying form-style '+' decoding.
        val parsed = parseHysteria2(
            "hy2://password@example.com:443/?sni=real.example.com&ech=$echConfig#ECH",
        ).apply { initializeDefaultValues() }

        assertEquals(true, parsed.enableECH)
        assertEquals(echConfig, parsed.echConfig)

        val exported = parsed.toUri()
        assertTrue(exported.contains("%2B"))
        val reparsed = parseHysteria2(exported).apply { initializeDefaultValues() }
        assertEquals(true, reparsed.enableECH)
        assertEquals(echConfig, reparsed.echConfig)
        assertEquals("real.example.com", reparsed.sni)
    }

    @Test
    fun disabledEch_isOmittedFromUriAndOutbound() {
        val bean = hysteria2Bean().apply {
            enableECH = false
            echConfig = this@HysteriaFmtTest.echConfig
        }

        assertFalse(bean.toUri().contains("ech="))
        assertNull(buildHysteria2(bean).tls.ech)
    }

    @Test
    fun enabledEch_wrapsRawBase64ForSingBox() {
        val bean = hysteria2Bean().apply {
            enableECH = true
            echConfig = this@HysteriaFmtTest.echConfig
        }

        val ech = buildHysteria2(bean).tls.ech
        assertEquals(true, ech.enabled)
        assertEquals(
            listOf(
                "-----BEGIN ECH CONFIGS-----",
                echConfig,
                "-----END ECH CONFIGS-----",
            ),
            ech.config,
        )
    }

    @Test
    fun enabledEch_acceptsAndCanonicalizesPem() {
        val wrappedConfig = echConfig.chunked(32).joinToString("\n")
        val bean = hysteria2Bean().apply {
            enableECH = true
            echConfig = """
                -----BEGIN ECH CONFIGS-----
                $wrappedConfig
                -----END ECH CONFIGS-----
            """.trimIndent()
        }

        val ech = buildHysteria2(bean).tls.ech
        assertEquals(
            listOf(
                "-----BEGIN ECH CONFIGS-----",
                echConfig,
                "-----END ECH CONFIGS-----",
            ),
            ech.config,
        )
    }

    @Test
    fun enabledEch_requiresExplicitValidConfig() {
        val missing = hysteria2Bean().apply {
            enableECH = true
            echConfig = ""
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundHysteriaBean(missing)
        }

        val missingFromUninitializedJavaBean = hysteria2Bean().apply {
            enableECH = true
            echConfig = null
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundHysteriaBean(missingFromUninitializedJavaBean)
        }

        val malformedStructure = hysteria2Bean().apply {
            enableECH = true
            echConfig = "AQIDBA=="
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundHysteriaBean(malformedStructure)
        }

        val malformedBase64 = hysteria2Bean().apply {
            enableECH = true
            echConfig = "not base64!"
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundHysteriaBean(malformedBase64)
        }
    }

    @Test
    fun hysteria1_ignoresEchFields() {
        val bean = hysteria2Bean().apply {
            protocolVersion = 1
            authPayloadType = HysteriaBean.TYPE_STRING
            enableECH = true
            echConfig = this@HysteriaFmtTest.echConfig
        }

        val outbound = buildSingBoxOutboundHysteriaBean(bean) as SingBoxOptions.Outbound_HysteriaOptions
        assertNull(outbound.tls.ech)
    }
}
