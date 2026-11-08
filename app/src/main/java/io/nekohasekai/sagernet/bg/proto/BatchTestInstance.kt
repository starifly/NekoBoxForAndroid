package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONObject
import kotlin.coroutines.suspendCoroutine

/**
 * 整组共用一个内核实例的测速器，对标 Clash 的 `/proxies/{name}/delay`：
 * 只 init/launch 一次，随后按 outbound tag 并发拨号，测完一个立刻回显。
 *
 * 原实现（[TestInstance]）每个节点都要新建并启动一次内核，节点多时开销很大。
 *
 * 注意：整组共用一个配置，任何一个节点配置非法都会让整个内核起不来。
 * 因此这里对单节点做了隔离：decode 失败时按出错的 outbound 定位节点并剔除重试，
 * 被剔除的节点记在 [excluded] 里由调用方标记失败，避免整组显示不可用。
 */
class BatchTestInstance(
    profiles: List<ProxyEntity>,
    val link: String,
    private val timeout: Int,
) : BoxInstance(profiles.first()) {

    /** 被跳过的节点：profileId -> 原因 */
    val excluded = LinkedHashMap<Long, String>()

    private var activeProfiles: List<ProxyEntity> = profiles

    /** profileId -> outbound tag，由批量配置的 buildChain 生成 */
    val tagMap: Map<Long, String>
        get() = config.profileTagMap

    /**
     * 构建配置、初始化并启动内核（插件节点会在此一并拉起）。
     * 只需调用一次，之后用 [testTag] 并发测速。
     */
    suspend fun prepare() {
        // 整组共用一个配置：任何一个节点配置非法都会让整个内核起不来，
        // decode 失败时按出错的 outbound 定位到具体节点，剔除后重建配置重试，
        // 避免一个坏节点让整组显示不可用。
        while (true) {
            try {
                startOnce()
                return
            } catch (e: Exception) {
                Logs.w("batch test prepare failed: ${e.readableMessage}", e)
                val badId = resolveFailedProfile(e)
                if (badId == null || activeProfiles.size <= 1) {
                    throw e
                }
                Logs.w("batch test: drop profile $badId (${e.readableMessage})")
                excluded[badId] = e.readableMessage
                activeProfiles = activeProfiles.filterNot { it.id == badId }
                releaseKernel()
            }
        }
    }

    private suspend fun startOnce() = suspendCoroutine<Unit> { c ->
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
        // 剔除节点后，主 profile 可能已不在列表里，取当前可用的第一个
        config = buildConfig(activeProfiles.first(), true, testProfiles = activeProfiles)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    /**
     * 从 decode 错误里定位是哪个节点的 outbound 非法。
     * sing-box 的报错形如 "decode config: outbounds[20].transport: unknown transport type: xhttp"，
     * 取该 outbound 的 tag，再反查 profileId。
     */
    private fun resolveFailedProfile(e: Throwable): Long? {
        val message = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        // 兼容 "outbounds[20]"（decode 阶段）和 "outbound[1]"（初始化阶段）
        val index = Regex("""outbounds?\[(\d+)]""").find(message)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val tag = runCatching {
            JSONObject(config.config).getJSONArray("outbounds").getJSONObject(index).optString("tag")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        // 优先精确匹配最终 outbound tag
        config.profileTagMap.entries.firstOrNull { it.value == tag }?.let { return it.key }
        // 链中间节点的 tag 形如 c-<chainId>-<profileId>
        return activeProfiles.firstOrNull { tag.endsWith("-${it.id}") }?.id
    }

    /** 丢弃上一轮已创建但不可用的内核/插件进程 */
    private fun releaseKernel() {
        // box / processes 是父类的 lateinit 属性，子类不能用 ::x.isInitialized 检查，
        // 未初始化时访问会抛 UninitializedPropertyAccessException，runCatching 兜住即可
        runCatching { box.close() }
        runCatching { processes.close(CoroutineScope(Dispatchers.IO)) }
    }
}
