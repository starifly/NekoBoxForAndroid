package io.nekohasekai.sagernet.fmt

import android.os.Build
import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.amneziawg.buildSingBoxOutboundAmneziaWGBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.olcrtc.carrierHost
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

// Extract the server hostname for a bean, mirroring the bypassDNSBeans logic
// (ConfigBean stores its address inside the parsed JSON "server" field). Falls back to
// bean.serverAddress when the JSON has no usable "server" so custom-resolver host mapping
// is preserved for those configs.
private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    // Per-port credentials for authenticated local SOCKS loopbacks of external
    // plugins (e.g. naive). Android does not isolate 127.0.0.1 per app, so an
    // unauthenticated plugin SOCKS listener could be reached by any local app and
    // leak the egress IP (issue #1166). The plugin listens with these creds and the
    // sing-box socks outbound dials with them.
    val localProxyCredentials: Map<Int, Pair<String, String>> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): ConfigBuildResult {
    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L,
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    // Per-port credentials for authenticated external-plugin SOCKS loopbacks (#1166).
    val localProxyCredentials = HashMap<Int, Pair<String, String>>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) {
            mapOf()
        } else {
            SagerDatabase.proxyDao.getEntities(
                extraRules.mapNotNull { rule ->
                    rule.outbound.takeIf { it > 0 && it != proxy.id }
                }.toHashSet().toList(),
            ).associateBy { it.id }
        }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    // Per-subscription custom resolver (#71). Maps a subscription group's resolver to the
    // exact server hostnames imported from that subscription, so the resolver is used ONLY
    // for those domains.
    val perGroupResolver = HashMap<Long, String>() // groupId -> resolver address
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>() // groupId -> server hosts
    // host -> set of resolver addresses requested for it (across all custom-resolver groups).
    // A host is only routed to a custom resolver when it maps to exactly one resolver; hosts
    // claimed by multiple subscriptions with different resolvers are ambiguous and fall back
    // to the global DNS.
    val hostResolvers = HashMap<String, MutableSet<String>>()
    // Every final-hop server host that does NOT belong to a custom-resolver subscription.
    // If a host appears here too, it is shared with a normal profile, so we must NOT hijack
    // it onto a per-subscription resolver (keep it on the global direct path).
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>() // groupId -> group (build-time cache)
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    // Whether the local mixed (SOCKS/HTTP) inbound is present in the final config.
    // In VPN/TUN mode it is omitted unless the user opts in (requireProxyInVPN) or the
    // system HTTP proxy needs it (appendHttpProxy). See issue #1197 / PR #1154.
    val keepMixedInbound = !forTest && (
        !isVPN ||
            DataStore.requireProxyInVPN ||
            (DataStore.appendHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        )
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (DataStore.enableClashAPI) {
                    store_fakeip = true
                    // }
                }

                if (DataStore.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) {
                inbounds.add(
                    Inbound_TunOptions().apply {
                        type = "tun"
                        tag = "tun-in"
                        interface_name = "tun0"
                        stack = when (DataStore.tunImplementation) {
                            TunImplementation.GVISOR -> "gvisor"
                            TunImplementation.SYSTEM -> "system"
                            else -> "mixed"
                        }
                        endpoint_independent_nat = true
                        mtu = DataStore.mtu
                        auto_route = true
                        strict_route = DataStore.strictRoute
                        when (ipv6Mode) {
                            IPv6Mode.DISABLE -> {
                                address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                            }

                            IPv6Mode.ONLY -> {
                                address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                            }

                            else -> {
                                address = listOf(
                                    VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                                    VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                                )
                            }
                        }
                    },
                )
            }
            // Local mixed (SOCKS/HTTP) inbound. In VPN/TUN mode this port is usually
            // unnecessary and only widens the local attack surface (issue #1197), so it
            // is omitted unless the user opts in (requireProxyInVPN) or the system HTTP
            // proxy needs it (appendHttpProxy routes the device proxy through this port).
            if (keepMixedInbound) {
                inbounds.add(
                    Inbound_MixedOptions().apply {
                        type = "mixed"
                        tag = TAG_MIXED
                        listen = bind
                        listen_port = DataStore.mixedPort
                        if (DataStore.mixedInboundNeedsAuth) {
                            users = listOf(
                                User().also { u ->
                                    u.username = Key.MIXED_USERNAME
                                    u.password = DataStore.mixedSecret
                                },
                            )
                        }
                    },
                )
            }
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // add concurrent dial setting
            concurrent_dial = DataStore.concurrentDial

            // sing-box 1.13 moved sniffing + domain resolution off inbounds onto route
            // rule actions. Emit them first so behaviour matches the old inbound fields.
            // sniff: previously inbound.sniff (trafficSniffing > 0). The old
            //   sniff_override_destination (trafficSniffing == 2) toggle has no 1.13
            //   equivalent - the sniff action overrides the destination by default, so the
            //   override mode is preserved without a separate flag.
            // resolve: previously inbound.domain_strategy, which ran whenever a non-empty
            //   strategy was configured - independent of sniff-override. Gate on the
            //   strategy string being non-empty (orthogonal to sniffing).
            if (needSniff) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        action = "sniff"
                    },
                )
            }
            val resolveStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (resolveStrategy.isNotEmpty()) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        action = "resolve"
                        strategy = resolveStrategy
                    },
                )
            }
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(chainId: Long, entity: ProxyEntity): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()

                    // Per-subscription custom resolver (#71). The resolver belongs to the
                    // subscription that owns this chain (entity.groupId), not necessarily the
                    // last hop's group (a front/landing proxy may come from another group).
                    // We record the server hostnames of the chain hops that belong to that
                    // same subscription, so the resolver is scoped to its own servers only.
                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            SagerDatabase.groupDao.getById(ownerGid)
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.customDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            // Record hosts of every chain hop owned by this subscription.
                            profileList.forEach { hop ->
                                if (hop.groupId == ownerGid) {
                                    val host = serverHostOf(hop.requireBean())
                                    if (host != null && !host.isIpAddress()) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    }
                                } else {
                                    // hop from another (non-custom) group sharing this chain
                                    val host = serverHostOf(hop.requireBean())
                                    if (host != null && !host.isIpAddress()) {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
                            // No custom resolver for this chain: none of its hosts (any hop)
                            // must be hijacked by another subscription's resolver.
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(
                            Rule_DefaultOptions().apply {
                                inbound = listOf(pastInboundTag)
                                outbound = tagOut
                            },
                        )
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    // olcRTC resolves its carrier host itself; on a fakeip VPN the system
                    // resolver would hand it a tun-only fake IP and its (protected) socket
                    // would loop back through the tun. Force the carrier signaling host to
                    // resolve via dns-direct (real IP) so the protected socket goes direct.
                    if (bean is OlcrtcBean) {
                        bean.carrierHost()?.takeIf { !it.isIpAddress() }?.let {
                            domainListDNSDirectForce.add("full:$it")
                        }
                    }
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                        // Authenticate the local SOCKS loopback for plugins that support
                        // it (naive), so other apps on the device can't use this open
                        // 127.0.0.1 port to leak the egress IP (#1166). The plugin is
                        // configured to listen with the same generated credentials.
                        // Skip for export: the exported naive config (ProxyEntity.
                        // buildNaiveConfig without creds) would otherwise mismatch and
                        // produce a broken standalone config.
                        if ((bean is NaiveBean || bean is OlcrtcBean) && !forExport) {
                            val user = "neko"
                            val pass = UUID.randomUUID().toString().replace("-", "")
                            localProxyCredentials[localPort] = user to pass
                            username = user
                            password = pass
                        }
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean ->
                            buildSingBoxOutboundWireguardBean(bean)

                        is AmneziaWGBean ->
                            buildSingBoxOutboundAmneziaWGBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            currentOutbound._hack_config_map["detour"] = TAG_FRAGMENT
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            // Only Hysteria v1 takes the external plugin path; v2 is
                            // handled natively, so it never reaches needExternal().
                            is HysteriaBean -> "hysteria-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception(
                                "You are using an unsupported $pluginId, please download the correct plugin.",
                            )
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(
                            Inbound_DirectOptions().apply {
                                type = "direct"
                                listen = LOCALHOST
                                listen_port = mappingPort
                                tag = "$chainTag-mapping-${proxyEntity.id}"

                                override_address = bean.serverAddress
                                override_port = bean.serverPort

                                pastInboundTag = tag

                                // no chain rule and not outbound, so need to set to direct
                                if (index == profileList.lastIndex) {
                                    if (DataStore.enableTLSFragment) {
                                        route.rules.add(
                                            Rule_DefaultOptions().apply {
                                                network = listOf("tcp")
                                                inbound = listOf(tag)
                                                outbound = TAG_FRAGMENT
                                            },
                                        )
                                    }

                                    route.rules.add(
                                        Rule_DefaultOptions().apply {
                                            inbound = listOf(tag)
                                            outbound = TAG_DIRECT
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(
                0,
                Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = TAG_PROXY
                    default_ = tagMap[proxy.id]
                    outbounds = tagMap.values.toList()
                },
            )
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        // check global mode before applying user rules
        if (!forTest && DataStore.globalMode) {
            // rule handling in global mode

            // bypass internal networks (if enabled)
            if (DataStore.bypassLan) {
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        ip_cidr = listOf(
                            "224.0.0.0/3",
                            "172.16.0.0/12",
                            "127.0.0.0/8",
                            "10.0.0.0/8",
                            "192.168.0.0/16",
                            "169.254.0.0/16",
                            "::1/128",
                            "fc00::/7",
                            "fe80::/10",
                        )
                        outbound = TAG_DIRECT
                    },
                )
            }

            route.rules.add(
                Rule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    outbound = mainProxyTag
                },
            )

            if (keepMixedInbound) {
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_MIXED)
                        outbound = mainProxyTag
                    },
                )
            }

            route.final_ = mainProxyTag
        } else {
            // apply user rules
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }

                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                    // store ruleset tag and type info
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()

                    // handle remote ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)

                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)

                            rulesetTags.add(Pair(tag, isIPRuleset))

                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                        return DNSRule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) user_id = uidList
                            domainList?.let { makeSingBoxRule(it) }
                        }
                    }

                    when (rule.outbound) {
                        -1L -> {
                            userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // only handle ruleset tags, and they must be non-IP type
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-direct"
                                        }
                                    }
                                }
                            }
                        }

                        0L -> {
                            if (useFakeDns) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf("tun-in")
                                    query_type = listOf("A", "AAAA")
                                }
                            } else {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-remote"
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        if (useFakeDns) {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-fake"
                                                inbound = listOf("tun-in")
                                                query_type = listOf("A", "AAAA")
                                            }
                                        } else {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-remote"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        -2L -> {
                            userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-block"
                                disable_cache = true
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-block"
                                            disable_cache = true
                                        }
                                    }
                                }
                            }
                        }
                    }

                    outbound = when (val outId = rule.outbound) {
                        0L -> mainProxyTag
                        -1L -> TAG_BYPASS
                        -2L -> TAG_BLOCK
                        else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                    }

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        // block now uses the new approach
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }
        }

        // deduplicate rule_set tags
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(
            Outbound().apply {
                tag = freedom
                type = "direct"
            },
        )

        if (DataStore.enableTLSFragment) {
            val fragmentOutbound = Outbound().apply {
                tag = TAG_FRAGMENT
                type = "direct"
                _hack_config_map["fragment"] = Fragment().apply {
                    length = DataStore.fragmentLength
                    interval = DataStore.fragmentInterval
                }.asMap()
            }
            outbounds.add(fragmentOutbound)
        }

        // Per-subscription custom resolver (#71): a host is eligible for a dedicated resolver
        // only when it maps to exactly one resolver AND is not shared with any non-custom
        // profile. Otherwise it stays on the global direct DNS path.
        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                // Servers handled by a dedicated per-subscription resolver are kept out of the
                // global direct-DNS force list to avoid conflicting routing (#71). Only do this
                // for hosts exclusive to a single custom-resolver subscription.
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:$serverAddr")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(
            DNSServerOptions().apply {
                address = "rcode://success"
                tag = "dns-block"
            },
        )

        dns.servers.add(
            DNSServerOptions().apply {
                address = "local"
                tag = "dns-local"
                detour = TAG_DIRECT
            },
        )

        directDNS.firstOrNull().let {
            dns.servers.add(
                DNSServerOptions().apply {
                    address = it ?: throw Exception("No direct DNS, check your settings!")
                    tag = "dns-direct"
                    detour = TAG_DIRECT
                    address_resolver = "dns-local"
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                },
            )
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) {
                dns.servers.add(
                    DNSServerOptions().apply {
                        address = it ?: throw Exception("No remote DNS, check your settings!")
                        tag = "dns-remote"
                        address_resolver = "dns-direct"
                        strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                    },
                )
            }
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(
                0,
                Rule_DefaultOptions().apply {
                    protocol = listOf("dns")
                    action = "hijack-dns"
                },
            )
            route.rules.add(
                0,
                Rule_DefaultOptions().apply {
                    port = listOf(53)
                    action = "hijack-dns"
                },
            )
            if (DataStore.bypassLanInCore) {
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        outbound = TAG_BYPASS
                        ip_is_private = true
                    },
                )
            }
            // block mcast
            route.rules.add(
                Rule_DefaultOptions().apply {
                    ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                    source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                    action = "reject"
                },
            )
            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(
                    DNSServerOptions().apply {
                        address = "fakeip"
                        tag = "dns-fake"
                        strategy = "ipv4_only"
                    },
                )
                dns.rules.add(
                    DNSRule_DefaultOptions().apply {
                        inbound = listOf("tun-in")
                        server = "dns-fake"
                        disable_cache = true
                        query_type = listOf("A", "AAAA")
                    },
                )
            }
            // avoid loopback
            dns.rules.add(
                0,
                DNSRule_DefaultOptions().apply {
                    outbound = mutableListOf("any")
                    server = "dns-direct"
                },
            )
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                        server = "dns-direct"
                    },
                )
            }

            // Per-subscription custom resolver (#71): one DNS server per subscription group,
            // routed directly (TAG_DIRECT) so it never loops through the proxy outbound, and
            // a DNS rule matching ONLY that subscription's server domains. Inserted at the top
            // so it takes precedence over the global direct-DNS force rule above. Hosts shared
            // by multiple subscriptions with different resolvers are ambiguous and are skipped
            // here (they stay on the global direct path).
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(
                    DNSServerOptions().apply {
                        address = resolver
                        tag = serverTag
                        detour = TAG_DIRECT
                        address_resolver = "dns-local"
                        // Reached via direct detour, so use the direct domain strategy (like
                        // dns-direct), not the server strategy (the tag would otherwise fall into
                        // the "server" arm of domainStrategy).
                        strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
                    },
                )
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(hosts)
                        server = serverTag
                    },
                )
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            localProxyCredentials,
        )
    }
}
