package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.canUseSingBox
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
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

/**
 * Single source of truth for per-protocol type dispatch on [ProxyEntity].
 *
 * Adding or maintaining a protocol used to mean editing a dozen parallel `when(type)` /
 * `when(bean)` ladders in lockstep; miss one and you get a silent runtime gap. This registry
 * declares each protocol ONCE as a [ProtocolDescriptor] and the ladders route through it, so a
 * missing entry is a fast startup failure rather than a silent omission.
 *
 * WIRE-FORMAT INVARIANT (device-data critical): the registry only changes *how* dispatch is
 * expressed. It MUST NOT change the on-disk Kryo wire format or the persisted [ProxyEntity.type]
 * ids - existing device profiles deserialize by exactly those bytes + ids. Each descriptor's
 * [deserialize] references the same `KryoConverters.*Deserialize` the old `putByteArray` ladder
 * used, and [getBean]/[setBean] read/write the same typed field. The [ProtocolRegistryDispatchTest]
 * golden net (all bean types) guards this.
 *
 * TO ADD A PROTOCOL: add its `TYPE_*` id, its typed field on [ProxyEntity], and ONE descriptor
 * entry below. The `init` sanity check fails fast if a descriptor references a duplicate id.
 */
class ProtocolDescriptor(
    /** Persisted type id. NEVER change an existing value - it is stored in every profile row. */
    val type: Int,
    /** Deserialize the Kryo blob into this protocol's bean (same converter as the old ladder). */
    val deserialize: (ByteArray) -> AbstractBean,
    /** The bean subtype this protocol stores (for putBean dispatch by instance). */
    val beanClass: Class<out AbstractBean>,
    /** Read the typed bean field off the entity (for requireBean). */
    val getBean: (ProxyEntity) -> AbstractBean?,
    /** Write the typed bean field on the entity (for putBean; null clears it). */
    val setBean: (ProxyEntity, AbstractBean?) -> Unit,
    /** Human-readable protocol label (may branch on bean state, e.g. VMess vs VLESS). */
    val displayType: (ProxyEntity) -> String,
    /** Whether this protocol needs an external process (may branch on bean state). */
    val needExternal: (ProxyEntity) -> Boolean = { false },
)

object ProtocolRegistry {

