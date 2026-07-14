package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.GuardedProcessRestartPolicy
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.masterdnsvpn.MasterDnsVpnBean
import io.nekohasekai.sagernet.fmt.masterdnsvpn.buildMasterDnsVpnConfig
import io.nekohasekai.sagernet.fmt.masterdnsvpn.resolverLines
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.olcrtc.OlcrtcBean
import io.nekohasekai.sagernet.fmt.olcrtc.buildOlcrtcArgs
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

abstract class BoxInstance(
    val profile: ProxyEntity,
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    protected open val enableOlcrtcRecovery = true
    private val olcrtcReadyMarkers = hashMapOf<Int, File>()
    private val olcrtcReadyMarkerOwner = UUID.randomUUID().toString()
    private var cacheFiles = ArrayList<File>()

    private fun olcrtcReadyTimeoutMillis() = olcrtcSidecarReadyTimeoutMillis(
        configuredTimeoutMillis = DataStore.connectionTestTimeout.toLong(),
        recoveryEnabled = enableOlcrtcRecovery,
    )

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
        DataStore.mixedInboundAuthed = DataStore.mixedInboundNeedsAuth
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        val creds = config.localProxyCredentials[port]
                        pluginConfigs[port] = profile.type to
                            bean.buildNaiveConfig(port, creds?.first, creds?.second)
                    }

                    is HysteriaBean -> {
                        // Only reached via the external path (needExternal == !canUseSingBox).
                        // Hysteria2 (incl. Gecko obfs) runs natively in sing-box now, so the
                        // external path is Hysteria v1 only.
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            File(
                                app.cacheDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".ca",
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }

                    is MasterDnsVpnBean -> {
                        initPlugin("masterdnsvpn-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMasterDnsVpnConfig(
                            port,
                            File(app.noBackupFilesDir, "protect_path").absolutePath,
                        )
                    }

                    is OlcrtcBean -> {
                        initPlugin("olcrtc-plugin")
                        // The olcRTC client sidecar takes CLI flags (no config file). Loopback
                        // SOCKS creds keep other apps off the 127.0.0.1 egress port; fail closed
                        // rather than start an unauthenticated listener if they are missing.
                        val creds = config.localProxyCredentials[port]
                            ?: error("olcRTC: missing loopback SOCKS credentials for port $port")
                        val readyTimeoutMs = olcrtcReadyTimeoutMillis()
                        val readyMarker = File(
                            app.noBackupFilesDir,
                            olcrtcReadyMarkerFileName(port, olcrtcReadyMarkerOwner),
                        )
                        olcrtcReadyMarkers[port] = readyMarker
                        val args = bean.buildOlcrtcArgs(
                            port,
                            File(app.noBackupFilesDir, "protect_path").absolutePath,
                            creds.first,
                            creds.second,
                            DataStore.logLevel >= 3,
                            "9.9.9.9:53",
                            readyTimeoutMs,
                        ) + listOf("-ready-marker", readyMarker.absolutePath)
                        pluginConfigs[port] = profile.type to args.joinToString("\u0000")
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = File(
                            cacheDir,
                            "trojan_go_" + SystemClock.elapsedRealtime() + ".json",
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path,
                            "-config",
                            configFile.absolutePath,
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = File(
                            cacheDir,
                            "mieru_" + SystemClock.elapsedRealtime() + ".json",
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path,
                            "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = File(
                            cacheDir,
                            "naive_" + SystemClock.elapsedRealtime() + ".json",
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = File(
                                cacheDir,
                                "naive_" + SystemClock.elapsedRealtime() + ".crt",
                            )

                            certFile.parentFile?.mkdirs()
                            certFile.writeText(bean.certificates)
                            cacheFiles.add(certFile)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path,
                            configFile.absolutePath,
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = File(
                            cacheDir,
                            "hysteria_" + SystemClock.elapsedRealtime() + ".json",
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client",
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }

                    bean is MasterDnsVpnBean -> {
                        // Port is unique per chain entry, so two sidecars can't collide
                        // (unlike a bare elapsedRealtime() shared by the config+resolvers pair).
                        val ts = "${port}_${SystemClock.elapsedRealtime()}"
                        val configFile = File(cacheDir, "masterdnsvpn_$ts.json")
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val resolversFile = File(cacheDir, "masterdnsvpn_${ts}_resolvers.txt")
                        resolversFile.writeText(bean.resolverLines())
                        cacheFiles.add(resolversFile)

                        val commands = mutableListOf(
                            initPlugin("masterdnsvpn-plugin").path,
                            "-json",
                            configFile.absolutePath,
                            "-resolvers",
                            resolversFile.absolutePath,
                        )

                        // The bundled MasterDnsVPN client is a CGO/PIE Go binary. When it is
                        // launched in the VpnService process context, the Go runtime's
                        // asynchronous preemption (signal-based) can fault during the first
                        // protected upstream socket dial, terminating the sidecar before it
                        // opens its local SOCKS listener. Disabling async preemption avoids
                        // the fault; the sidecar workload is I/O-bound so scheduling latency
                        // is unaffected in practice.
                        val env = mutableMapOf("GODEBUG" to "asyncpreemptoff=1")

                        processes.start(commands, env)
                    }

                    bean is OlcrtcBean -> {
                        // olcRTC client sidecar (libolcrtc.so). Args were NUL-joined in
                        // buildConfig; split them back into argv. Sockets are protected via
                        // the protect_path unix socket passed in the args.
                        val args = config.split("\u0000")
                        val commands = mutableListOf(initPlugin("olcrtc-plugin").path)
                        commands.addAll(args)
                        // Disable Go async preemption, matching the MasterDnsVPN sidecar: the
                        // signal-based preemption can fault during the first protected dial
                        // in the VpnService process context.
                        val env = mutableMapOf("GODEBUG" to "asyncpreemptoff=1")
                        val readyMarker = olcrtcReadyMarkers.getValue(port)
                        clearOlcrtcReadyMarker(readyMarker)
                        if (enableOlcrtcRecovery) {
                            processes.start(
                                commands,
                                env,
                                onRestartPrepare = { clearOlcrtcReadyMarker(readyMarker) },
                                onRestartCallback = {
                                    awaitExternalPortReady(
                                        port,
                                        olcrtcReadyTimeoutMillis() + 5_000L,
                                    )
                                },
                                restartPolicy = GuardedProcessRestartPolicy(),
                            )
                        } else {
                            processes.start(commands, env, restartOnExit = false)
                        }
                    }
                }
            }
        }

        box.start()
    }

    private fun clearOlcrtcReadyMarker(marker: File) {
        if (marker.exists() && !marker.delete() && marker.exists()) {
            throw IOException("olcRTC: could not reset readiness marker")
        }
    }

    private suspend fun pendingExternalPorts(ports: Collection<Int>, timeoutMillis: Long) =
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            val pending = ports.toMutableSet()
            while (
                pending.isNotEmpty() &&
                SystemClock.elapsedRealtime() < deadline &&
                processes.isActive
            ) {
                ensureActive()
                if (!processes.isActive) break
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val port = iterator.next()
                    val readyMarker = olcrtcReadyMarkers[port]
                    if (!readinessMarkerSatisfied(readyMarker != null, readyMarker?.isFile == true)) continue
                    try {
                        Socket().use {
                            it.connect(InetSocketAddress(LOCALHOST, port), 100)
                        }
                        iterator.remove()
                    } catch (_: IOException) {
                        // not ready yet
                    }
                }
                if (pending.isNotEmpty()) {
                    if (!processes.isActive) break
                    delay(50)
                }
            }
            pending
        }

    private suspend fun awaitExternalPortReady(port: Int, timeoutMillis: Long) {
        if (pendingExternalPorts(listOf(port), timeoutMillis).isNotEmpty()) {
            throw IOException("sidecar listener not ready on port: $port")
        }
    }

    /**
     * Waits until every external sidecar's local SOCKS listener is accepting connections
     * before the service reports Connected, so the sing-box socks outbound (and any
     * connection test) doesn't race a sidecar that hasn't bound its port yet.
     *
     * Most sidecars open their listener immediately, so the short connection-test timeout
     * is sufficient. MasterDnsVPN and olcRTC are exceptions: they only start listening
     * after carrier setup, which can take tens of seconds (with retries) on lossy or
     * restricted links, so they get a longer readiness window.
     *
     * @param strict when true (URL test), a sidecar that never binds is a hard failure with
     * a clear message, instead of the live-service behavior of logging and continuing
     * (the live path keeps a long-lived connection that sing-box retries; a one-shot URL
     * test has no such luxury and would otherwise surface a flaky "connection refused").
     */
    suspend fun awaitExternalProcessesReady(strict: Boolean = false) {
        if (!::processes.isInitialized || processes.processCount == 0) return
        val ports = config.externalIndex.flatMap { it.chain.keys }.distinct()
        if (ports.isEmpty()) return

        val hasMasterDnsVpn = config.externalIndex.any { idx ->
            idx.chain.values.any { it.requireBean() is MasterDnsVpnBean }
        }
        // olcRTC, like MasterDnsVPN, binds its SOCKS listener only after the WebRTC carrier
        // connects (room join + ICE), which can take tens of seconds; give it the long window.
        val hasOlcrtc = config.externalIndex.any { idx ->
            idx.chain.values.any { it.requireBean() is OlcrtcBean }
        }
        val readinessTimeoutMs = if (hasMasterDnsVpn || hasOlcrtc) {
            if (strict) {
                // URL test: no retry window, so don't force the full live-path floor and
                // hang ~60s on a sidecar that never binds. Still give the carrier room to
                // join (tens of seconds), bounded by the configured test timeout.
                maxOf(15_000L, DataStore.connectionTestTimeout.toLong())
            } else {
                maxOf(60_000L, DataStore.connectionTestTimeout.toLong())
            }
        } else if (strict) {
            // URL test: a healthy sidecar binds well under a second. Cap the readiness
            // wait so a slow/unbound sidecar can't make the total perceived test time
            // roughly double the configured timeout (readiness wait + the url test itself).
            minOf(2_000L, maxOf(1_000L, DataStore.connectionTestTimeout.toLong()))
        } else {
            maxOf(1_000L, DataStore.connectionTestTimeout.toLong())
        }

        val pending = pendingExternalPorts(ports, readinessTimeoutMs)
        if (pending.isNotEmpty()) {
            // If the process pool is no longer active, its sidecars were torn down (e.g. a
            // superseded start during reload). A port that never bound on a dead pool is an
            // orphan, not a real failure - drop it instead of throwing.
            if (!processes.isActive) {
                Logs.w(
                    "sidecar listener not ready on port(s): ${pending.joinToString()}; " +
                        "process pool already stopped (superseded start), ignoring",
                )
                return
            }
            // MasterDnsVPN and olcRTC must have their listeners up before the first dial,
            // so a timeout on either is fatal. Other sidecars (Mieru/Naïve/
            // TrojanGo/Hysteria) were historically fire-and-forget: the first sing-box
            // dial retries, so a slow bind shouldn't hard-fail VPN start - log and continue.
            // For a URL test (strict), there is no retry window, so a listener that never
            // binds is reported as a clear error instead of a flaky "connection refused".
            val message = "sidecar listener not ready on port(s): ${pending.joinToString()}"
            val requiredPorts = config.externalIndex.flatMap { idx ->
                idx.chain.mapNotNull { (port, profile) ->
                    when (profile.requireBean()) {
                        is MasterDnsVpnBean, is OlcrtcBean -> port
                        else -> null
                    }
                }
            }.toSet()
            if (shouldFailSidecarReadiness(pending, requiredPorts, strict)) {
                throw IOException(message)
            } else {
                Logs.w("$message; continuing (sing-box will retry the connection)")
            }
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll {
            it.delete()
            true
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)
        olcrtcReadyMarkers.values.forEach { it.delete() }

        if (::box.isInitialized) {
            box.close()
        }
    }
}
