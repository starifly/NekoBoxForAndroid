package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for IniConfig (Plan 020) — the internal INI reader replacing org.ini4j for parsing
 * WireGuard / AmneziaWG `.conf` files. Pure JVM (no Android / libcore). These pin the exact
 * behaviors parseWireGuard / parseAmneziaWG rely on so the reader is behavior-equivalent to
 * ini4j on real configs: repeated sections, multi-value keys, comments, and `key = value`
 * whitespace handling.
 */
class IniConfigTest {

    private val wgConf = """
        [Interface]
        # a comment
        Address = 10.0.0.2/32
        Address = fd00::2/128
        PrivateKey = aPrivateKeyValue=
        MTU = 1420

        [Peer]
        PublicKey = peerOnePub=
        PresharedKey = pskOne=
        Endpoint = 192.0.2.1:51820

        [Peer]
        PublicKey = peerTwoPub=
        Endpoint = 192.0.2.2:51821
    """.trimIndent()

    @Test
    fun firstSection_andFirstValue() {
        val ini = IniConfig.parse(wgConf)
        val iface = ini["Interface"]!!
        assertEquals("aPrivateKeyValue=", iface["PrivateKey"]) // value may itself contain '='
        assertEquals("1420", iface["MTU"])
    }

    @Test
    fun multiValueKey_getAll() {
        val iface = IniConfig.parse(wgConf)["Interface"]!!
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), iface.getAll("Address"))
    }

    @Test
    fun repeatedSections_getAll() {
        val peers = IniConfig.parse(wgConf).getAll("Peer")!!
        assertEquals(2, peers.size)
        assertEquals("peerOnePub=", peers[0]["PublicKey"])
        assertEquals("192.0.2.1:51820", peers[0]["Endpoint"])
        assertEquals("peerTwoPub=", peers[1]["PublicKey"])
        // PresharedKey absent on the second peer -> null (parseWireGuard relies on this).
        assertNull(peers[1]["PresharedKey"])
    }

    @Test
    fun missingSection_isNull() {
        assertNull(IniConfig.parse(wgConf)["Nonexistent"])
        assertNull(IniConfig.parse(wgConf).getAll("Nonexistent"))
    }

    @Test
    fun commentsAndBlankLines_ignored_andSemicolonComment() {
        val conf = """
            ; semicolon comment
            [Interface]

            Address=10.0.0.5/32
            # hash comment
            PrivateKey=key
        """.trimIndent()
        val iface = IniConfig.parse(conf)["Interface"]!!
        // No spaces around '=' must also work.
        assertEquals(listOf("10.0.0.5/32"), iface.getAll("Address"))
        assertEquals("key", iface["PrivateKey"])
    }

    @Test
    fun amneziaObfuscationKeys_present() {
        val conf = """
            [Interface]
            Address = 10.0.0.2/32
            PrivateKey = k
            Jc = 4
            S1 = 50
            H1 = 1234567890
            [Peer]
            PublicKey = p
            Endpoint = 192.0.2.1:51820
        """.trimIndent()
        val iface = IniConfig.parse(conf)["Interface"]!!
        assertEquals("4", iface["Jc"])
        assertEquals("50", iface["S1"])
        assertEquals("1234567890", iface["H1"])
        assertNull(iface["Jmin"])
    }
}
