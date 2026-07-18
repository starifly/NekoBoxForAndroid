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
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
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
import java.net.IDN
import java.util.UUID

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

// Validate a hosts address token strictly enough for sing-box's netip-based
// parser: the app-wide isIpAddress() regex is looser (it allows IPv4 leading
// zeros and malformed IPv6 forms) and one bad value would fail the whole config
// load. Embedded-IPv4 IPv6 forms (::ffff:1.2.3.4) are not supported; write the
// plain IPv4 address instead. Returns the address, or null when not usable.
private fun parseHostsAddress(token: String): String? {
    val value = token.unwrapIPV6Host()
    if (!value.isIpAddress()) return null
    if (value.contains(':')) {
        // Pure-hex IPv6 (the regex rejects embedded IPv4 forms). Reject the empty
        // groups Go's netip parser refuses but the app regex tolerates: more than
        // one "::" (a ":::" run counts twice via overlap), a bare leading or
        // trailing ":", and more than 7 explicit groups alongside "::" (without
        // "::" the regex already enforces exactly 8).
        if (value.indexOf("::") != value.lastIndexOf("::")) return null
        if (value.startsWith(":") && !value.startsWith("::")) return null
        if (value.endsWith(":") && !value.endsWith("::")) return null
        if (value.contains("::") && value.split(':').count { it.isNotEmpty() } > 7) return null
    } else {
        // IPv4: reject leading zeros, which Go's netip parser refuses.
        if (value.split('.').any { it.length > 1 && it.startsWith('0') }) return null
    }
    return value
}

private fun parseHostsDomain(token: String): String? {
    var domain = sanitizeDnsEntry(token).removeSuffix(".")
    if (domain.isEmpty()) return null
    // Internationalized domains: convert to punycode, which is what arrives in
    // actual DNS queries and what sing-box matches against.
    if (domain.any { it.code >= 0x80 }) {
        domain = try {
            IDN.toASCII(domain)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    domain = domain.lowercase()
    if (domain.isEmpty() || domain.isIpAddress() || domain.length > 253) return null
    val labels = domain.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 }) return null
    // Underscore is permitted: it is common in DNS names (service labels) even
    // though it is invalid in strict hostnames.
    if (labels.any { label ->
            label.startsWith('-') ||
                label.endsWith('-') ||
                label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }
        }
    ) {
        return null
    }
    return domain
}

// Token separator for hosts entries: ASCII whitespace plus NBSP, which pasted web
// content often contains and which neither Java's \s (ASCII-only) nor the
// ISO-control sanitization covers.
private val hostsSeparator = "[\\s\u00A0]+".toRegex()

