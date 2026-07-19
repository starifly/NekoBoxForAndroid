package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import org.json.JSONObject
import android.net.VpnService as BaseVpnService

class VpnService :
    BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
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

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        conn?.close()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) = ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this,
                        VpnRequestActivity::class.java,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
            }
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException :
        NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        {
            val packageName = packageName
            val proxyApps = DataStore.proxyApps
            var bypass = DataStore.bypass
            val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
            val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
                it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
            }

            if (proxyApps || needBypassRootUid) {
                val individual = mutableSetOf<String>()
                val allApps by lazy {
                    packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                        when (it.packageName) {
                            packageName -> false
                            "android" -> true
                            else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                        }
                    }.map {
                        it.packageName
                    }
                }
                if (proxyApps) {
                    individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                    if (bypass && needBypassRootUid) {
                        val individualNew = allApps.toMutableList()
                        individualNew.removeAll(individual)
                        individual.clear()
                        individual.addAll(individualNew)
                        bypass = false
                    }
                } else {
                    individual.addAll(allApps)
                    bypass = false
                }

                val added = mutableListOf<String>()

                individual.apply {
                    // Allow Matsuri itself using VPN.
                    remove(packageName)
                    if (!bypass) add(packageName)
                    // Keep Android system DNS resolver traffic inside VPN in allow-list mode.
                    if (!bypass) addAll(ANDROID_DNS_PACKAGES)
                    // In bypass mode, force Android system UID traffic out of VPN.
                    if (bypass) add("android")
                }.forEach {
                    try {
                        if (bypass) {
                            builder.addDisallowedApplication(it)
                        } else {
                            builder.addAllowedApplication(it)
                        }
                        added.add(it)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        Logs.w(ex)
                    }
                }

                if (bypass) {
                    Logs.d("Add bypass: ${added.joinToString(", ")}")
                } else {
                    Logs.d("Add allow: ${added.joinToString(", ")}")
                }
            }
        }
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            if (DataStore.allowAccess) {
                // When LAN access is enabled the mixed inbound requires authentication
                // (see DataStore.mixedInboundNeedsAuth). Android's system HTTP proxy
                // cannot supply credentials, so registering it here would just fail.
                // Skip it instead of weakening inbound auth and exposing an open LAN proxy.
                Logs.w(
                    "Append HTTP proxy was skipped because LAN access requires proxy " +
                        "authentication. Disable Allow LAN access to use system HTTP proxy.",
                )
            } else {
                val proxyInfo = runCatching {
                    val exclusionList = parseHttpProxyBypass(DataStore.httpProxyBypass)
                    if (exclusionList.isNotEmpty()) {
                        ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.httpPort, exclusionList)
                    } else {
                        ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.httpPort)
                    }
                }.getOrElse {
                    // A malformed exclusion entry must never block service start.
                    Logs.w("Invalid HTTP proxy bypass list, ignoring it", it)
                    ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.httpPort)
                }
                builder.setHttpProxy(proxyInfo)
            }
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    // Build a validated exclusion list for the system HTTP proxy. Entries are
    // Android ProxyInfo host/suffix patterns (NOT CIDRs), one per line (commas
    // also accepted). Each entry is validated individually so a single bad
    // pattern is dropped instead of invalidating the whole list or blocking
    // service start.
    private fun parseHttpProxyBypass(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isValidProxyBypassEntry(it) }
            .distinct()
    }

    // Mirror the host/suffix patterns Android's ProxyInfo accepts: a bare "*",
    // or dot-separated labels where each label is alphanumeric (hyphens allowed
    // internally) and a single "*" wildcard label is allowed only as the first
    // or last label (e.g. "192.168.*", "*.example.com"). Rejects empty labels
    // (e.g. ".."), leading/trailing dots and stray characters.
    private fun isValidProxyBypassEntry(entry: String): Boolean {
        if (entry == "*") return true
        val labels = entry.split('.')
        val label = Regex("^[A-Za-z0-9]+(-+[A-Za-z0-9]+)*$")
        labels.forEachIndexed { index, l ->
            if (l == "*") {
                if (index != 0 && index != labels.lastIndex) return false
            } else if (!label.matches(l)) {
                return false
            }
        }
        return true
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
        builder?.setUnderlyingNetworks(networks) ?: setUnderlyingNetworks(networks)
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
