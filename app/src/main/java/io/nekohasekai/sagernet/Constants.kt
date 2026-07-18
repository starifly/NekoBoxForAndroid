package io.nekohasekai.sagernet

const val CONNECTION_TEST_URL = "http://www.gstatic.com/generate_204"

object Key {

    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val PERSIST_ACROSS_REBOOT = "isAutoConnect"

    const val CLEAR_CACHE = "clearCache"
    const val PLUGIN_SIGNER_APPROVALS = "pluginSignerApprovals"

    const val APP_EXPERT = "isExpert"const val APP_THEME = "appTheme"
    const val USE_SYSTEM_THEME = "useSystemTheme"
    const val NIGHT_THEME = "nightTheme"

    // Remembers the user's night-mode setting before a dark-only theme (Dracula
    // or Dark High Contrast) forced it on, so it can be restored when switching
    // to another theme. Storage key kept as "nightThemeBeforeDracula" for
    // backward compatibility with previously persisted values.
    const val NIGHT_THEME_BEFORE_DRACULA = "nightThemeBeforeDracula"
    const val APP_LANGUAGE = "appLanguage"
    const val DYNAMIC_COLORS = "dynamicColors"
    const val UI_DESIGN_VERSION = "uiDesignVersion"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val GLOBAL_CUSTOM_CONFIG = "globalCustomConfig"

    const val REMOTE_DNS = "remoteDns"
    const val DIRECT_DNS = "directDns"
    const val ENABLE_DNS_ROUTING = "enableDnsRouting"
    const val ENABLE_FAKEDNS = "enableFakeDns"

    const val IPV6_MODE = "ipv6Mode"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val METERED_NETWORK = "meteredNetwork"

    const val TRAFFIC_SNIFFING = "trafficSniffing"
    const val RESOLVE_DESTINATION = "resolveDestination"

    const val BYPASS_LAN = "bypassLan"
    const val BYPASS_LAN_IN_CORE = "bypassLanInCore"
    const val CONCURRENT_DIAL = "concurrentDial"

    const val MIXED_PORT = "mixedPort" // migration source
    const val SOCKS_PORT = "socksPort"
    const val HTTP_PORT = "httpPort"
    const val MIXED_USERNAME = "mixedUsername"
    const val MIXED_PASSWORD = "mixedPassword"
    const val MIXED_SECRET = "mixedSecret" // storage key for the generated inbound secret
    const val CLASH_API_SECRET = "clashApiSecret" // per-install secret for the local Clash API

    const val ALLOW_ACCESS = "allowAccess"
    const val REQUIRE_PROXY_IN_VPN = "requireProxyInVPN" // keep local mixed inbound open in VPN mode
    const val PROXY_MODE_INBOUND_AUTH = "proxyModeInboundAuth" // authenticate loopback inbound in Proxy mode
    const val SPEED_INTERVAL = "speedInterval"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"

    const val APPEND_HTTP_PROXY = "appendHttpProxy"
    const val HTTP_PROXY_BYPASS = "httpProxyBypass"
    const val DNS_HOSTS = "dnsHosts"
    const val STRICT_ROUTE = "strictRoute"

    const val CONNECTION_TEST_URL = "connectionTestURL"
    const val CONNECTION_TEST_TIMEOUT = "connectionTestTimeout"

    const val NETWORK_CHANGE_RESET_CONNECTIONS = "networkChangeResetConnections"
    const val RESTART_PROFILE_ON_NETWORK_CHANGE = "restartProfileOnNetworkChange"
    const val WAKE_RESET_CONNECTIONS = "wakeResetConnections"
    const val RULES_PROVIDER = "rulesProvider"
    const val LOG_LEVEL = "logLevel"
    const val LOG_BUF_SIZE = "logBufSize"
    const val MTU = "mtu"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"

