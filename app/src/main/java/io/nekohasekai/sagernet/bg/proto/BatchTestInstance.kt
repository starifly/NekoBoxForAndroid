package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.suspendCoroutine

/**
 * 整组共用一个内核实例的测速器，对标 Clash 的 `/proxies/{name}/delay`：
 * 只 init/launch 一次，随后按 outbound tag 并发拨号，测完一个立刻回显。
 *
 * 原实现（[TestInstance]）每个节点都要新建并启动一次内核，节点多时开销很大。
 */
class BatchTestInstance(
    private val profiles: List<ProxyEntity>,
    val link: String,
    private val timeout: Int,
) : BoxInstance(profiles.first()) {

    /** profileId -> outbound tag，由批量配置的 buildChain 生成 */
    val tagMap: Map<Long, String>
        get() = config.profileTagMap

    /**
     * 构建配置、初始化并启动内核（插件节点会在此一并拉起）。
     * 只需调用一次，之后用 [testTag] 并发测速。
     */
    suspend fun prepare() = suspendCoroutine { c ->
        processes = GuardedProcessPool {
            Logs.w(it)
            c.tryResumeWithException(it)
        }
        runOnDefaultDispatcher {
            try {
                init()
                launch()
                if (processes.processCount > 0) {
                    // wait for plugin start
                    delay(500)
                }
                c.tryResume(Unit)
            } catch (e: Exception) {
                c.tryResumeWithException(e)
            }
        }
    }

    /** 在已启动的实例内测指定节点，返回 RTT（毫秒），失败抛异常 */
    fun testTag(tag: String): Int {
        return Libcore.urlTestTag(box, tag, link, timeout)
    }

    override fun buildConfig() {
        config = buildConfig(profile, true, testProfiles = profiles)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
