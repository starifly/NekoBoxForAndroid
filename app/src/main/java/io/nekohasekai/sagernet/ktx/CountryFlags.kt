package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.ProxyEntity
import libcore.Libcore
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * IP 归属地国旗缓存。
 *
 * URL Test 时同步查询一次（本地 geoip.db，无需联网），结果按 profileId 与
 * 服务器地址双键缓存；卡片绘制时只读缓存，不做任何解析或查询。
 */
object CountryFlags {

    private val byProfile = ConcurrentHashMap<Long, String>()    // profileId -> ISO 代码
    private val byHost = ConcurrentHashMap<String, String>()     // serverAddress -> ISO 代码
    private val failed = ConcurrentHashMap.newKeySet<String>()   // 查询失败的地址，避免反复重试

    fun get(profileId: Long): String? = byProfile[profileId]

    fun forget(profileId: Long) {
        byProfile.remove(profileId)
    }

    /**
     * 查询节点服务器地址的归属国家，阻塞调用，必须在后台线程执行。
     * 返回 ISO 3166-1 两位代码，查不到返回空串。
     */
    fun query(profile: ProxyEntity): String {
        val host = try {
            profile.requireBean().serverAddress
        } catch (e: Exception) {
            return ""
        }
        if (host.isBlank()) return ""

        byHost[host]?.let { cached ->
            byProfile[profile.id] = cached
            return cached
        }
        if (host in failed) return ""

        // 域名先解析成 IP（IP 字面量会直接返回，不产生 DNS 请求）
        val ip = try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            null
        }
        if (ip.isNullOrBlank()) {
            Logs.d("CountryFlags: $host 无法解析为 IP")
            failed.add(host)
            return ""
        }

        val code = try {
            Libcore.queryGeoIPCountry(ip) ?: ""
        } catch (e: Exception) {
            Logs.w("CountryFlags: 查询 $ip 失败", e)
            ""
        }
        Logs.d("CountryFlags: $host -> $ip -> [$code]")
        if (code.length == 2) {
            byHost[host] = code
            byProfile[profile.id] = code
            return code
        }
        failed.add(host)
        return ""
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
