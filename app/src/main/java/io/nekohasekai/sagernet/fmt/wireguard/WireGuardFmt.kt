package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Outbound_WireGuardOptions {
    return SingBoxOptions.Outbound_WireGuardOptions().apply {
        type = "wireguard"
        server = bean.serverAddress
        server_port = bean.serverPort
        local_address = normalizeWireGuardLocalAddresses(bean.localAddress)
        private_key = normalizeBase64Key(bean.privateKey)
        peer_public_key = normalizeBase64Key(bean.peerPublicKey)
        pre_shared_key = normalizeBase64Key(bean.peerPreSharedKey)
        mtu = bean.mtu
        if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)
        if (bean.isAmneziaWG) {
            _hack_config_map["jc"] = bean.jc
            _hack_config_map["jmin"] = bean.jmin
            _hack_config_map["jmax"] = bean.jmax
            _hack_config_map["s1"] = bean.s1
            _hack_config_map["s2"] = bean.s2
            _hack_config_map["s3"] = bean.s3
            _hack_config_map["s4"] = bean.s4
            if (bean.h1.isNotBlank()) _hack_config_map["h1"] = bean.h1
            if (bean.h2.isNotBlank()) _hack_config_map["h2"] = bean.h2
            if (bean.h3.isNotBlank()) _hack_config_map["h3"] = bean.h3
            if (bean.h4.isNotBlank()) _hack_config_map["h4"] = bean.h4
            if (bean.i1.isNotBlank()) _hack_config_map["i1"] = bean.i1
            if (bean.i2.isNotBlank()) _hack_config_map["i2"] = bean.i2
            if (bean.i3.isNotBlank()) _hack_config_map["i3"] = bean.i3
            if (bean.i4.isNotBlank()) _hack_config_map["i4"] = bean.i4
            if (bean.i5.isNotBlank()) _hack_config_map["i5"] = bean.i5
        }
    }
}

private fun normalizeBase64Key(value: String): String {
    if (value.isBlank()) return value
    val trimmed = value.trim()
    val remainder = trimmed.length % 4
    return if (remainder == 0) trimmed else trimmed + "=".repeat(4 - remainder)
}
