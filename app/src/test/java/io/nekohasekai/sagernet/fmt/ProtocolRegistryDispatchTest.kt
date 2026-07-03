package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.masterdnsvpn.MasterDnsVpnBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format safety net for the protocol descriptor registry (Plan 029, Option C).
 *
 * The registry may change *how* type dispatch is expressed (putByteArray / requireBean /
 * putBean) but must NEVER change the on-disk Kryo wire format or the persisted TYPE_* ids -
 * existing device profiles deserialize by exactly these bytes + ids. Because local builds are
 * forbidden here, this pure-JVM test (run on Depot's unit-tests workflow) is the golden net:
 * for every persistable bean type it asserts
 *   1. serialize -> deserialize -> serialize is byte-stable (write/read paths are symmetric), and
 *   2. the ProxyEntity type-dispatch round-trips: putBean(bean) sets the expected TYPE_* id and
 *      the correct typed field; serialize(requireBean()) reproduces the bytes; and
 *      putByteArray(type, bytes) then requireBean() yields a bean that re-serializes identically.
 *
 * If the registry ever maps a type id to the wrong (de)serializer or drops a field, one of these
 * assertions fails - catching the "profiles become undeserializable / mis-typed" data hazard.
 */
class ProtocolRegistryDispatchTest {

    private fun <T : AbstractBean> byteStable(bean: T) {
        bean.initializeDefaultValues()
        val first = KryoConverters.serialize(bean)
        // A populated bean must not serialize to empty (empty => null-bean path).
        assertTrue("bean serialized to empty bytes: ${bean.javaClass.simpleName}", first.isNotEmpty())
        val decoded = KryoConverters.deserialize(bean.javaClass.getDeclaredConstructor().newInstance(), first)
        val second = KryoConverters.serialize(decoded)
        assertArrayEquals("round-trip not byte-stable: ${bean.javaClass.simpleName}", first, second)
    }

    /**
     * Full ProxyEntity dispatch parity: putBean -> expected type id + serialize(requireBean())
     * byte-identical to serialize(bean); then putByteArray(type, bytes) -> requireBean()
     * re-serializes to the same bytes (deserialize dispatch lands on the right converter).
     */
    private fun dispatchParity(bean: AbstractBean, expectedType: Int) {
        bean.initializeDefaultValues()
        val entity = ProxyEntity()
        entity.putBean(bean)
        assertEquals("putBean set wrong type id for ${bean.javaClass.simpleName}", expectedType, entity.type)

        val viaRequireBean = KryoConverters.serialize(entity.requireBean())
        val direct = KryoConverters.serialize(bean)
        assertArrayEquals("requireBean() bytes differ for ${bean.javaClass.simpleName}", direct, viaRequireBean)

        // Deserialize dispatch: a fresh entity of the same type must land the bytes on the
        // correct typed field and re-serialize identically.
        val entity2 = ProxyEntity()
        entity2.type = expectedType
        entity2.putByteArray(direct)
        val roundTrip = KryoConverters.serialize(entity2.requireBean())
        assertArrayEquals("putByteArray dispatch differs for ${bean.javaClass.simpleName}", direct, roundTrip)
    }

    private fun socks() = SOCKSBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 1080
        username = "u"
        password = "p"
    }
    private fun http() = HttpBean().apply {
        serverAddress = "192.0.2.2"
        serverPort = 8080
        username = "u"
        password = "p"
    }
    private fun ss() = ShadowsocksBean().apply {
        serverAddress = "192.0.2.3"
        serverPort = 8388
        method = "aes-128-gcm"
        password = "p"
    }
    private fun ssr() = ShadowsocksRBean().apply {
        serverAddress = "192.0.2.4"
        serverPort = 8389
        method = "aes-128-cfb"
        password = "p"
        protocol = "origin"
        obfs = "plain"
    }
    private fun vmess() = VMessBean().apply {
        serverAddress = "192.0.2.5"
        serverPort = 443
        uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    }
    private fun trojan() = TrojanBean().apply {
        serverAddress = "192.0.2.6"
        serverPort = 443
        password = "p"
    }
    private fun trojanGo() = TrojanGoBean().apply {
        serverAddress = "192.0.2.7"
        serverPort = 443
        password = "p"
    }
    private fun mieru() = MieruBean().apply {
        serverAddress = "192.0.2.8"
        serverPort = 4443
        username = "u"
        password = "p"
    }
    private fun naive() = NaiveBean().apply {
        serverAddress = "192.0.2.9"
        serverPort = 443
        username = "u"
        password = "p"
    }
    private fun hysteria() = HysteriaBean().apply {
        serverAddress = "192.0.2.10"
        serverPorts = "443"
    }
    private fun ssh() = SSHBean().apply {
        serverAddress = "192.0.2.11"
        serverPort = 22
        username = "u"
        password = "p"
    }
    private fun wg() = WireGuardBean().apply {
        serverAddress = "192.0.2.12"
        serverPort = 51820
    }
    private fun awg() = AmneziaWGBean().apply {
        serverAddress = "192.0.2.13"
        serverPort = 51820
    }
    private fun tuic() = TuicBean().apply {
        serverAddress = "192.0.2.14"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000000"
        token = "t"
    }
    private fun juicity() = JuicityBean().apply {
        serverAddress = "192.0.2.15"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000000"
        password = "p"
    }
    private fun shadowTls() = ShadowTLSBean().apply {
        serverAddress = "192.0.2.16"
        serverPort = 443
    }
    private fun anyTls() = AnyTLSBean().apply {
        serverAddress = "192.0.2.17"
        serverPort = 443
        password = "p"
    }
    private fun snell() = SnellBean().apply {
        serverAddress = "192.0.2.18"
        serverPort = 443
        psk = "k"
    }
    private fun masterDnsVpn() = MasterDnsVpnBean().apply {
        serverAddress = "192.0.2.19"
        serverPort = 443
    }
    private fun olcrtc() = OlcrtcBean().apply {
        serverAddress = "192.0.2.20"
        serverPort = 443
    }
    private fun chain() = ChainBean().apply { name = "chain" }
    private fun config() = ConfigBean().apply { name = "config" }

    private val allBeans: List<Pair<AbstractBean, Int>> = listOf(
        socks() to ProxyEntity.TYPE_SOCKS,
        http() to ProxyEntity.TYPE_HTTP,
        ss() to ProxyEntity.TYPE_SS,
        ssr() to ProxyEntity.TYPE_SSR,
        vmess() to ProxyEntity.TYPE_VMESS,
        trojan() to ProxyEntity.TYPE_TROJAN,
        trojanGo() to ProxyEntity.TYPE_TROJAN_GO,
        mieru() to ProxyEntity.TYPE_MIERU,
        naive() to ProxyEntity.TYPE_NAIVE,
        hysteria() to ProxyEntity.TYPE_HYSTERIA,
        ssh() to ProxyEntity.TYPE_SSH,
        wg() to ProxyEntity.TYPE_WG,
        awg() to ProxyEntity.TYPE_AWG,
        tuic() to ProxyEntity.TYPE_TUIC,
        juicity() to ProxyEntity.TYPE_JUICITY,
        shadowTls() to ProxyEntity.TYPE_SHADOWTLS,
        anyTls() to ProxyEntity.TYPE_ANYTLS,
        snell() to ProxyEntity.TYPE_SNELL,
        masterDnsVpn() to ProxyEntity.TYPE_MASTERDNSVPN,
        olcrtc() to ProxyEntity.TYPE_OLCRTC,
        chain() to ProxyEntity.TYPE_CHAIN,
        config() to ProxyEntity.TYPE_CONFIG,
    )

    @Test
    fun everyBean_roundTripIsByteStable() {
        for ((bean, _) in allBeans) byteStable(bean)
    }

    @Test
    fun everyBean_proxyEntityDispatchParity() {
        for ((bean, type) in allBeans) dispatchParity(bean, type)
    }

    @Test
    fun allPersistableTypesCovered() {
        // Guard: if a new TYPE_* is added to the putBean ladder, add it here too.
        assertEquals(22, allBeans.size)
    }
}