// Parse the user DNS hosts rewrite list: one "domain ip [ip ...]" entry per line,
// separated by any whitespace. Blank lines, comments (#) and malformed lines are
// ignored instead of failing the config build. Non-IP tokens after the domain are
// dropped, and IPv6 addresses may be written with or without brackets.
internal fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val tokens = line.split(hostsSeparator)
            .map { sanitizeDnsEntry(it) }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens.first().startsWith("#")) return@forEach
        val domain = parseHostsDomain(tokens.first()) ?: return@forEach
        val addresses = tokens.drop(1)
            .takeWhile { !it.startsWith("#") }
            .mapNotNull { parseHostsAddress(it) }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
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
const val TAG_SOCKS_IN = "socks-in"
const val TAG_HTTP_IN = "http-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

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
    // Per-port credentials for authenticated local SOCKS loopbacks of external
    // plugins (e.g. naive). Android does not isolate 127.0.0.1 per app, so an
    // unauthenticated plugin SOCKS listener could be reached by any local app and
    // leak the egress IP (issue #1166). The plugin listens with these creds and the
    // sing-box socks outbound dials with them.
    val localProxyCredentials: Map<Int, Pair<String, String>> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
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
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {
// Extracted from buildConfig as pure, capture-free helpers (Plan 028 seams).
// Behavior-preserving moves: same inputs -> same outputs.

private fun resolveChainInternal(entity: ProxyEntity): MutableList<ProxyEntity> {
    val bean = entity.requireBean()
    if (bean is ChainBean) {
        val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
        val beansMap = beans.associateBy { it.id }
        val beanList = ArrayList<ProxyEntity>()
        for (proxyId in bean.proxies) {
            val item = beansMap[proxyId] ?: continue
            beanList.addAll(resolveChainInternal(item))
        }
        return beanList.asReversed()
    }
    return mutableListOf(entity)
}

private class BuildLookupCache {
    private val groups = HashMap<Long, ProxyGroup?>()
    private val proxies = HashMap<Long, ProxyEntity?>()

    fun group(groupId: Long): ProxyGroup? {
        if (groups.containsKey(groupId)) return groups[groupId]
        return SagerDatabase.groupDao.getById(groupId).also { groups[groupId] = it }
    }

    fun proxy(proxyId: Long): ProxyEntity? {
        val cached = if (proxies.containsKey(proxyId)) {
            proxies[proxyId]
        } else {
            SagerDatabase.proxyDao.getById(proxyId).also { proxies[proxyId] = it }
        }
        return cached?.copy()?.putBean(cached.requireBean().clone())
    }
}

private fun resolveChain(entity: ProxyEntity, lookupCache: BuildLookupCache): MutableList<ProxyEntity> {
    val thisGroup = lookupCache.group(entity.groupId)
    val frontProxy = thisGroup?.frontProxy?.let(lookupCache::proxy)
    val landingProxy = thisGroup?.landingProxy?.let(lookupCache::proxy)
    val list = resolveChainInternal(entity)
    if (frontProxy != null) {
        list.add(frontProxy)
    }
    if (landingProxy != null) {
        list.add(0, landingProxy)
    }
    return list
}

private fun genDomainStrategy(noAsIs: Boolean, ipv6Mode: Int): String {
    return when {
        !noAsIs -> ""
        ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
        ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
        ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
        else -> "prefer_ipv4"
    }
}

private fun autoDnsDomainStrategy(s: String, ipv6Mode: Int): String? {
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

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): ConfigBuildResult {
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
    // Per-port credentials for authenticated external-plugin SOCKS loopbacks (#1166).
    val localProxyCredentials = HashMap<Int, Pair<String, String>>()
    val tunBypassPackages = linkedSetOf<String>()
    val tunBypassUserIds = linkedSetOf<Int>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val lookupCache = BuildLookupCache()
    val group = lookupCache.group(proxy.groupId)

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

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> = resolveChain(this, lookupCache)

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
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST// Whether the local proxy (SOCKS/HTTP) inbounds are present in the final config.
    // In VPN/TUN mode they are omitted unless the user opts in (requireProxyInVPN),
    // LAN access is allowed, or the system HTTP proxy needs it. See issue #1197 / PR #1154.
    val needLocalProxyInbounds = !forTest && (
        !isVPN ||
            DataStore.requireProxyInVPN ||
            DataStore.allowAccess ||
            (DataStore.appendHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        )
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts = if (forTest) emptyMap() else parseDnsHosts(DataStore.dnsHosts)
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String = genDomainStrategy(noAsIs, ipv6Mode)

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
                        // Exported/shared configs must not carry the per-install token.
                        if (!forExport) secret = DataStore.clashApiSecret
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
                                address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
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

                    // Per-subscription custom resolver (#71). The resolver belongs to the
                    // subscription that owns this chain (entity.groupId), not necessarily the
                    // last hop's group (a front/landing proxy may come from another group).
                    // We record the server hostnames of the chain hops that belong to that
                    // same subscription, so the resolver is scoped to its own servers only.
                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = lookupCache.group(ownerGid)
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

                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val tls = SingBoxOptions.toJsonTree(currentOutbound).get("tls")
                        val enabled = tls?.takeIf { it.isJsonObject }?.asJsonObject?.get("enabled")
                        val tlsEnabled = enabled?.isJsonPrimitive == true &&
                            enabled.asJsonPrimitive.isBoolean &&
                            enabled.asBoolean
                        if (tlsEnabled) currentOutbound._hack_config_map["detour"] = TAG_FRAGMENT
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
                wait_for_initial = true
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

            route.final_ = mainProxyTag
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
                            domainList?.let { makeSingBoxRule(it) }}
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

                            val nonIpRulesets = mutableListOf<String>()
                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // only handle ruleset tags, and they must be non-IP type
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        nonIpRulesets.add(tag)
                                    }
                                }
                            }
                            if (nonIpRulesets.isNotEmpty()) {
                                rule_set = nonIpRulesets
                            }
                        }

                        0L -> {
                            if (shouldAddDnsRule) {
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
                    }
                        -2L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-block"
                                    disable_cache = true
                                }
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

            route.rules.addAll(buildPerAppTunGuardRules())
        }

        // deduplicate rule_set tags
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
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                tag = TAG_DNS_HOSTS
                _hack_config_map["type"] = "hosts"
                _hack_config_map["predefined"] = dnsHosts
            })
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
            // User DNS hosts rewrite: a hosts-type server answering A/AAAA from the
            // predefined domain -> IP map. Scope the rule to configured domains only;
            // otherwise sing-box's hosts transport may also consult implicit hosts-file
            // data. Do not use ip_accept_any here: if the user maps only A records,
            // an AAAA query should produce no answer instead of falling through to
            // FakeDNS or remote DNS and bypassing the rewrite.
            // Scoping to A/AAAA keeps other record types (TXT, MX, HTTPS/SVCB) on
            // their normal resolution path, mirroring hosts-file semantics; SVCB
            // answers may therefore still carry upstream address hints.
            // Inserted below the loopback/force-bypass/per-subscription rules added
            // after it, so proxy bootstrap resolution is never hijacked by user
            // entries, and above the user DNS routing rules, so the rewrite wins
            // for its configured domains.
            if (dnsHosts.isNotEmpty()) {
                dns.servers.add(
                    DNSServerOptions().apply {
                        tag = TAG_DNS_HOSTS
                        _hack_config_map["type"] = "hosts"
                        _hack_config_map["predefined"] = dnsHosts
                    },
                )
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(dnsHosts.keys.map { "full:$it" })
                        query_type = listOf("A", "AAAA")
                        server = TAG_DNS_HOSTS
                    },
                )
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    _hack_config_map["ip_accept_any"] = true
                })
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
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(DNSServerOptions().apply {
                    address = resolver
                    tag = serverTag
                    detour = TAG_DIRECT
                    if (!resolver.isIpAddress()) {
                        address_resolver = "dns-direct"
                    }
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
                })
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
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
        val configTree = SingBoxOptions.toJsonTree(it)
        Util.mergeJsonElement(configTree, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            SingBoxOptions.treeToJson(configTree),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            localProxyCredentials,
        )
    }
}
