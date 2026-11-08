package io.nekohasekai.sagernet.fmt.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    // sing-box 1.14 的 snell outbound 只接受 version 4 / 6 两种联合结构
    val version = if (bean.version == 6) 6 else 4
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        psk = bean.psk
        if (!bean.userKey.isNullOrBlank()) {
            userkey = bean.userKey
        }
        this.version = version

        if (bean.network != null && bean.network.isNotBlank()) {
            network = bean.network
        }

        if (version == 6) {
            // 1.14 的 v6 只有 mode（default / unshaped / unsafe-raw），quic_proxy_mode 已被移除
            val snellMode = bean.mode?.takeIf { it in setOf("default", "unshaped", "unsafe-raw") }
            if (snellMode != null && snellMode != "default") {
                mode = snellMode
            }
        } else if (!bean.obfsMode.isNullOrBlank()) {
            val obfsModeValue = bean.obfsMode
            if (obfsModeValue in setOf("none", "http", "tls")) {
                if (obfsModeValue != "none") obfs_mode = obfsModeValue
                if (obfs_mode.isNotBlank() && !bean.obfsHost.isNullOrBlank()) {
                    obfs_host = bean.obfsHost
                }
            }
        }

        if (bean.reuse != null && bean.reuse) {
            this.reuse = true
        }
    }
}
