package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.ProxyEntity
import libcore.Libcore
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * IP 归属地国旗缓存。
 *
 * URL Test 时同步查询一次（本地 geoip.db，无需联网），卡片首次绑定时也会异步补查一次。
 * 结果按 profileId 与服务器地址双键缓存；查询失败只冷却一段时间，不做永久拉黑。
 */
object CountryFlags {

    private val byProfile = ConcurrentHashMap<Long, String>()      // profileId -> ISO 代码
    private val byHost = ConcurrentHashMap<String, String>()       // serverAddress -> ISO 代码
    private val failedAt = ConcurrentHashMap<String, Long>()       // 失败时间戳，冷却重试
    private val inFlight = ConcurrentHashMap.newKeySet<String>()   // 正在查询中的地址

    private const val RETRY_COOL_DOWN_MS = 60_000L

    fun get(profileId: Long): String? = byProfile[profileId]

    fun forget(profileId: Long) {
        byProfile.remove(profileId)
    }

    private fun hostOf(profile: ProxyEntity): String = try {
        profile.requireBean().serverAddress.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * 查询节点服务器地址的归属国家，阻塞调用，必须在后台线程执行。
     * 返回 ISO 3166-1 两位代码，查不到返回空串。
     */
    fun query(profile: ProxyEntity): String {
        val host = hostOf(profile)
        if (host.isBlank()) {
            Logs.d("CountryFlags: profile=${profile.id} 服务器地址为空")
            return ""
        }

        byHost[host]?.let { cached ->
            byProfile[profile.id] = cached
            return cached
        }

        val failedTime = failedAt[host]
        if (failedTime != null &&
            System.currentTimeMillis() - failedTime < RETRY_COOL_DOWN_MS
        ) {
            return ""
        }

        // 域名先解析成 IP（IP 字面量会直接返回，不产生 DNS 请求）
        val ip = try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            Logs.w("CountryFlags: $host 域名解析失败", e)
            null
        }
        if (ip.isNullOrBlank()) {
            failedAt[host] = System.currentTimeMillis()
            return ""
        }

        val code = try {
            Libcore.queryGeoIPCountry(ip) ?: ""
        } catch (e: Exception) {
            Logs.w("CountryFlags: 查询 $ip 归属地异常", e)
            ""
        }
        Logs.d("CountryFlags: $host -> $ip -> [$code]")

        if (code.length == 2) {
            byHost[host] = code
            byProfile[profile.id] = code
            failedAt.remove(host)
            return code
        }
        failedAt[host] = System.currentTimeMillis()
        return ""
    }

    /**
     * 卡片绘制时调用：缓存缺失就后台补查一次，查到后回调（主线程）。
     * 已在查询中或处于冷却期则直接跳过，避免滚动列表时反复解析。
     */
    fun ensure(profile: ProxyEntity, onResolved: (Long) -> Unit) {
        if (byProfile.containsKey(profile.id)) return
        val host = hostOf(profile)
        if (host.isBlank()) return

        // 同一域名已被别的节点查过，直接复用
        byHost[host]?.let { cached ->
            byProfile[profile.id] = cached
            runOnMainDispatcher { onResolved(profile.id) }
            return
        }

        val failedTime = failedAt[host]
        if (failedTime != null &&
            System.currentTimeMillis() - failedTime < RETRY_COOL_DOWN_MS
        ) return
        if (!inFlight.add(host)) return

        runOnDefaultDispatcher {
            try {
                query(profile)
            } finally {
                inFlight.remove(host)
            }
            if (byProfile.containsKey(profile.id)) {
                runOnMainDispatcher {
                    onResolved(profile.id)
                }
            }
        }
    }

    /** ISO 国家代码转国旗 emoji，如 "US" -> 🇺🇸 */
    fun flagOf(code: String?): String {
        if (code == null || code.length != 2) return ""
        val upper = code.uppercase()
        val first = upper[0]
        val second = upper[1]
        if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
        val base = 0x1F1E6 // Regional Indicator Symbol Letter A
        return String(Character.toChars(base + (first - 'A'))) +
                String(Character.toChars(base + (second - 'A')))
    }
}