    const val RULES_GEOSITE_URL = "rulesGeositeUrl"
    const val RULES_GEOIP_URL = "rulesGeoipUrl"
    const val RULES_UPDATE_INTERVAL = "rulesUpdateInterval"

    // Protocol Settings
    const val GLOBAL_ALLOW_INSECURE = "globalAllowInsecure"

    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"
    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"
    const val SHOW_BOTTOM_BAR = "showBottomBar"
    const val AMOLED_THEME = "amoledTheme"
    const val APP_THEME = "appTheme"
    const val CONFIRM_PROFILE_DELETE = "confirmProfileDelete"
    const val GROUP_LAYOUT_MODE = "groupLayoutMode"

    const val ALLOW_INSECURE_ON_REQUEST = "allowInsecureOnRequest"

    const val TUN_IMPLEMENTATION = "tunImplementation"
    const val PROFILE_TRAFFIC_STATISTICS = "profileTrafficStatistics"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_NAME = "profileName"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_ADDRESS = "serverAddress"
    const val SERVER_PORT = "serverPort"
    const val SERVER_USERNAME = "serverUsername"
    const val SERVER_PASSWORD = "serverPassword"
    const val SERVER_METHOD = "serverMethod"
    const val SERVER_PASSWORD1 = "serverPassword1"

    const val PROTOCOL_VERSION = "protocolVersion"

    const val SERVER_PROTOCOL = "serverProtocol"
    const val SERVER_OBFS = "serverObfs"

    const val SERVER_PROTOCOL_PARAM = "serverProtocolParam"
    const val SERVER_OBFS_PARAM = "serverObfsParam"

    const val SERVER_NETWORK = "serverNetwork"
    const val SERVER_HOST = "serverHost"
    const val SERVER_PATH = "serverPath"
    const val SERVER_SNI = "serverSNI"
    const val SERVER_ENCRYPTION = "serverEncryption"
    const val SERVER_ALPN = "serverALPN"
    const val SERVER_CERTIFICATES = "serverCertificates"
    const val SERVER_MTU = "serverMTU"

    const val SERVER_CONFIG = "serverConfig"
    const val SERVER_CUSTOM = "serverCustom"
    const val SERVER_CUSTOM_OUTBOUND = "serverCustomOutbound"

    const val SERVER_SECURITY_CATEGORY = "serverSecurityCategory"
    const val SERVER_TLS_CAMOUFLAGE_CATEGORY = "serverTlsCamouflageCategory"
    const val SERVER_ECH_CATEORY = "serverECHCategory"
    const val SERVER_WS_CATEGORY = "serverWsCategory"
    const val SERVER_SS_CATEGORY = "serverSsCategory"
    const val SERVER_HEADERS = "serverHeaders"
    const val SERVER_ALLOW_INSECURE = "serverAllowInsecure"

    const val SERVER_AUTH_TYPE = "serverAuthType"
    const val SERVER_UPLOAD_SPEED = "serverUploadSpeed"
    const val SERVER_DOWNLOAD_SPEED = "serverDownloadSpeed"
    const val SERVER_STREAM_RECEIVE_WINDOW = "serverStreamReceiveWindow"
    const val SERVER_CONNECTION_RECEIVE_WINDOW = "serverConnectionReceiveWindow"
    const val SERVER_DISABLE_MTU_DISCOVERY = "serverDisableMtuDiscovery"
    const val SERVER_HOP_INTERVAL = "hopInterval"

    const val SERVER_HY2_OBFS_TYPE = "serverHy2ObfsType"
    const val SERVER_HY2_GECKO_MIN_PACKET = "serverHy2GeckoMinPacket"
    const val SERVER_HY2_GECKO_MAX_PACKET = "serverHy2GeckoMaxPacket"
    const val SERVER_HY2_ECH_CATEGORY = "serverHy2EchCategory"
    const val SERVER_HY2_ECH_ENABLED = "serverHy2EchEnabled"
    const val SERVER_HY2_ECH_CONFIG = "serverHy2EchConfig"

