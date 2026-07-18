package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Test

class WireGuardFmtTest {

    @Test
    fun outboundNormalizesBareAddressesFromExistingProfiles() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            localAddress = "172.16.0.2\nfd00::2"
        }

        val outbound = buildSingBoxOutboundWireguardBean(bean)

        assertEquals(listOf("172.16.0.2/32", "fd00::2/128"), outbound.local_address)
        assertEquals("172.16.0.2\nfd00::2", bean.localAddress)
    }
}
