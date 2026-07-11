package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.olcrtc.buildOlcrtcArgs
import io.nekohasekai.sagernet.fmt.olcrtc.parseOlcrtc
import io.nekohasekai.sagernet.fmt.olcrtc.toUri
import org.junit.Assert.assertEquals
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
        val bean = OlcrtcBean().apply {
            carrier = "telemost"
            roomId = "room-7"
            clientId = "device-x"
            keyHex = key
            transport = "vp8channel"
            vp8Fps = 60
            vp8BatchSize = 64
            name = "my olc"
        }.apply { /* defaults already set explicitly above */ }

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
        val link = "olcrtc://zoom?datachannel@room-01#$key"
        assertThrows(IllegalArgumentException::class.java) { parseOlcrtc(link) }
    }

    @Test
    fun buildArgs_unsetDnsUsesFallback() {
        // A bean whose dns field was never set (the default) must not crash; the
        // fallback dns is used instead. Regression guard for the connect-time NPE.
        val bean = OlcrtcBean().apply {
            initializeDefaultValues()
            carrier = "jitsi"
            roomId = "room-01"
            clientId = "dev1"
            keyHex = key
        }
        val args = bean.buildOlcrtcArgs(
            port = 10800,
            protectPath = "/tmp/protect",
            socksUser = "",
            socksPass = "",
            verbose = false,
            dnsFallback = "9.9.9.9:53",
            readyTimeoutMs = 15_000L,
        )
        val dnsIdx = args.indexOf("-dns")
        assertTrue(dnsIdx >= 0)
        assertEquals("9.9.9.9:53", args[dnsIdx + 1])
    }

    @Test
    fun buildArgs_blankRoomThrowsArgumentError() {
        val bean = OlcrtcBean().apply {
            initializeDefaultValues()
            carrier = "jitsi"
            keyHex = key
        }
        assertThrows(IllegalArgumentException::class.java) {
            bean.buildOlcrtcArgs(
                port = 10800,
                protectPath = "/tmp/protect",
                socksUser = "",
                socksPass = "",
                verbose = false,
                dnsFallback = "9.9.9.9:53",
                readyTimeoutMs = 15_000L,
            )
        }
    }
}