    // MasterDnsVPN
    const val MDV_DOMAINS = "mdvDomains"
    const val MDV_ENCRYPTION_METHOD = "mdvEncryptionMethod"
    const val MDV_ENCRYPTION_KEY = "mdvEncryptionKey"
    const val MDV_RESOLVERS = "mdvResolvers"
    const val MDV_BALANCING_STRATEGY = "mdvBalancingStrategy"
    const val MDV_PACKET_DUP = "mdvPacketDup"
    const val MDV_SETUP_PACKET_DUP = "mdvSetupPacketDup"
    const val MDV_AUTO_DISABLE_TIMEOUT = "mdvAutoDisableTimeout"
    const val MDV_AUTO_REMOVE_LOW_MTU = "mdvAutoRemoveLowMtu"
    const val MDV_BASE_ENCODE = "mdvBaseEncode"
    const val MDV_UPLOAD_COMPRESSION = "mdvUploadCompression"
    const val MDV_DOWNLOAD_COMPRESSION = "mdvDownloadCompression"
    const val MDV_COMPRESSION_MIN_SIZE = "mdvCompressionMinSize"
    const val MDV_MIN_UPLOAD_MTU = "mdvMinUploadMtu"
    const val MDV_MIN_DOWNLOAD_MTU = "mdvMinDownloadMtu"
    const val MDV_MAX_UPLOAD_MTU = "mdvMaxUploadMtu"
    const val MDV_MAX_DOWNLOAD_MTU = "mdvMaxDownloadMtu"
    const val MDV_LOCAL_DNS_ENABLED = "mdvLocalDnsEnabled"
    const val MDV_LOCAL_DNS_PORT = "mdvLocalDnsPort"
    const val MDV_LOG_LEVEL = "mdvLogLevel"
    const val MDV_ADVANCED_JSON = "mdvAdvancedJson"

    // olcRTC
    const val OLCRTC_CARRIER = "olcrtcCarrier"
    const val OLCRTC_ROOM_ID = "olcrtcRoomId"
    const val OLCRTC_CLIENT_ID = "olcrtcClientId"
    const val OLCRTC_KEY_HEX = "olcrtcKeyHex"
    const val OLCRTC_TRANSPORT = "olcrtcTransport"
    const val OLCRTC_VP8_FPS = "olcrtcVp8Fps"
    const val OLCRTC_VP8_BATCH = "olcrtcVp8Batch"
    const val OLCRTC_DNS_SERVER = "olcrtcDnsServer"

    const val SERVER_PRIVATE_KEY = "serverPrivateKey"
    const val SERVER_INSECURE_CONCURRENCY = "serverInsecureConcurrency"

    const val SERVER_UDP_RELAY_MODE = "serverUDPRelayMode"
    const val SERVER_CONGESTION_CONTROLLER = "serverCongestionController"
    const val SERVER_DISABLE_SNI = "serverDisableSNI"
    const val SERVER_REDUCE_RTT = "serverReduceRTT"

    const val SERVER_USER_ID = "serverUserId"
    const val SERVER_PINNED_CERT_CHAIN_SHA256 = "serverPinnedCertChainSha256"

    const val ROUTE_NAME = "routeName"
    const val ROUTE_DOMAIN = "routeDomain"
    const val ROUTE_IP = "routeIP"
    const val ROUTE_PORT = "routePort"
    const val ROUTE_SOURCE_PORT = "routeSourcePort"
    const val ROUTE_NETWORK = "routeNetwork"
    const val ROUTE_SOURCE = "routeSource"
    const val ROUTE_PROTOCOL = "routeProtocol"
    const val ROUTE_RULESET = "routeRuleset"
    const val ROUTE_OUTBOUND = "routeOutbound"
    const val ROUTE_PACKAGES = "routePackages"

