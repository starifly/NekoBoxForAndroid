package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.olcrtc.buildOlcrtcArgs
import io.nekohasekai.sagernet.fmt.olcrtc.carrierHost
import io.nekohasekai.sagernet.fmt.olcrtc.parseOlcrtc
import io.nekohasekai.sagernet.fmt.olcrtc.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and parsing tests for the olcrtc:// URI codec (parseOlcrtc / toUri).
 */
class OlcrtcFmtTest {

    private val key = "0".repeat(64)

    @Test
    fun parse_jitsi_datachannel_urlRoom() {
        val link = "olcrtc://jitsi?datachannel@https://meet.example.org/myroom#$key\$RU sub"
        val bean = parseOlcrtc(link)
        assertEquals("jitsi", bean.carrier)
        assertEquals("datachannel", bean.transport)
        assertEquals("https://meet.example.org/myroom", bean.roomId)
        assertEquals(key, bean.keyHex)
        assertEquals("RU sub", bean.name)
        assertEquals("", bean.clientId)
    }

    @Test
    fun parse_vp8_withPayloadAndCid() {
        val link = "olcrtc://wbstream?vp8channel<vp8-fps=60&vp8-batch=64&cid=dev42>@room-01#$key\$DE"
        val bean = parseOlcrtc(link)
        assertEquals("wbstream", bean.carrier)
        assertEquals("vp8channel", bean.transport)
        assertEquals("room-01", bean.roomId)
        assertEquals(60, bean.vp8Fps)
        assertEquals(64, bean.vp8BatchSize)
        assertEquals("dev42", bean.clientId)
        assertEquals("DE", bean.name)
    }

    @Test
    fun roundTrip_preservesFieldsAndCid() {
        val bean = validBean(
            carrier = "telemost",
            clientId = "device-x",
            vp8Fps = 60,
            vp8Batch = 64,
        ).apply { name = "my olc" }

        val uri = bean.toUri()
        assertTrue(uri.startsWith("olcrtc://telemost?vp8channel"))
        assertTrue(uri.contains("cid=device-x"))

        val parsed = parseOlcrtc(uri)
        assertEquals(bean.carrier, parsed.carrier)
        assertEquals(bean.roomId, parsed.roomId)
        assertEquals(bean.clientId, parsed.clientId)
        assertEquals(bean.keyHex, parsed.keyHex)
        assertEquals(bean.transport, parsed.transport)
        assertEquals(bean.vp8Fps, parsed.vp8Fps)
        assertEquals(bean.vp8BatchSize, parsed.vp8BatchSize)
        assertEquals(bean.name, parsed.name)
    }

    @Test
    fun roundTrip_allowsBlankUpstreamClientId() {
        val uri = validBean(clientId = "").toUri()

        assertFalse(uri.contains("cid="))
        assertEquals("", parseOlcrtc(uri).clientId)
    }

    @Test
    fun parse_rejectsMissingKey() {
        val link = "olcrtc://jitsi?datachannel@room-01"
        assertThrows(IllegalArgumentException::class.java) { parseOlcrtc(link) }
    }

    @Test
    fun parse_rejectsBadKeyLength() {
        val link = "olcrtc://jitsi?datachannel@room-01#deadbeef"
        assertThrows(IllegalArgumentException::class.java) { parseOlcrtc(link) }
    }

    @Test
    fun parse_rejectsNonScheme() {
        assertThrows(IllegalArgumentException::class.java) { parseOlcrtc("https://example.org") }
    }

    @Test
    fun parse_rejectsUnsupportedCarrier() {
        val link = "olcrtc://unsupported?datachannel@room-01#$key"
        assertThrows(IllegalArgumentException::class.java) { parseOlcrtc(link) }
    }

