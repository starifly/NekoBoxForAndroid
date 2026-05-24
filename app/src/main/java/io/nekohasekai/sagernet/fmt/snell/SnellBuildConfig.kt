package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        psk = bean.psk
        version = bean.version

        if (bean.network != null && bean.network.isNotBlank()) {
            network = bean.network
        }

        if (bean.obfsMode != null && bean.obfsMode.isNotBlank()) {
            obfs_mode = bean.obfsMode
            if (bean.obfsHost != null && bean.obfsHost.isNotBlank()) {
                obfs_host = bean.obfsHost
            }
        }

        if (bean.reuse != null && bean.reuse) {
            this.reuse = true
        }
    }
}
