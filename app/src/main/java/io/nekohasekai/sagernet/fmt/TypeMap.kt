package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity

object TypeMap : HashMap<String, Int>() {
    init {
        this["socks"] = ProxyEntity.TYPE_SOCKS
        this["http"] = ProxyEntity.TYPE_HTTP
        this["ss"] = ProxyEntity.TYPE_SS
        this["ssr"] = ProxyEntity.TYPE_SSR
        this["vmess"] = ProxyEntity.TYPE_VMESS
        this["trojan"] = ProxyEntity.TYPE_TROJAN
        this["trojan-go"] = ProxyEntity.TYPE_TROJAN_GO
        this["mieru"] = ProxyEntity.TYPE_MIERU
        this["naive"] = ProxyEntity.TYPE_NAIVE
        this["hysteria"] = ProxyEntity.TYPE_HYSTERIA
        this["ssh"] = ProxyEntity.TYPE_SSH
        this["wg"] = ProxyEntity.TYPE_WG
        this["awg"] = ProxyEntity.TYPE_AWG
        this["tuic"] = ProxyEntity.TYPE_TUIC
        this["juicity"] = ProxyEntity.TYPE_JUICITY
        this["anytls"] = ProxyEntity.TYPE_ANYTLS
        this["snell"] = ProxyEntity.TYPE_SNELL
        this["masterdnsvpn"] = ProxyEntity.TYPE_MASTERDNSVPN
        this["olcrtc"] = ProxyEntity.TYPE_OLCRTC
        this["config"] = ProxyEntity.TYPE_CONFIG
    }

    val reversed = HashMap<Int, String>()

    init {
        TypeMap.forEach { (key, type) ->
            reversed[type] = key
        }
    }
}