    const val GROUP_NAME = "groupName"
    const val GROUP_TYPE = "groupType"
    const val GROUP_ORDER = "groupOrder"
    const val GROUP_IS_SELECTOR = "groupIsSelector"
    const val GROUP_FRONT_PROXY = "groupFrontProxy"
    const val GROUP_LANDING_PROXY = "groupLandingProxy"

    const val GROUP_SUBSCRIPTION = "groupSubscription"
    const val SUBSCRIPTION_LINK = "subscriptionLink"
    const val SUBSCRIPTION_FORCE_RESOLVE = "subscriptionForceResolve"
    const val SUBSCRIPTION_DEDUPLICATION = "subscriptionDeduplication"
    const val SUBSCRIPTION_UPDATE = "subscriptionUpdate"
    const val SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY = "subscriptionUpdateWhenConnectedOnly"
    const val SUBSCRIPTION_USER_AGENT = "subscriptionUserAgent"
    const val SUBSCRIPTION_SEND_HWID = "subscriptionSendHwid"
    const val SUBSCRIPTION_CUSTOM_HWID_PARAMS = "subscriptionCustomHwidParams"
    const val SUBSCRIPTION_AUTO_UPDATE = "subscriptionAutoUpdate"
    const val SUBSCRIPTION_AUTO_UPDATE_DELAY = "subscriptionAutoUpdateDelay"
    const val SUBSCRIPTION_FILTER_MODE = "subscriptionFilterMode"
    const val SUBSCRIPTION_FILTER_REGEX = "subscriptionFilterRegex"
    const val SUBSCRIPTION_SERVER_DNS = "subscriptionServerDns"
    const val SUBSCRIPTION_CUSTOM_DNS = "subscriptionCustomDns"

    //
    const val APP_TLS_VERSION = "appTLSVersion"
    const val ENABLE_CLASH_API = "enableClashAPI"

    const val ENABLE_TLS_FRAGMENT = "enableTLSFragment"

    const val FRAGMENT_LENGTH = "fragmentLength"
    const val FRAGMENT_INTERVAL = "fragmentInterval"

    const val WEBDAV_SERVER = "webdavServer"
    const val WEBDAV_USERNAME = "webdavUsername"
    const val WEBDAV_PASSWORD = "webdavPassword"
    const val WEBDAV_PATH = "webdavPath"

    const val GLOBAL_MODE = "globalMode"
}

object TunImplementation {
    const val GVISOR = 0
    const val SYSTEM = 1
    const val MIXED = 2
}

object IPv6Mode {
    const val DISABLE = 0
    const val ENABLE = 1
    const val PREFER = 2
    const val ONLY = 3
}

object GroupType {
    const val BASIC = 0
    const val SUBSCRIPTION = 1
}

object GroupOrder {
    const val ORIGIN = 0
    const val BY_NAME = 1
    const val BY_DELAY = 2
}

object SubscriptionFilterMode {
    const val DISABLED = 0
    const val INCLUDE = 1
    const val EXCLUDE = 2
}

object Action {
    const val SERVICE = "io.nekohasekai.sagernet.SERVICE"
    const val CLOSE = "io.nekohasekai.sagernet.CLOSE"
    const val RELOAD = "io.nekohasekai.sagernet.RELOAD"

    // Optional Long extra carrying the freshly-selected profile id across the start/reload IPC,
    // so the :bg process does not depend on the UI's async write-through DB commit having landed
    // (see RoomPreferenceDataStore cached mode). -1 / absent => read selectedProxy from the store.
    const val EXTRA_PROFILE_ID = "io.nekohasekai.sagernet.EXTRA_PROFILE_ID"

    // const val SWITCH_WAKE_LOCK = "io.nekohasekai.sagernet.SWITCH_WAKELOCK"
    const val RESET_UPSTREAM_CONNECTIONS = "moe.nb4a.RESET_UPSTREAM_CONNECTIONS"
}
