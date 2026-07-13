package io.nekohasekai.sagernet.database

import android.os.Binder
import android.os.Build
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringToInt
import io.nekohasekai.sagernet.ktx.stringToIntIfExists
import moe.matsuri.nb4a.TempDatabase

// Default exclusion patterns for the system HTTP proxy (appendHttpProxy).
// These are Android ProxyInfo host/suffix patterns (NOT CIDRs): private and
// loopback ranges so LAN hosts connect directly while proxied traffic flows.
val DEFAULT_HTTP_PROXY_BYPASS = listOf(
    "localhost",
    "127.*",
    "10.*",
    "172.16.*", "172.17.*", "172.18.*", "172.19.*",
    "172.20.*", "172.21.*", "172.22.*", "172.23.*",
    "172.24.*", "172.25.*", "172.26.*", "172.27.*",
    "172.28.*", "172.29.*", "172.30.*", "172.31.*",
    "192.168.*",
    "169.254.*",
).joinToString("\n")

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    @Volatile
    var mixedInboundAuthed: Boolean = false

    val configurationStore = RoomPreferenceDataStore(
        PublicDatabase.kvPairDao,
        cached = true,
        database = PublicDatabase.database,
    )
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    val pluginSignerApprovals: Set<String>
        get() = configurationStore.getStringSet(Key.PLUGIN_SIGNER_APPROVALS).orEmpty()

    suspend fun approvePluginSigner(identity: String) =
        configurationStore.addToStringSetDurable(Key.PLUGIN_SIGNER_APPROVALS, setOf(identity))

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        groups.find { it.type == GroupType.BASIC }?.let { return it.id }
        return SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
    }

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION)
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API)
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)
    var confirmProfileDelete by configurationStore.boolean(Key.CONFIRM_PROFILE_DELETE) { true }
    var groupLayoutMode by configurationStore.stringToInt(Key.GROUP_LAYOUT_MODE) { 0 }

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)

    // -1 = not set (no dark-only-theme override active). Otherwise holds the
    // night-mode value to restore when leaving a dark-only theme (Dracula or
    // Dark High Contrast). Key name kept for backward compatibility.
    var nightThemeBeforeDracula by configurationStore.int(Key.NIGHT_THEME_BEFORE_DRACULA) { -1 }
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "" }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 1500 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)
    var concurrentDial by configurationStore.boolean(Key.CONCURRENT_DIAL)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)

    // Default must match global_preferences.xml (1000ms). Without a non-zero code
    // default, a fresh install that never opens Settings reads 0, which disables the
    // TrafficLooper stats loop entirely (TrafficLooper.loop() returns on delayMs == 0),
    // so the dashboard up/down speeds stay blank. "0" remains a valid user choice (Off).
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://dns.quad9.net/dns-query" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "https://1.1.1.1/dns-query" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { false }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER)
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)
    var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)

    var rulesGeositeUrl by configurationStore.string(Key.RULES_GEOSITE_URL) {
        "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
    }
    var rulesGeoipUrl by configurationStore.string(Key.RULES_GEOIP_URL) {
        "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
    }
    var rulesUpdateInterval by configurationStore.string(Key.RULES_UPDATE_INTERVAL) {
        "0"
    } // defaults to 0, no automatic update

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }
    val mixedSecret: String
        @Synchronized get() {
            var s = configurationStore.getString(Key.MIXED_SECRET)
            if (s.isNullOrEmpty()) {
                s = java.util.UUID.randomUUID().toString().replace("-", "")
                configurationStore.putString(Key.MIXED_SECRET, s)
            }
            return s
        }

    val clashApiSecret: String
        @Synchronized get() {
            var s = configurationStore.getString(Key.CLASH_API_SECRET)
            if (s.isNullOrEmpty()) {
                s = java.util.UUID.randomUUID().toString().replace("-", "")
                configurationStore.putString(Key.CLASH_API_SECRET, s)
            }
            return s
        }

    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    val mixedInboundNeedsAuth: Boolean
        get() =
            // When the inbound is exposed to the LAN (bind 0.0.0.0 via allowAccess),
            // authentication is mandatory so the proxy is never an open relay. This
            // holds in both VPN and Proxy service modes and overrides appendHttpProxy.
            allowAccess ||
                // Loopback-only inbound: keep the prior behavior. In VPN mode the
                // inbound is authenticated, except when appendHttpProxy is active on
                // Android Q+ (the system HTTP proxy cannot supply credentials).
                (
                    serviceMode == Key.MODE_VPN &&
                        !(appendHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ) ||
                // Proxy service mode loopback inbound: optionally authenticate too
                // (issue #1197 residual gap). Off by default to preserve the
                // "open localhost proxy" use case; users opt in for hardening.
                (serviceMode == Key.MODE_PROXY && proxyModeInboundAuth)

    // Keep the local mixed (SOCKS/HTTP) inbound open in VPN mode. Default false: in
    // TUN mode the local proxy port is usually unnecessary, and closing it removes the
    // local port-scan attack surface entirely (PR #1154 / issue #1197).
    var requireProxyInVPN by configurationStore.boolean(Key.REQUIRE_PROXY_IN_VPN)

    // Authenticate the loopback mixed inbound in Proxy service mode (issue #1197).
    var proxyModeInboundAuth by configurationStore.boolean(Key.PROXY_MODE_INBOUND_AUTH)

    val mixedInboundUser: String get() = if (mixedInboundAuthed) Key.MIXED_USERNAME else ""
    val mixedInboundPass: String get() = if (mixedInboundAuthed) mixedSecret else ""

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
    }

    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY)
    var httpProxyBypass by configurationStore.string(Key.HTTP_PROXY_BYPASS) { DEFAULT_HTTP_PROXY_BYPASS }
    var dnsHosts by configurationStore.string(Key.DNS_HOSTS) { "" }
    var strictRoute by configurationStore.boolean(Key.STRICT_ROUTE) { true }
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") { 5 }
    var connectionTestTimeout by configurationStore.int(Key.CONNECTION_TEST_TIMEOUT) { 3000 }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    var yacdURL by configurationStore.string("yacdURL") { "http://127.0.0.1:9090/ui" }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }

    var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT) { false }
    var fragmentLength by configurationStore.string(Key.FRAGMENT_LENGTH) { "100-200" }
    var fragmentInterval by configurationStore.string(Key.FRAGMENT_INTERVAL) { "10-20" }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)
    var serverProtocolParam by profileCacheStore.string(Key.SERVER_PROTOCOL_PARAM)
    var serverObfsParam by profileCacheStore.string(Key.SERVER_OBFS_PARAM)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var serverHy2ObfsType by profileCacheStore.stringToInt(Key.SERVER_HY2_OBFS_TYPE)
    var serverHy2GeckoMinPacket by profileCacheStore.stringToInt(Key.SERVER_HY2_GECKO_MIN_PACKET) { 512 }
    var serverHy2GeckoMaxPacket by profileCacheStore.stringToInt(Key.SERVER_HY2_GECKO_MAX_PACKET) { 1200 }
    var serverHy2EchEnabled by profileCacheStore.boolean(Key.SERVER_HY2_ECH_ENABLED)
    var serverHy2EchConfig by profileCacheStore.string(Key.SERVER_HY2_ECH_CONFIG)

    // MasterDnsVPN
    var mdvDomains by profileCacheStore.string(Key.MDV_DOMAINS)
    var mdvEncryptionMethod by profileCacheStore.stringToInt(Key.MDV_ENCRYPTION_METHOD) { 0 }
    var mdvEncryptionKey by profileCacheStore.string(Key.MDV_ENCRYPTION_KEY)
    var mdvResolvers by profileCacheStore.string(Key.MDV_RESOLVERS)
    var mdvBalancingStrategy by profileCacheStore.stringToInt(Key.MDV_BALANCING_STRATEGY) { 3 }
    var mdvPacketDup by profileCacheStore.stringToInt(Key.MDV_PACKET_DUP) { 3 }
    var mdvSetupPacketDup by profileCacheStore.stringToInt(Key.MDV_SETUP_PACKET_DUP) { 4 }
    var mdvAutoDisableTimeout by profileCacheStore.boolean(Key.MDV_AUTO_DISABLE_TIMEOUT)
    var mdvAutoRemoveLowMtu by profileCacheStore.boolean(Key.MDV_AUTO_REMOVE_LOW_MTU)
    var mdvBaseEncode by profileCacheStore.boolean(Key.MDV_BASE_ENCODE)
    var mdvUploadCompression by profileCacheStore.stringToInt(Key.MDV_UPLOAD_COMPRESSION) { 0 }
    var mdvDownloadCompression by profileCacheStore.stringToInt(Key.MDV_DOWNLOAD_COMPRESSION) { 0 }
    var mdvCompressionMinSize by profileCacheStore.stringToInt(Key.MDV_COMPRESSION_MIN_SIZE) { 120 }
    var mdvMinUploadMtu by profileCacheStore.stringToInt(Key.MDV_MIN_UPLOAD_MTU) { 38 }
    var mdvMinDownloadMtu by profileCacheStore.stringToInt(Key.MDV_MIN_DOWNLOAD_MTU) { 200 }
    var mdvMaxUploadMtu by profileCacheStore.stringToInt(Key.MDV_MAX_UPLOAD_MTU) { 150 }
    var mdvMaxDownloadMtu by profileCacheStore.stringToInt(Key.MDV_MAX_DOWNLOAD_MTU) { 4000 }
    var mdvLocalDnsEnabled by profileCacheStore.boolean(Key.MDV_LOCAL_DNS_ENABLED)
    var mdvLocalDnsPort by profileCacheStore.stringToInt(Key.MDV_LOCAL_DNS_PORT) { 53 }
    var mdvLogLevel by profileCacheStore.string(Key.MDV_LOG_LEVEL) { "INFO" }
    var mdvAdvancedJson by profileCacheStore.string(Key.MDV_ADVANCED_JSON)

    var olcrtcCarrier by profileCacheStore.string(Key.OLCRTC_CARRIER) { "jitsi" }
    var olcrtcRoomId by profileCacheStore.string(Key.OLCRTC_ROOM_ID)
    var olcrtcClientId by profileCacheStore.string(Key.OLCRTC_CLIENT_ID)
    var olcrtcKeyHex by profileCacheStore.string(Key.OLCRTC_KEY_HEX)
    var olcrtcTransport by profileCacheStore.string(Key.OLCRTC_TRANSPORT) { "vp8channel" }
    var olcrtcVp8Fps by profileCacheStore.stringToInt(Key.OLCRTC_VP8_FPS) { 30 }
    var olcrtcVp8Batch by profileCacheStore.stringToInt(Key.OLCRTC_VP8_BATCH) { 8 }
    var olcrtcDnsServer by profileCacheStore.string(Key.OLCRTC_DNS_SERVER) { "" }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var serverUserId by profileCacheStore.string(Key.SERVER_USER_ID)
    var serverPinnedCertChainSha256 by profileCacheStore.string(Key.SERVER_PINNED_CERT_CHAIN_SHA256)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeRuleset by profileCacheStore.string(Key.ROUTE_RULESET)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }
    var subscriptionFilterMode by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FILTER_MODE) { 0 }
    var subscriptionFilterRegex by profileCacheStore.string(Key.SUBSCRIPTION_FILTER_REGEX)
    var subscriptionCustomDns by profileCacheStore.string(Key.SUBSCRIPTION_CUSTOM_DNS)

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    // var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT)

    var webdavServer: String?
        get() = configurationStore.getString("webdavServer")
        set(value) = configurationStore.putString("webdavServer", value)

    var webdavUsername: String?
        get() = configurationStore.getString("webdavUsername")
        set(value) = configurationStore.putString("webdavUsername", value)

    var webdavPassword: String?
        get() = configurationStore.getString("webdavPassword")
        set(value) = configurationStore.putString("webdavPassword", value)

    var webdavPath: String?
        get() = configurationStore.getString("webdavPath") ?: "NekoBox" // set default value
        set(value) = configurationStore.putString("webdavPath", value)

    var globalMode by configurationStore.boolean(Key.GLOBAL_MODE)

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}
