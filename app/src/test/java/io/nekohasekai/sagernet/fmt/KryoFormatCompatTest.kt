package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-compatibility guard for the kryo bump (Plan 021).
 *
 * The beans serialize via kryo's low-level ByteBufferInput/Output (varint + string encoding)
 * only - there is no Kryo instance / registration / FieldSerializer in use - so the on-disk
 * wire format is just that encoding. This test freezes the exact bytes a known SOCKSBean
 * produced under kryo 5.2.1 (the pre-bump version) and asserts:
 *   1. those golden bytes still DESERIALIZE to the same bean under the new kryo version, and
 *   2. re-serializing produces the identical bytes.
 *
 * If a kryo upgrade ever changed the wire format, this fails - preventing the
 * "every stored proxy config becomes undeserializable" data-loss hazard.
 */
class KryoFormatCompatTest {

    private val goldenSocksHex =
        "020000003139322e302e322e31b03804000002000000757365f2706173f30101000000676f6c64656e2d736f636bf38181"

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun golden5_2_1_bytes_stillDeserialize() {
        val decoded = KryoConverters.socksDeserialize(goldenSocksHex.hexToBytes())!!
        assertEquals("192.0.2.10", decoded.serverAddress)
        assertEquals(1080, decoded.serverPort)
        assertEquals("user", decoded.username)
        assertEquals("pass", decoded.password)
        assertEquals(2, decoded.protocol)
        assertEquals(true, decoded.sUoT)
        assertEquals("golden-socks", decoded.name)
    }

    @Test
    fun reserialize_matchesGoldenBytes() {
        // Re-encoding the golden-equivalent bean must reproduce the exact 5.2.1 bytes,
        // proving the wire format is unchanged by the kryo bump.
        val bean = SOCKSBean().apply {
            serverAddress = "192.0.2.10"
            serverPort = 1080
            username = "user"
            password = "pass"
            protocol = 2
            sUoT = true
            name = "golden-socks"
            initializeDefaultValues()
        }
        assertArrayEquals(goldenSocksHex.hexToBytes(), KryoConverters.serialize(bean))
    }
}
