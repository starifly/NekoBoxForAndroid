package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicationTest {

    @Test
    fun olcrtcDifferentRooms_areNotDuplicates() {
        val first = olcrtc("standup-4821", "11".repeat(32))
        val second = olcrtc("review-sync", "22".repeat(32))
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertTrue(values.add(wrap(second)))
    }

    @Test
    fun socksDifferentPasswords_areNotDuplicates() {
        val first = socks("alpha")
        val second = socks("beta")
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertTrue(values.add(wrap(second)))
    }

    @Test
    fun sameValuesDifferentNames_areDuplicates() {
        val first = shadowsocks("first")
        val second = shadowsocks("second")
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertFalse(values.add(wrap(second)))
    }

    @Test
    fun configBeanIdentity_remainsConfigStringOnly() {
        val first = ConfigBean().apply {
            name = "first"
            config = "{\"type\":\"direct\"}"
            initializeDefaultValues()
        }
        val same = ConfigBean().apply {
            name = "second"
            config = "{\"type\":\"direct\"}"
            initializeDefaultValues()
        }
        val different = ConfigBean().apply {
            name = "third"
            config = "{\"type\":\"block\"}"
            initializeDefaultValues()
        }
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertFalse(values.add(wrap(same)))
        assertTrue(values.add(wrap(different)))
    }

    @Test
    fun differentTypesAtSameEndpoint_areNotDuplicates() {
        val socks = SOCKSBean().apply {
            serverAddress = "192.0.2.20"
            serverPort = 8080
            initializeDefaultValues()
        }
        val http = HttpBean().apply {
            serverAddress = "192.0.2.20"
            serverPort = 8080
            initializeDefaultValues()
        }
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(socks)))
        assertTrue(values.add(wrap(http)))
    }

    private fun olcrtc(roomId: String, keyHex: String) = OlcrtcBean().apply {
        serverAddress = "olcrtc"
        serverPort = 1
        this.roomId = roomId
        clientId = "client-7"
        this.keyHex = keyHex
        initializeDefaultValues()
    }

    private fun socks(password: String) = SOCKSBean().apply {
        serverAddress = "192.0.2.10"
        serverPort = 1080
        username = "reader"
        this.password = password
        initializeDefaultValues()
    }

    private fun shadowsocks(displayName: String) = ShadowsocksBean().apply {
        serverAddress = "example.com"
        serverPort = 443
        method = "aes-256-gcm"
        password = "alpha"
        name = displayName
        initializeDefaultValues()
    }

    private fun wrap(bean: io.nekohasekai.sagernet.fmt.AbstractBean) = Protocols.Deduplication(bean)
}