    // Order mirrors the historical putByteArray / requireBean / putBean / displayType ladders.
    private val descriptors: List<ProtocolDescriptor> = listOf(
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SOCKS,
            deserialize = { KryoConverters.socksDeserialize(it) },
            beanClass = SOCKSBean::class.java,
            getBean = { it.socksBean },
            setBean = { e, b -> e.socksBean = b as SOCKSBean? },
            displayType = { it.socksBean!!.protocolName() },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_HTTP,
            deserialize = { KryoConverters.httpDeserialize(it) },
            beanClass = HttpBean::class.java,
            getBean = { it.httpBean },
            setBean = { e, b -> e.httpBean = b as HttpBean? },
            displayType = { if (it.httpBean!!.isTLS()) "HTTPS" else "HTTP" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SS,
            deserialize = { KryoConverters.shadowsocksDeserialize(it) },
            beanClass = ShadowsocksBean::class.java,
            getBean = { it.ssBean },
            setBean = { e, b -> e.ssBean = b as ShadowsocksBean? },
            displayType = { "Shadowsocks" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SSR,
            deserialize = { KryoConverters.shadowsocksrDeserialize(it) },
            beanClass = ShadowsocksRBean::class.java,
            getBean = { it.ssrBean },
            setBean = { e, b -> e.ssrBean = b as ShadowsocksRBean? },
            displayType = { "ShadowsocksR" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_VMESS,
            deserialize = { KryoConverters.vmessDeserialize(it) },
            beanClass = VMessBean::class.java,
            getBean = { it.vmessBean },
            setBean = { e, b -> e.vmessBean = b as VMessBean? },
            displayType = { if (it.vmessBean!!.isVLESS) "VLESS" else "VMess" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_TROJAN,
            deserialize = { KryoConverters.trojanDeserialize(it) },
            beanClass = TrojanBean::class.java,
            getBean = { it.trojanBean },
            setBean = { e, b -> e.trojanBean = b as TrojanBean? },
            displayType = { "Trojan" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_TROJAN_GO,
            deserialize = { KryoConverters.trojanGoDeserialize(it) },
            beanClass = TrojanGoBean::class.java,
            getBean = { it.trojanGoBean },
            setBean = { e, b -> e.trojanGoBean = b as TrojanGoBean? },
            displayType = { "Trojan-Go" },
            needExternal = { true },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_MIERU,
            deserialize = { KryoConverters.mieruDeserialize(it) },
            beanClass = MieruBean::class.java,
            getBean = { it.mieruBean },
            setBean = { e, b -> e.mieruBean = b as MieruBean? },
            displayType = { "Mieru" },
            needExternal = { true },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_NAIVE,
            deserialize = { KryoConverters.naiveDeserialize(it) },
            beanClass = NaiveBean::class.java,
            getBean = { it.naiveBean },
            setBean = { e, b -> e.naiveBean = b as NaiveBean? },
            displayType = { "Naïve" },
            needExternal = { true },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_HYSTERIA,
            deserialize = { KryoConverters.hysteriaDeserialize(it) },
            beanClass = HysteriaBean::class.java,
            getBean = { it.hysteriaBean },
            setBean = { e, b -> e.hysteriaBean = b as HysteriaBean? },
            displayType = { "Hysteria" + it.hysteriaBean!!.protocolVersion },
            needExternal = { !it.hysteriaBean!!.canUseSingBox() },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SSH,
            deserialize = { KryoConverters.sshDeserialize(it) },
            beanClass = SSHBean::class.java,
            getBean = { it.sshBean },
            setBean = { e, b -> e.sshBean = b as SSHBean? },
            displayType = { "SSH" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_WG,
            deserialize = { KryoConverters.wireguardDeserialize(it) },
            beanClass = WireGuardBean::class.java,
            getBean = { it.wgBean },
            setBean = { e, b -> e.wgBean = b as WireGuardBean? },
            displayType = { "WireGuard" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_TUIC,
            deserialize = { KryoConverters.tuicDeserialize(it) },
            beanClass = TuicBean::class.java,
            getBean = { it.tuicBean },
            setBean = { e, b -> e.tuicBean = b as TuicBean? },
            displayType = { "TUIC" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_JUICITY,
            deserialize = { KryoConverters.juicityDeserialize(it) },
            beanClass = JuicityBean::class.java,
            getBean = { it.juicityBean },
            setBean = { e, b -> e.juicityBean = b as JuicityBean? },
            displayType = { "Juicity" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SHADOWTLS,
            deserialize = { KryoConverters.shadowTLSDeserialize(it) },
            beanClass = ShadowTLSBean::class.java,
            getBean = { it.shadowTLSBean },
            setBean = { e, b -> e.shadowTLSBean = b as ShadowTLSBean? },
            displayType = { "ShadowTLS" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_ANYTLS,
            deserialize = { KryoConverters.anyTLSDeserialize(it) },
            beanClass = AnyTLSBean::class.java,
            getBean = { it.anyTLSBean },
            setBean = { e, b -> e.anyTLSBean = b as AnyTLSBean? },
            displayType = { "AnyTLS" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_CHAIN,
            deserialize = { KryoConverters.chainDeserialize(it) },
            beanClass = ChainBean::class.java,
            getBean = { it.chainBean },
            setBean = { e, b -> e.chainBean = b as ChainBean? },
            displayType = { ProxyEntity.chainName },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_CONFIG,
            deserialize = { KryoConverters.configDeserialize(it) },
            beanClass = ConfigBean::class.java,
            getBean = { it.configBean },
            setBean = { e, b -> e.configBean = b as ConfigBean? },
            displayType = { it.configBean!!.displayType() },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_SNELL,
            deserialize = { KryoConverters.snellDeserialize(it) },
            beanClass = SnellBean::class.java,
            getBean = { it.snellBean },
            setBean = { e, b -> e.snellBean = b as SnellBean? },
            displayType = { "Snell" },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_MASTERDNSVPN,
            deserialize = { KryoConverters.masterDnsVpnDeserialize(it) },
            beanClass = MasterDnsVpnBean::class.java,
            getBean = { it.masterDnsVpnBean },
            setBean = { e, b -> e.masterDnsVpnBean = b as MasterDnsVpnBean? },
            displayType = { "MasterDnsVPN" },
            needExternal = { true },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_OLCRTC,
            deserialize = { KryoConverters.olcrtcDeserialize(it) },
            beanClass = OlcrtcBean::class.java,
            getBean = { it.olcrtcBean },
            setBean = { e, b -> e.olcrtcBean = b as OlcrtcBean? },
            displayType = { "olcRTC" },
            needExternal = { true },
        ),
        ProtocolDescriptor(
            type = ProxyEntity.TYPE_AWG,
            deserialize = { KryoConverters.amneziaWGDeserialize(it) },
            beanClass = AmneziaWGBean::class.java,
            getBean = { it.awgBean },
            setBean = { e, b -> e.awgBean = b as AmneziaWGBean? },
            displayType = { "AmneziaWG" },
        ),
    )

    private val byType: Map<Int, ProtocolDescriptor> = buildMap {
        for (d in descriptors) {
            require(put(d.type, d) == null) { "duplicate ProtocolDescriptor for type ${d.type}" }
        }
    }

    private val byBeanClass: Map<Class<out AbstractBean>, ProtocolDescriptor> = buildMap {
        for (d in descriptors) {
            require(put(d.beanClass, d) == null) { "duplicate ProtocolDescriptor for bean ${d.beanClass}" }
        }
    }

    /** Descriptor for a persisted type id, or null for an unknown/dead id (matches old else-branch). */
    fun forType(type: Int): ProtocolDescriptor? = byType[type]

    /** Descriptor for a bean instance's concrete class, or null if unregistered. */
    fun forBean(bean: AbstractBean): ProtocolDescriptor? = byBeanClass[bean.javaClass]

    /** All typed-field setters, used by putBean to null out every field before assigning one. */
    fun clearAllBeans(entity: ProxyEntity) {
        for (d in descriptors) d.setBean(entity, null)
    }
}
