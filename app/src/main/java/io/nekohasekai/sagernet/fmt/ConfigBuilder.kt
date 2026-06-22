package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
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

const val TAG_MIXED = "mixed-in"
const val TAG_SOCKS_IN = "socks-in"
const val TAG_HTTP_IN = "http-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"

const val LOCALHOST = "127.0.0.1"
private val ANDROID_DNS_PACKAGES = listOf(
    "android",
    "com.android.resolv",
    "com.google.android.resolv",
    "com.android.networkstack",
    "com.google.android.networkstack",
    "com.android.dnsresolver",
    "com.android.networkstack.tethering",
    "com.google.android.networkstack.tethering",
    "com.android.tethering",
    "com.google.android.tethering"
)

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

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id,
                mapOf(tagProxy to listOf(proxy)),
                mapOf(proxy.id to tagProxy),
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val tunBypassPackages = linkedSetOf<String>()
    val tunBypassUserIds = linkedSetOf<Int>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (type == ProxyEntity.TYPE_CHAIN && bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                if (item.type == ProxyEntity.TYPE_WATERFALL ||
                    item.type == ProxyEntity.TYPE_FASTEST
                ) {
                    error("Dynamic proxy profiles cannot be used as chain hops")
                }
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
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val needLocalProxyInbounds = !isVPN || DataStore.allowAccess || DataStore.appendHttpProxy
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
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
                    path = "../cache/cache_${proxy.id.coerceAtLeast(0)}.db"
                    store_fakeip = true
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
            if (s.isNotEmpty()) return s
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
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
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
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                auto_route = true
                // sing-box known issue on Android: DNS hijack may fail with Private DNS when strict_route is disabled.
                strict_route = DataStore.strictRoute || SagerNet.isPrivateDnsActiveOnUnderlyingNetwork()
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }

                    IPv6Mode.ONLY -> {
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }

                    else -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }
                }
                if (DataStore.proxyApps) {
                    val selectedPackages = DataStore.individual
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableList()
                    if (DataStore.bypass) {
                        if (!selectedPackages.contains("android")) {
                            selectedPackages.add("android")
                        }
                        exclude_package = selectedPackages
                        tunBypassPackages.addAll(selectedPackages)
                        PackageCache.awaitLoadSync()
                        selectedPackages.forEach {
                            PackageCache[it]?.takeIf { uid -> uid >= 1000 }?.let { uid ->
                                tunBypassUserIds.add(uid)
                            }
                        }
                        tunBypassUserIds.add(1000)
                    }
                }
            })

            fun buildInboundUsers(): List<User> = listOf(
                User().apply {
                    username = DataStore.mixedUsername
                    password = DataStore.mixedPassword
                }
            )

            if (needLocalProxyInbounds) {
                inbounds.add(Inbound_SocksOptions().apply {
                    type = "socks"
                    tag = TAG_SOCKS_IN
                    listen = bind
                    listen_port = DataStore.socksPort
                    domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                    sniff = needSniff
                    sniff_override_destination = needSniffOverride
                    users = buildInboundUsers()
                })
                inbounds.add(Inbound_HTTPOptions().apply {
                    type = "http"
                    tag = TAG_HTTP_IN
                    listen = bind
                    listen_port = DataStore.httpPort
                    domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                    sniff = needSniff
                    sniff_override_destination = needSniffOverride
                    users = buildInboundUsers()
                })
            }
        }

        outbounds = mutableListOf()

        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            concurrent_dial = DataStore.concurrentDial
        }

        @Suppress("UNCHECKED_CAST")
        fun buildStaticChain(chainId: Long, entity: ProxyEntity): String {
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

            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                var tagOut = "$chainTag-${proxyEntity.id}"
                var needGlobal = false

                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                if (index > 0) {
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    chainTagOut = tagOut
                }

                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) {
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption
                        is ShadowTLSBean -> buildSingBoxOutboundShadowTLSBean(bean)
                        is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean)
                        is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean)
                        is TuicBean -> buildSingBoxOutboundTuicBean(bean)
                        is JuicityBean -> buildSingBoxOutboundJuicityBean(bean)
                        is SOCKSBean -> buildSingBoxOutboundSocksBean(bean)
                        is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean)
                        is ShadowsocksRBean -> buildSingBoxOutboundShadowsocksRBean(bean)
                        is WireGuardBean -> buildSingBoxOutboundWireguardBean(bean)
                        is SSHBean -> buildSingBoxOutboundSSHBean(bean)
                        is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
                        else -> throw IllegalStateException("can't reach")
                    }

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

                currentOutbound.apply {
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    pastEntity?.requireBean()?.apply {
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut
                    _hack_custom_config = bean.customOutboundJson
                }

                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
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
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        fun buildProfile(
            profileId: Long,
            entity: ProxyEntity,
            managedByParent: Boolean = false,
        ): String {
            if (entity.type != ProxyEntity.TYPE_WATERFALL &&
                entity.type != ProxyEntity.TYPE_FASTEST
            ) {
                return buildStaticChain(profileId, entity)
            }

            val bean = entity.chainBean ?: error("Missing dynamic profile data")
            if (bean.proxies.size != bean.proxies.distinct().size) {
                error("Dynamic proxy profile contains duplicate candidates")
            }
            val profilesById = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
            val candidates = bean.proxies.mapNotNull(profilesById::get)
            if (candidates.isEmpty()) {
                error("Dynamic proxy profile has no available candidates")
            }

            val candidateTags = candidates.map { candidate ->
                when {
                    entity.type == ProxyEntity.TYPE_WATERFALL &&
                        candidate.type == ProxyEntity.TYPE_FASTEST ->
                        buildProfile(candidate.id, candidate, managedByParent = true)

                    candidate.type == ProxyEntity.TYPE_WATERFALL ||
                        candidate.type == ProxyEntity.TYPE_FASTEST ->
                        error("Unsupported nested dynamic proxy profile")

                    else -> buildStaticChain(candidate.id, candidate)
                }
            }

            val groupTag = readableTag(entity.displayName())
            outbounds.add(Outbound_URLTestOptions().apply {
                type = "urltest"
                tag = groupTag
                outbounds = candidateTags
                url = DataStore.connectionTestURL
                interval = "10m"
                idle_timeout = "30m"
                timeout = "${DataStore.connectionTestTimeout}ms"
                tolerance = if (entity.type == ProxyEntity.TYPE_FASTEST) 50 else 0
                strategy = if (entity.type == ProxyEntity.TYPE_WATERFALL) {
                    "priority"
                } else {
                    "fastest"
                }
                managed_by_parent = managedByParent
                wait_for_initial = !managedByParent
            })

            trafficMap[groupTag] = buildList {
                add(entity)
                candidateTags.forEach { tag ->
                    addAll(trafficMap[tag].orEmpty())
                }
            }.distinctBy { it.id }
            return groupTag
        }

        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildProfile(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            val mainTag = buildProfile(0, proxy)
            tagMap[proxy.id] = mainTag
        }

        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildProfile(key, p)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY
        route.final_ = mainProxyTag

        fun buildPerAppTunGuardRules(): List<Rule_DefaultOptions> {
            if (forTest || !isVPN || DataStore.globalMode || !DataStore.proxyApps) {
                return emptyList()
            }

            PackageCache.awaitLoadSync()

            val selectedUids = DataStore.individual
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { pkg -> PackageCache[pkg]?.takeIf { uid -> uid >= 1000 } }
                .toCollection(linkedSetOf())

            return buildList {
                if (DataStore.bypass) {
                    if (selectedUids.isNotEmpty()) {
                        add(Rule_DefaultOptions().apply {
                            inbound = listOf("tun-in")
                            user_id = selectedUids.toList()
                            action = "reject"
                        })
                    }
                } else {
                    if (selectedUids.isEmpty()) {
                        add(Rule_DefaultOptions().apply {
                            inbound = listOf("tun-in")
                            action = "reject"
                        })
                    } else {
                        add(Rule_DefaultOptions().apply {
                            inbound = listOf("tun-in")
                            user_id = selectedUids.toList()
                            invert = true
                            action = "reject"
                        })
                    }
                }
            }
        }

        if (!forTest && DataStore.globalMode) {
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
                inbound = listOf("tun-in")
                outbound = mainProxyTag
            })

            if (needLocalProxyInbounds) {
                route.rules.add(Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_SOCKS_IN)
                    outbound = mainProxyTag
                })
                route.rules.add(Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_HTTP_IN)
                    outbound = mainProxyTag
                })
            }
        } else {
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

                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()

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
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }

            route.rules.addAll(buildPerAppTunGuardRules())
        }

        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }
        if (isVPN && DataStore.proxyApps && DataStore.bypass && tunBypassPackages.isNotEmpty()) {
            route.rules.add(0, Rule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                package_name = tunBypassPackages.toList()
                action = "reject"
            })
        }
        if (isVPN && DataStore.proxyApps && DataStore.bypass && tunBypassUserIds.isNotEmpty()) {
            route.rules.add(0, Rule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                user_id = tunBypassUserIds.toList()
                action = "reject"
            })
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(Outbound().apply {
                tag = freedom
                type = "direct"
            })
        }

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
                domainListDNSDirectForce.add("full:${serverAddr}")
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
            address = "rcode://success"
            tag = "dns-block"
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No direct DNS, check your settings!")
                tag = "dns-direct"
                detour = TAG_DIRECT
                address_resolver = "dns-local"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        remoteDns.firstOrNull().let {
            if (!forTest) {
                dns.servers.add(DNSServerOptions().apply {
                    address = it ?: throw Exception("No remote DNS, check your settings!")
                    tag = "dns-remote"
                    address_resolver = "dns-direct"
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                })
            }
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
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
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                    strategy = "ipv4_only"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                    query_type = listOf("A", "AAAA")
                })
            }
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) {
            val hasVlessTlsOutbound = outbounds.any { outbound ->
                val outboundMap = outbound.asMap()
                val tlsOptions = outboundMap["tls"] as? Map<*, *>
                outboundMap["type"] == "vless" && tlsOptions?.get("enabled") == true
            }
            if (hasVlessTlsOutbound) {
                // Prevent recurring "tls illegal parameter" state for VLESS by avoiding
                // persistent sing-box cache DB reuse across runs for this config.
                experimental?.cache_file?.enabled = false
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