    @Test
    fun parse_rejectsClientIdReservedDelimiter() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseOlcrtc("olcrtc://jitsi?vp8channel<cid=device=7>@review-4821#$key")
        }

        assertFalse(error.message.orEmpty().contains("device=7"))
    }

    @Test
    fun parse_enforcesCarrierTransportMatrix() {
        val accepted = listOf(
            "jitsi" to "vp8channel",
            "jitsi" to "datachannel",
            "telemost" to "vp8channel",
            "wbstream" to "vp8channel",
        )
        accepted.forEach { (carrier, transport) ->
            val parsed = parseOlcrtc("olcrtc://$carrier?$transport@review-4821#$key")
            assertEquals(carrier, parsed.carrier)
            assertEquals(transport, parsed.transport)
        }

        listOf("telemost", "wbstream").forEach { carrier ->
            assertThrows(IllegalArgumentException::class.java) {
                parseOlcrtc("olcrtc://$carrier?datachannel@review-4821#$key")
            }
        }
    }

    @Test
    fun parse_enforcesVp8BoundsAndDefaults() {
        val minimum = parseOlcrtc(
            "olcrtc://jitsi?vp8channel<vp8-fps=1&vp8-batch=1>@review-4821#$key",
        )
        assertEquals(1, minimum.vp8Fps)
        assertEquals(1, minimum.vp8BatchSize)

        val maximum = parseOlcrtc(
            "olcrtc://jitsi?vp8channel<vp8-fps=120&vp8-batch=64>@review-4821#$key",
        )
        assertEquals(120, maximum.vp8Fps)
        assertEquals(64, maximum.vp8BatchSize)

        val defaults = parseOlcrtc("olcrtc://jitsi?vp8channel@review-4821#$key")
        assertEquals(30, defaults.vp8Fps)
        assertEquals(8, defaults.vp8BatchSize)
    }

    @Test
    fun datachannel_ignoresUnusedVp8Values() {
        val bean = validBean(transport = "datachannel").apply {
            vp8Fps = null
            vp8BatchSize = 999
        }

        assertFalse(bean.toUri().contains("vp8-"))
        val args = buildArgs(bean)
        assertEquals("30", args[args.indexOf("-vp8-fps") + 1])
        assertEquals("8", args[args.indexOf("-vp8-batch") + 1])
    }

    @Test
    fun parse_rejectsMalformedOrOutOfRangeVp8Values() {
        val payloads = listOf(
            "vp8-fps=abc",
            "vp8-fps=0",
            "vp8-fps=121",
            "vp8-fps=-1",
            "vp8-batch=abc",
            "vp8-batch=0",
            "vp8-batch=65",
            "vp8-batch=-1",
            "vp8-fps",
            " =ignored",
        )
        payloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                parseOlcrtc("olcrtc://jitsi?vp8channel<$payload>@review-4821#$key")
            }
        }
    }

    @Test
    fun parse_rejectsTextAfterTransportPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOlcrtc("olcrtc://jitsi?vp8channel<cid=device-7>junk@review-4821#$key")
        }
    }

    @Test
    fun buildArgs_unsetDnsUsesFallback() {
        val args = buildArgs(validBean(dns = ""))
        val dnsIdx = args.indexOf("-dns")
        assertTrue(dnsIdx >= 0)
        assertEquals("9.9.9.9:53", args[dnsIdx + 1])
    }

    @Test
    fun buildArgs_acceptsIpLiteralDnsEndpoints() {
        val endpoints = listOf(
            "0.0.0.0:1",
            "9.9.9.9:53",
            "255.255.255.255:65535",
            "[2001:4860:4860::8888]:53",
            "[::ffff:192.0.2.1]:53",
            "[fe80::1%wlan0]:53",
        )
        endpoints.forEach { endpoint ->
            val args = buildArgs(validBean(dns = endpoint))
            assertEquals(endpoint, args[args.indexOf("-dns") + 1])
        }
    }

    @Test
    fun buildArgs_rejectsInvalidDnsEndpointsWithoutEchoingThem() {
        val endpoints = listOf(
            "dns.example:53",
            "9.9.9.9",
            "2001:4860:4860::8888:53",
            ":53",
            "[]:53",
            "9.9.9.9:",
            "9.9.9.9:dns",
            "9.9.9.9:0",
            "9.9.9.9:65536",
            "999.1.1.1:53",
            "09.9.9.9:53",
            "[2001:db8:::1]:53",
            "[192.0.2.1::]:53",
            "[fe80::1%]:53",
        )
        endpoints.forEach { endpoint ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                buildArgs(validBean(dns = endpoint))
            }
            assertFalse(error.message.orEmpty().contains(endpoint))
        }
    }

    @Test
    fun buildArgs_rejectsBlankClientAndUnsupportedTransport() {
        assertThrows(IllegalArgumentException::class.java) {
            buildArgs(validBean(clientId = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildArgs(validBean(transport = "futurechannel"))
        }
    }

    @Test
    fun buildArgs_blankRoomThrowsArgumentError() {
        assertThrows(IllegalArgumentException::class.java) {
            buildArgs(validBean().apply { roomId = "" })
        }
    }

    @Test
    fun toUri_rejectsRoomPayloadDelimiter() {
        assertThrows(IllegalArgumentException::class.java) {
            validBean(roomId = "review<4821").toUri()
        }
    }

    @Test
    fun toUri_rejectsUnsupportedCarrierTransportCombination() {
        assertThrows(IllegalArgumentException::class.java) {
            validBean(carrier = "telemost", transport = "datachannel").toUri()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validBean(transport = "futurechannel").toUri()
        }
    }

    @Test
    fun uninitializedBeanFailsWithArgumentErrorInsteadOfNullPointer() {
        val bean = OlcrtcBean()

        assertThrows(IllegalArgumentException::class.java) { bean.toUri() }
        assertThrows(IllegalArgumentException::class.java) { buildArgs(bean) }
    }

    @Test
    fun toUri_treatsNullOptionalJavaFieldsAsBlank() {
        val bean = OlcrtcBean().apply {
            carrier = "jitsi"
            transport = "vp8channel"
            roomId = "review-4821"
            keyHex = key
            vp8Fps = 30
            vp8BatchSize = 8
        }

        assertEquals("olcrtc://jitsi?vp8channel@review-4821#$key", bean.toUri())
    }

    @Test
    fun carrierHost_extractsHostnameAndBracketedIpv6() {
        val cases = mapOf(
            "https://meet.example.org/room" to "meet.example.org",
            "https://meet.example.org:8443/room" to "meet.example.org",
            "meet.example.org/room" to "meet.example.org",
            "https://meet_private.example/room" to "meet_private.example",
            "https://[2001:db8::1]/room" to "2001:db8::1",
            "https://[2001:db8::1]:8443/room" to "2001:db8::1",
            "[2001:db8::1]/room" to "2001:db8::1",
        )
        cases.forEach { (room, expectedHost) ->
            assertEquals(expectedHost, validBean(roomId = room).carrierHost())
        }
    }

    @Test
    fun carrierHost_preservesFixedCarrierHosts() {
        assertEquals("telemost.yandex.ru", validBean(carrier = "telemost").carrierHost())
        assertEquals("stream.wb.ru", validBean(carrier = "wbstream").carrierHost())
        assertNull(validBean().apply { carrier = "unsupported" }.carrierHost())
        assertNull(OlcrtcBean().apply { carrier = "jitsi" }.carrierHost())
    }

    private fun validBean(
        carrier: String = "jitsi",
        transport: String = "vp8channel",
        roomId: String = "review-4821",
        clientId: String = "device-7",
        dns: String = "",
        vp8Fps: Int = 30,
        vp8Batch: Int = 8,
    ) = OlcrtcBean().apply {
        initializeDefaultValues()
        this.carrier = carrier
        this.transport = transport
        this.roomId = roomId
        this.clientId = clientId
        keyHex = key
        dnsServer = dns
        this.vp8Fps = vp8Fps
        vp8BatchSize = vp8Batch
    }

    private fun buildArgs(bean: OlcrtcBean) = bean.buildOlcrtcArgs(
        port = 10800,
        protectPath = "/tmp/protect",
        socksUser = "",
        socksPass = "",
        verbose = false,
        dnsFallback = "9.9.9.9:53",
        readyTimeoutMs = 15_000L,
    )
}
