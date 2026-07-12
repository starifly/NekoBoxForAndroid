package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.masterdnsvpn.parseMasterDnsVpn
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.olcrtc.parseOlcrtc
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseSnell
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}

// name collision, nya
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}

fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()
    // An http(s) link that fails to parse as an HTTP proxy is a subscription candidate.
    // Don't abort import immediately (issue #1128): a file may contain valid profile
    // links alongside a plain promo/Telegram URL. Remember the first candidate and only
    // treat the input as a subscription if NO profiles parsed at all.
    var subscriptionCandidate: String? = null

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            Logs.d("Trying universal parser")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w("Universal parser rejected input")
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://",
            )
        ) {
            Logs.d("Trying SOCKS parser")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w("SOCKS parser rejected input")
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            Logs.d("Trying HTTP parser")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w("HTTP parser rejected input")
                if (subscriptionCandidate == null) {
                    val clashUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("install-config")
                        .addQueryParameter("url", this)
                        .build()
                        .toString()
                        .replaceFirst("https://", "clash://")
                    // Defer: only thrown later if no profile links were parsed.
                    subscriptionCandidate = clashUrl
                }
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Trying V2Ray parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("V2Ray parser rejected input")
            }
        } else if (startsWith("vless://")) {
            Logs.d("Trying VLESS parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("VLESS parser rejected input")
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Trying Trojan parser")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w("Trojan parser rejected input")
            }
        } else if (startsWith("trojan-go://")) {
            Logs.d("Trying Trojan-Go parser")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.w("Trojan-Go parser rejected input")
            }
        } else if (startsWith("ss://")) {
            Logs.d("Trying Shadowsocks parser")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w("Shadowsocks parser rejected input")
            }
        } else if (startsWith("ssr://")) {
            Logs.d("Trying ShadowsocksR parser")
            runCatching {
                entities.add(parseShadowsocksR(this))
            }.onFailure {
                Logs.w("ShadowsocksR parser rejected input")
            }
        } else if (startsWith("naive+")) {
            Logs.d("Trying Naive parser")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w("Naive parser rejected input")
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Trying Hysteria 1 parser")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w("Hysteria 1 parser rejected input")
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Trying Hysteria 2 parser")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w("Hysteria 2 parser rejected input")
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Trying TUIC parser")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w("TUIC parser rejected input")
            }
        } else if (startsWith("juicity://")) {
            Logs.d("Trying Juicity parser")
            runCatching {
                entities.add(parseJuicity(this))
            }.onFailure {
                Logs.w("Juicity parser rejected input")
            }
        } else if (startsWith("snell://")) {
            Logs.d("Trying Snell parser")
            runCatching {
                entities.add(parseSnell(this))
            }.onFailure {
                Logs.w("Snell parser rejected input")
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Trying AnyTLS parser")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w("AnyTLS parser rejected input")
            }
        } else if (startsWith("masterdns://")) {
            Logs.d("Trying MasterDnsVPN parser")
            runCatching {
                entities.add(parseMasterDnsVpn(this))
            }.onFailure {
                Logs.w("MasterDnsVPN parser rejected input")
            }
        } else if (startsWith("olcrtc://")) {
            Logs.d("Trying olcRTC parser")
            runCatching {
                entities.add(parseOlcrtc(this))
            }.onFailure {
                Logs.w("olcRTC parser rejected input")
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
    // No profile links parsed but we saw an unparsable http(s) URL: treat the whole
    // input as a subscription link (single-URL paste / file). When profiles WERE found,
    // the stray URL is ignored so the profiles still import (issue #1128).
    if (entities.isEmpty() && entitiesByLine.isEmpty()) {
        subscriptionCandidate?.let { throw SubscriptionFoundException(it) }
    }
//    var isBadLink = false
    if (entities.onEach {
            it.initializeDefaultValues()
        }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size
    ) {
        run test@{
            entities.forEachIndexed { index, bean ->
                val lineBean = entitiesByLine[index]
                if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                    return@test
                }
            }
        }
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
