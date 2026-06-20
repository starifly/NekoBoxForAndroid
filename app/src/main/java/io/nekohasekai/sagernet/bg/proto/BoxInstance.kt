package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
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

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
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
                                app.cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }

                    is MasterDnsVpnBean -> {
                        initPlugin("masterdnsvpn-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMasterDnsVpnConfig(
                            port, File(app.noBackupFilesDir, "protect_path").absolutePath
                        )
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
                            cacheDir, "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = File(
                            cacheDir, "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = File(
                            cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = File(
                                cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".crt"
                            )

                            certFile.parentFile?.mkdirs()
                            certFile.writeText(bean.certificates)
                            cacheFiles.add(certFile)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = File(
                            cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".json"
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
                            "client"
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
                            "-json", configFile.absolutePath,
                            "-resolvers", resolversFile.absolutePath,
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
                }
            }
        }

        box.start()
    }

    /**
     * Waits until every external sidecar's local SOCKS listener is accepting connections
     * before the service reports Connected, so the sing-box socks outbound (and any
     * connection test) doesn't race a sidecar that hasn't bound its port yet.
     *
     * Most sidecars open their listener immediately, so the short connection-test timeout
     * is sufficient. MasterDnsVPN is the exception: it only starts listening after DNS
     * MTU probing and session setup, which can take tens of seconds (with retries) on
     * lossy or restricted links, so it gets a longer readiness window.
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
        val readinessTimeoutMs = if (hasMasterDnsVpn) {
            maxOf(60_000L, DataStore.connectionTestTimeout.toLong())
        } else if (strict) {
            // URL test: a healthy sidecar binds well under a second. Cap the readiness
            // wait so a slow/unbound sidecar can't make the total perceived test time
            // roughly double the configured timeout (readiness wait + the url test itself).
            minOf(2_000L, maxOf(1_000L, DataStore.connectionTestTimeout.toLong()))
        } else {
            maxOf(1_000L, DataStore.connectionTestTimeout.toLong())
        }

        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + readinessTimeoutMs
            val pending = ports.toMutableSet()
            while (pending.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val port = iterator.next()
                    try {
                        Socket().use {
                            it.connect(InetSocketAddress(LOCALHOST, port), 100)
                        }
                        iterator.remove()
                    } catch (_: IOException) {
                        // not ready yet
                    }
                }
                if (pending.isNotEmpty()) delay(50)
            }
            if (pending.isNotEmpty()) {
                // MasterDnsVPN must have its listener up before the first dial (it crashed
                // otherwise), so a timeout there is fatal. Other sidecars (Mieru/Naïve/
                // TrojanGo/Hysteria) were historically fire-and-forget: the first sing-box
                // dial retries, so a slow bind shouldn't hard-fail VPN start — log and continue.
                // For a URL test (strict), there is no retry window, so a listener that never
                // binds is reported as a clear error instead of a flaky "connection refused".
                val message = "sidecar listener not ready on port(s): ${pending.joinToString()}"
                if (hasMasterDnsVpn || strict) {
                    throw IOException(message)
                } else {
                    Logs.w("$message; continuing (sing-box will retry the connection)")
                }
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

        cacheFiles.removeAll { it.delete(); true }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::box.isInitialized) {
            box.close()
        }
    }

}
