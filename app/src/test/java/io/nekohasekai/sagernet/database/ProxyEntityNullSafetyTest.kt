package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assert.assertThrows
import io.nekohasekai.sagernet.fmt.KryoConverters

class ProxyEntityNullSafetyTest {

    @Test
    fun serializationRejectsMissingBeanInsteadOfProducingAnUnusableBackup() {
        val entity = ProxyEntity(id = 42L, groupId = 10L, type = ProxyEntity.TYPE_SOCKS)
        assertThrows(IllegalStateException::class.java) {
            KryoConverters.serialize(entity)
        }
    }

    @Test
    fun missingChainBeanReportsErrorWithoutLookingUpAndroidResources() {
        val entity = ProxyEntity(id = 43L, groupId = 10L, type = ProxyEntity.TYPE_CHAIN)
        val error = assertThrows(IllegalStateException::class.java) { entity.requireBean() }
        assertTrue(error.message.orEmpty().contains("43"))
    }

    @Test
    fun displayTypeDoesNotThrowWhenBeansAreNull() {
        val types = listOf(
            ProxyEntity.TYPE_SOCKS to "SOCKS",
            ProxyEntity.TYPE_HTTP to "HTTP",
            ProxyEntity.TYPE_SS to "Shadowsocks",
            ProxyEntity.TYPE_SSR to "ShadowsocksR",
            ProxyEntity.TYPE_VMESS to "VMess",
            ProxyEntity.TYPE_TROJAN to "Trojan",
            ProxyEntity.TYPE_TROJAN_GO to "Trojan-Go",
            ProxyEntity.TYPE_MIERU to "Mieru",
            ProxyEntity.TYPE_NAIVE to "Naïve",
            ProxyEntity.TYPE_HYSTERIA to "Hysteria",
            ProxyEntity.TYPE_SSH to "SSH",
            ProxyEntity.TYPE_WG to "WireGuard",
            ProxyEntity.TYPE_TUIC to "TUIC",
            ProxyEntity.TYPE_JUICITY to "Juicity",
            ProxyEntity.TYPE_SHADOWTLS to "ShadowTLS",
            ProxyEntity.TYPE_ANYTLS to "AnyTLS",
            ProxyEntity.TYPE_NEKO to "Neko",
            ProxyEntity.TYPE_CONFIG to "Config",
            ProxyEntity.TYPE_SNELL to "Snell",
        )

        for ((typeId, expectedPrefix) in types) {
            val entity = ProxyEntity(id = 42L, groupId = 10L, type = typeId)
            val result = entity.displayType()
            assertTrue(
                "displayType for type $typeId returned '$result', expected to start with '$expectedPrefix'",
                result.startsWith(expectedPrefix)
            )
        }
    }

    @Test
    fun requireBeanProvidesHelpfulErrorMessageOnNullBean() {
        val entity = ProxyEntity(id = 1234L, groupId = 5678L, type = ProxyEntity.TYPE_SOCKS)
        try {
            entity.requireBean()
            fail("Expected requireBean to throw on null socksBean")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message should contain profile id: ${e.message}",
                e.message?.contains("1234") == true
            )
            assertTrue(
                "Error message should contain group id: ${e.message}",
                e.message?.contains("5678") == true
            )
        }
    }

    @Test
    fun displayNameOrFallbackHandlesCorruptedProxyWithoutCrashing() {
        val entity = ProxyEntity(id = 9999L, groupId = 111L, type = ProxyEntity.TYPE_SOCKS)
        val name = entity.displayNameOrFallback()
        assertEquals("Proxy 9999", name)
    }
}
