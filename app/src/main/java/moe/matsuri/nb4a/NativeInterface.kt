package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.route.routerNodeKey
import io.nekohasekai.sagernet.route.routerStableIdOrFallback
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        val service = DataStore.baseService
        val proxy = service?.data?.proxy
        val routerTag = proxy?.config?.routerSelectorTags?.entries
            ?.firstOrNull { it.value == selectorTag }?.key
        if (routerTag != null && service != null && proxy != null) {
            val id = proxy.config.profileTagMap
                .filterValues { it == tag }.keys.firstOrNull() ?: return
            runOnDefaultDispatcher {
                if (DataStore.baseService !== service || service.data.proxy !== proxy) return@runOnDefaultDispatcher
                val selected = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                SagerDatabase.routerGroupDao.getByStableTag(routerTag)?.let { router ->
                    SagerDatabase.routerGroupDao.updateSelection(
                        routerId = router.id,
                        selectedProxyId = id,
                        selectedNodeKey = routerNodeKey(
                            selected.groupId,
                            routerStableIdOrFallback(selected.uuid, selected.id),
                        ),
                    )
                }
            }
            return
        }
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        if (service == null || proxy == null) return
        runOnDefaultDispatcher {
            if (DataStore.baseService !== service || service.data.proxy !== proxy) return@runOnDefaultDispatcher
            val id = proxy.config.profileTagMap
                .filterValues { it == tag }.keys.firstOrNull() ?: -1
            val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
            // traffic & title
            proxy.apply {
                looper?.selectMain(id)
                displayProfileName = ServiceNotification.genTitle(ent)
                service.data.notification?.postNotificationTitle(displayProfileName)
            }
            // post binder
            service.data.binder.broadcast { b ->
                b.cbSelectorUpdate(id)
            }
        }
    }

}
