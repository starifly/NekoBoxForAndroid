package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.route.RouterRuntimeMode
import io.nekohasekai.sagernet.route.RouterSelection
import io.nekohasekai.sagernet.route.RouterSelectionPlan
import io.nekohasekai.sagernet.route.RouterSelectionRequest
import io.nekohasekai.sagernet.route.routerNodeKey
import io.nekohasekai.sagernet.route.routerStableIdOrFallback
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        @Volatile var state = State.Stopped
        @Volatile var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload(
                    intent.getStringExtra(Action.EXTRA_ROUTER_TAG),
                    intent.getLongExtra(Action.EXTRA_ROUTER_PROXY_ID, 0L).takeIf { it > 0L },
                    intent.getBooleanExtra(Action.EXTRA_FORCE_FULL_RELOAD, false),
                )
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (SagerNet.power.isDeviceIdleMode) {
                        proxy?.box?.sleep()
                    } else {
                        proxy?.box?.wake()
                        if (DataStore.wakeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                callback?.let(callbackIdMap::remove)
            }
        }

        val callbackIdMap = ConcurrentHashMap<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"
        override fun getCurrentUrlTestSelections(): LongArray =
            data?.takeIf { it.state == State.Connected }?.proxy?.currentUrlTestSelections()
                ?: longArrayOf()

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
            if (id == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                data?.proxy?.looper?.requestUpdate()
            }
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, DataStore.connectionTestTimeout
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            callbackIdMap.clear()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload(
            routerTag: String? = null,
            routerProxyId: Long? = null,
            forceFullReload: Boolean = false,
        ) {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val routerReloadRequested = routerTag != null && routerProxyId != null
            if (routerReloadRequested && trySelectRouter(routerTag!!, routerProxyId!!)) return
            if (!forceFullReload && !routerReloadRequested && canReloadSelector()) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // select from GUI
                    data.proxy!!.box.selectOutbound(tag)
                    // or select from webui
                    // => selector_OnProxySelected
                }
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        private fun trySelectRouter(routerTag: String, proxyId: Long): Boolean {
            val runningProxy = data.proxy ?: return false
            if (routerTag.isBlank()) return false
            val router = SagerDatabase.routerGroupDao.getByStableTag(routerTag)
                ?.takeIf { it.enabled && it.stableTag.isNotBlank() && it.stableTag == routerTag }
                ?: return false
            if (runningProxy.config.routerSelectorTags[routerTag].isNullOrBlank()) return false
            val plan = RouterSelection.plan(
                request = RouterSelectionRequest(
                    routerTag = routerTag,
                    proxyId = proxyId,
                    mode = if (router.mode == RouterGroup.MODE_URL_TEST) {
                        RouterRuntimeMode.URL_TEST
                    } else {
                        RouterRuntimeMode.SELECTOR
                    },
                    routerEnabled = router.enabled,
                ),
                routerSelectorTags = runningProxy.config.routerSelectorTags,
                routerMemberIds = runningProxy.config.routerMemberIds,
                profileTags = runningProxy.config.profileTagMap,
                selectorGroupId = runningProxy.config.selectorGroupId,
            )
            if (plan !is RouterSelectionPlan.HotSwitch) return false
            if (!runningProxy.isInitialized() || !runningProxy.box.selectOutboundFor(plan.selectorTag, plan.targetTag)) {
                return false
            }
            val selected = SagerDatabase.proxyDao.getById(proxyId) ?: return false
            SagerDatabase.routerGroupDao.updateSelection(
                routerId = router.id,
                selectedProxyId = proxyId,
                selectedNodeKey = routerNodeKey(
                    selected.groupId,
                    routerStableIdOrFallback(selected.uuid, selected.id),
                ),
            )
            return true
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            startForegroundService(Intent(this, javaClass))
        }

        fun killProcesses() {
            data.proxy?.close()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false

            if (data.state == State.Stopping) return
            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null
                }

                // change the state
                data.changeState(State.Stopped, msg)
                // stop the service if nothing has bound to it
                if (restart) startRunner() else {
                    stopSelf()
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            var previousNetwork: android.net.Network? = null
            var hadNetwork = false
            DefaultNetworkListener.start(this) { network ->
                if (network == null) {
                    previousNetwork = null
                    SagerNet.underlyingNetwork = null
                    return@start
                }
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    val networkChanged = hadNetwork && previousNetwork != network
                    previousNetwork = network
                    hadNetwork = true
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (networkChanged || (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName)) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                        if (DataStore.networkChangeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                        val runningProxy = data.proxy
                        if (data.state == State.Connected && runningProxy?.isInitialized() == true) {
                            data.binder.launch(Dispatchers.IO) {
                                if (data.state != State.Connected || data.proxy !== runningProxy) return@launch
                                runningProxy.config.routerUrlTestTags.values.distinct().forEach {
                                    runningProxy.box.refreshURLTestFor(it)
                                }
                            }
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                ContextCompat.registerReceiver(
                    this, data.receiver, filter, "$packageName.SERVICE", null,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = data.binder.launch(start = CoroutineStart.LAZY) {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            data.connectingJob?.start()
            return Service.START_NOT_STICKY
        }
    }

}
