package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.Logs
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

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"
const val TAG_DNS_DIRECT_OUT = "dns-direct-out"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

// sing-box 1.12+ 新 DNS 服务器格式：把旧版地址字符串拆成 type / server / server_port / path
private fun DNSServerOptions.applyDnsAddress(address: String) {
    val value = address.trim()
    val hasScheme = value.contains("://")
    val scheme = if (hasScheme) value.substringBefore("://").lowercase() else ""
    val hostPart = if (hasScheme) value.substringAfter("://") else value
    when (val typeName = scheme.ifEmpty {
        when (hostPart.lowercase()) {
            "local", "fakeip", "dhcp" -> hostPart.lowercase()
            else -> "udp"
        }
    }) {
        "local" -> type = "local"
        "fakeip" -> type = "fakeip"
        "dhcp" -> {
            type = "dhcp"
            if (hasScheme && hostPart.isNotBlank()) interface_ = hostPart
        }
        "https", "h3" -> {
            val url = (if (hasScheme) value else "https://$value").toHttpUrlOrNull()
            if (url == null) {
                // 原版对坏地址更宽容：解析失败降级为 local，不让服务起不来
                Logs.d("invalid DNS address, fallback to local: $address")
                type = "local"
                return
            }
            type = typeName
            server = url.host
            if (url.port != 443) server_port = url.port
            path = url.encodedPath.takeIf { it.isNotBlank() && it != "/" }
        }
        "tls", "quic", "tcp", "udp" -> {
            type = typeName
            val hostPort = hostPart.trim()
            when {
                hostPort.startsWith("[") -> {
                    server = hostPort.substringAfter("[").substringBefore("]")
                    hostPort.substringAfter("]", "").removePrefix(":").toIntOrNull()?.let { server_port = it }
                }
                hostPort.count { it == ':' } == 1 -> {
                    server = hostPort.substringBefore(":")
                    hostPort.substringAfter(":").toIntOrNull()?.let { server_port = it }
                }
                else -> server = hostPort
            }
        }
        else -> {
            Logs.d("unsupported DNS address, fallback to local: $address")
            type = "local"
        }
    }
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first().lowercase()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

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

fun buildConfig(
    proxy: ProxyEntity,
    forTest: Boolean = false,
    forExport: Boolean = false,
    // 整组测速：一次构建组内全部节点的 outbound，配合单实例按 tag 并发测速
    testProfiles: List<ProxyEntity>? = null,
): ConfigBuildResult {

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
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
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
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val deviceInboundTag = if (isVPN && DataStore.enableHevTun) TAG_MIXED else "tun-in"
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy { parseDnsHosts(DataStore.dnsHosts) }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
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
            if (isVPN && !DataStore.enableHevTun) inbounds.add(Inbound_TunOptions().apply {
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
                            VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                        )
                    }
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                if (DataStore.mixedInboundHasAuth) {
                    users = listOf(User().also { u ->
                        u.username = DataStore.mixedUsername
                        u.password = DataStore.mixedSecret
                    })
                }
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
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

                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            SagerDatabase.groupDao.getById(ownerGid)
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.serverDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    if (hop.groupId == ownerGid) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    } else {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
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
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
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
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
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

                    // TLS 分片已改为路由动作（route-options tls_fragment），不再用 detour 绕经 fragment 出站
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
                    // 1.12+ 用 domain_resolver 替代已废弃的 domain_strategy
                    _hack_config_map["domain_resolver"] =
                        if (forTest || defaultServerDomainStrategy.isEmpty()) "dns-direct"
                        else mapOf("server" to "dns-direct", "strategy" to defaultServerDomainStrategy)

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
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
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
                                    route.rules.add(Rule_DefaultOptions().apply {
                                        network = listOf("tcp")
                                        inbound = listOf(tag)
                                        outbound = TAG_FRAGMENT
                                    })
                                }

                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
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
        if (buildSelector || testProfiles != null) {
            val list = testProfiles
                ?: group?.id?.let { SagerDatabase.proxyDao.getByGroup(it) }
                ?: emptyList()
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            if (buildSelector) {
                outbounds.add(0, Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = TAG_PROXY
                    default_ = tagMap[proxy.id]
                    outbounds = tagMap.values.toList()
                })
            }
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        // 在应用用户规则之前检查全局模式
        if (!forTest && DataStore.globalMode) {
            // 全局模式下的规则处理
            
            // 绕过内部网络（如果启用）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "224.0.0.0/3",
                        "172.16.0.0/12",
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                    )
                    outbound = TAG_DIRECT
                })
            }

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(deviceInboundTag)
                outbound = mainProxyTag
            })

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
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
                    
		    // 存储ruleset标签和类型信息
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                    
                    // 处理远程ruleset
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

                    val hasDomainCriteria = !domainList.isNullOrEmpty()
                    val hasIpCriteria =
                        rule.ip.isNotBlank() || rulesetTags.any { it.second }
                    val hasDomainRuleset = rulesetTags.any { !it.second }
                    val isAppOnlyDns =
                        uidList.isNotEmpty() &&
                            !hasDomainCriteria &&
                            !hasIpCriteria &&
                            !hasDomainRuleset &&
                            rule.port.isBlank() &&
                            rule.sourcePort.isBlank() &&
                            rule.network.isBlank() &&
                            rule.source.isBlank() &&
                            rule.protocol.isBlank()
                    val shouldAddDnsRule = hasDomainCriteria || isAppOnlyDns

                    when (rule.outbound) {
                        -1L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // 只处理ruleset标签，且必须是非IP类型
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
                            if (shouldAddDnsRule) {
                                if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf(deviceInboundTag)
                                    query_type = listOf("A", "AAAA")
                                } else {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-remote"
                                    }
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
                                                inbound = listOf(deviceInboundTag)
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
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    _hack_config_map["action"] = "predefined"
                                    _hack_config_map["rcode"] = "NOERROR"
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            _hack_config_map["action"] = "predefined"
                                            _hack_config_map["rcode"] = "NOERROR"
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
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // block 改用新的写法
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

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // 1.14 拒绝 detour 到「空 direct 出站」，DNS 服务器需要带参数的直连出站
        outbounds.add(Outbound().apply {
            tag = TAG_DNS_DIRECT_OUT
            type = "direct"
            _hack_config_map["udp_fragment"] = false
        })

        if (DataStore.enableTLSFragment) {
            // 1.14 移除了 direct outbound 的 fragment 字段，分片改由路由动作实现
            outbounds.add(Outbound().apply {
                tag = TAG_FRAGMENT
                type = "direct"
                _hack_config_map["udp_fragment"] = false
            })
        }

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
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:${serverAddr}")
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

        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
            detour = TAG_DNS_DIRECT_OUT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                applyDnsAddress(it ?: throw Exception("No direct DNS, check your settings!"))
                tag = "dns-direct"
                detour = TAG_DNS_DIRECT_OUT
                domain_resolver = "dns-local"
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DNSServerOptions().apply {
                applyDnsAddress(it ?: throw Exception("No remote DNS, check your settings!"))
                tag = "dns-remote"
                domain_resolver = "dns-direct"
            })
        }
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                type = "hosts"
                tag = TAG_DNS_HOSTS
                predefined = dnsHosts
            })
        }

        // 1.14 起 DNS server 不再支持独立 strategy（domain_strategy 已废弃），统一放到 dns.strategy
        autoDnsDomainStrategy(genDomainStrategy(DataStore.resolveDestination))?.let {
            dns.strategy = it
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
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf(deviceInboundTag)
                    server = "dns-fake"
                    disable_cache = true
                    query_type = listOf("A", "AAAA")
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    // hosts 服务器精确匹配域名，未命中会返回 NXDOMAIN，因此只在请求阶段命中 hosts 域名时才走它
                    domain = dnsHosts.keys.map { "full:${it.lowercase()}" }
                    server = TAG_DNS_HOSTS
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(DNSServerOptions().apply {
                    applyDnsAddress(resolver)
                    tag = serverTag
                    detour = TAG_DIRECT
                    if (!resolver.isIpAddress()) {
                        domain_resolver = "dns-direct"
                    }
                })
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                })
            }
        }

        // 1.12+ 起 TLS 分片改为路由动作（原 fragment outbound 字段已移除）
        if (DataStore.enableTLSFragment) {
            route.rules.add(0, Rule_DefaultOptions().apply {
                action = "route-options"
                _hack_config_map["tls_fragment"] = true
            })
        }

        if (!forTest) {
            // 1.12+ 起出站缺省域名解析器由 route 统一指定（1.16 将改为强制）
            route._hack_config_map["default_domain_resolver"] = "dns-direct"
            // 1.13+ 起 inbound 的 sniff / domain_strategy 字段被移除，改用路由规则动作
            if (needSniff) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "sniff"
                })
            }
            val resolveStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (resolveStrategy.isNotEmpty()) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "resolve"
                    _hack_config_map["strategy"] = resolveStrategy
                })
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
            if (buildSelector) group.id else -1L
        )
    }

}
