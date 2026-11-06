package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.util.Locale

/**
 * 通过当前连接请求 Cloudflare trace，获取实际出口 IP 与归属国家。
 *
 * 与节点服务器地址的 geoip 查询不同，这里反映的是真实落地：
 * 中转/落地分离、CDN 入口等场景都能得到正确结果。
 */
object EgressInfo {

    data class Result(val ip: String, val countryCode: String) {

        /** 本地化国家名，如 "美国" */
        val countryName: String
            get() {
                if (countryCode.length != 2) return ""
                return try {
                    Locale.Builder().setRegion(countryCode).build().displayCountry
                } catch (e: Exception) {
                    ""
                }
            }

        /** ISO 国家码转国旗 emoji，如 "US" -> 🇺🇸 */
        val flag: String
            get() {
                if (countryCode.length != 2) return ""
                val upper = countryCode.uppercase()
                val first = upper[0]
                val second = upper[1]
                if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
                val base = 0x1F1E6 // Regional Indicator Symbol Letter A
                return String(Character.toChars(base + (first - 'A'))) +
                        String(Character.toChars(base + (second - 'A')))
            }

        /** 一行展示文本，如 "🇺🇸 美国" */
        val display: String
            get() {
                val name = countryName
                return when {
                    flag.isNotEmpty() && name.isNotEmpty() -> "$flag $name"
                    name.isNotEmpty() -> name
                    flag.isNotEmpty() -> flag
                    else -> countryCode
                }
            }
    }

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    @Volatile
    var current: Result? = null
        private set

    fun clear() {
        current = null
    }

    /**
     * 请求 trace 并解析出口 IP / 国家码，阻塞调用，必须在后台线程执行。
     * 失败返回 null（连接刚建立时可能尚未就绪，调用方自行重试）。
     */
    fun fetch(): Result? {
        val body = try {
            Libcore.httpGet(TRACE_URL, 5000)
        } catch (e: Exception) {
            return null
        }
        if (body.isNullOrBlank()) return null

        var ip = ""
        var loc = ""
        for (line in body.lineSequence()) {
            when {
                line.startsWith("ip=") -> ip = line.removePrefix("ip=").trim()
                line.startsWith("loc=") -> loc = line.removePrefix("loc=").trim()
            }
        }
        if (ip.isEmpty()) return null

        val result = Result(ip, loc.uppercase())
        current = result
        return result
    }
}
