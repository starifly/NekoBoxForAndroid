package io.nekohasekai.sagernet.fmt.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardAddressTest {

    @Test
    fun addsHostPrefixesToBareIpv4AndIpv6() {
        assertEquals("172.16.0.2/32", normalizeWireGuardLocalAddress("172.16.0.2"))
        assertEquals("fd00::2/128", normalizeWireGuardLocalAddress("fd00::2"))
    }

    @Test
    fun preservesExplicitValidPrefixes() {
        assertEquals("172.16.0.2/24", normalizeWireGuardLocalAddress("172.16.0.2/24"))
        assertEquals("fd00::2/64", normalizeWireGuardLocalAddress("fd00::2/64"))
    }

    @Test
    fun normalizesMixedCommaAndLineSeparatedAddresses() {
        assertEquals(
            listOf("172.16.0.2/32", "10.0.0.2/24", "fd00::2/128", "2001:db8::2/64"),
            normalizeWireGuardLocalAddresses(
                "172.16.0.2, 10.0.0.2/24\nfd00::2, 2001:db8::2/64",
            ),
        )
    }

    @Test
    fun rejectsInvalidAddressesAndPrefixes() {
        listOf(
            "",
            "example.com",
            "172.16.0.999",
            "172.16.0.2/33",
            "fd00::2/129",
            "fd00::2/not-a-prefix",
        ).forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeWireGuardLocalAddresses(address)
            }
        }
    }
}
