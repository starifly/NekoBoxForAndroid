package io.nekohasekai.sagernet.fmt.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        psk = bean.psk
        if (!bean.userKey.isNullOrBlank()) {
            userkey = bean.userKey
        }
        version = bean.version

        if (bean.network != null && bean.network.isNotBlank()) {
            network = bean.network
        }

        if (bean.version == 6) {
            if (!bean.mode.isNullOrBlank() && bean.mode != "default") {
                mode = bean.mode
            }
        } else if (bean.obfsMode != null && bean.obfsMode.isNotBlank()) {
            obfs_mode = if (bean.version != null && bean.version >= 4 && bean.obfsMode == "tls") "" else bean.obfsMode
            if (obfs_mode.isNotBlank() && bean.obfsHost != null && bean.obfsHost.isNotBlank()) {
                obfs_host = bean.obfsHost
            }
        }

        if (bean.reuse != null && bean.reuse) {
            this.reuse = true
        }
    }
}
