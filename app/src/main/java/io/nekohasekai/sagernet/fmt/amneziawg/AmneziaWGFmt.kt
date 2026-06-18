package io.nekohasekai.sagernet.fmt.amneziawg

import io.nekohasekai.sagernet.fmt.wireguard.genReserved
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxOutboundAmneziaWGBean(bean: AmneziaWGBean): SingBoxOptions.Outbound_AmneziaWGOptions {
    return SingBoxOptions.Outbound_AmneziaWGOptions().apply {
        type = "amneziawg"
        server = bean.serverAddress
        server_port = bean.serverPort
        local_address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        peer_public_key = bean.peerPublicKey
        pre_shared_key = bean.peerPreSharedKey
        mtu = bean.mtu
        if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)

        // AmneziaWG obfuscation parameters; zero/blank values are omitted so the
        // tunnel behaves like plain WireGuard when unset.
        if (bean.jc != 0) jc = bean.jc
        if (bean.jmin != 0) jmin = bean.jmin
        if (bean.jmax != 0) jmax = bean.jmax
        if (bean.s1 != 0) s1 = bean.s1
        if (bean.s2 != 0) s2 = bean.s2
        if (bean.s3 != 0) s3 = bean.s3
        if (bean.s4 != 0) s4 = bean.s4
        if (bean.h1.isNotBlank()) h1 = bean.h1
        if (bean.h2.isNotBlank()) h2 = bean.h2
        if (bean.h3.isNotBlank()) h3 = bean.h3
        if (bean.h4.isNotBlank()) h4 = bean.h4
        if (bean.i1.isNotBlank()) i1 = bean.i1
        if (bean.i2.isNotBlank()) i2 = bean.i2
        if (bean.i3.isNotBlank()) i3 = bean.i3
        if (bean.i4.isNotBlank()) i4 = bean.i4
        if (bean.i5.isNotBlank()) i5 = bean.i5
    }
}
