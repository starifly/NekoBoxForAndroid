package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File

// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = url.replace("hysteria://", "https://").toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url",
    )
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = link.host
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("alpn")?.also {
            if (it != "none") alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
        // obfs type: salamander (default when password present) or gecko.
        link.queryParameter("obfs")?.also {
            hysteria2ObfsType = when (it.lowercase()) {
                "gecko" -> HysteriaBean.OBFS_GECKO
                "salamander" -> HysteriaBean.OBFS_SALAMANDER
                else -> HysteriaBean.OBFS_NONE
            }
        }
        link.queryParameter("obfs-min-packet-size")?.toIntOrNull()?.also {
            geckoMinPacketSize = it
        }
        link.queryParameter("obfs-max-packet-size")?.toIntOrNull()?.also {
            geckoMaxPacketSize = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank() && hysteria2ObfsType != HysteriaBean.OBFS_NONE) {
            when (hysteria2ObfsType) {
                HysteriaBean.OBFS_GECKO -> {
                    builder.addQueryParameter("obfs", "gecko")
                    builder.addQueryParameter("obfs-password", obfuscation)
                    builder.addQueryParameter("obfs-min-packet-size", geckoMinPacketSize.toString())
                    builder.addQueryParameter("obfs-max-packet-size", geckoMaxPacketSize.toString())
                }

                else -> {
                    builder.addQueryParameter("obfs", "salamander")
                    builder.addQueryParameter("obfs-password", obfuscation)
                }
            }
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    // HY1 JSON (legacy). HY2 JSON is handled by parseHysteria2Json below.
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = optString("server").substringBeforeLast(":")
        serverPorts = optString("server").substringAfterLast(":")
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        sni = getStr("server_name")
        getStr("alpn")?.also { if (it != "none") alpn = it }
        allowInsecure = getBool("insecure")

        streamReceiveWindow = getIntNya("recv_window_conn")
        connectionReceiveWindow = getIntNya("recv_window")
        disableMtuDiscovery = getBool("disable_mtu_discovery")
    }
}

/**
 * Parse a Hysteria 2 client config in JSON (or Clash/Mihomo-style) form into a HysteriaBean.
 *
 * Mirrors the official HY2 client config schema and the parseHysteria2(url) field mapping:
 *   server: "host:port"            -> serverAddress / serverPorts
 *   auth: "<string>"               -> authPayload (string type)
 *   tls: { sni, insecure }         -> sni / allowInsecure
 *   obfs: { type, salamander|gecko: { password, minPacketSize, maxPacketSize } } -> obfs fields
 *   bandwidth: { up, down }        -> uploadMbps / downloadMbps (Mbps ints when numeric)
 */
fun JSONObject.parseHysteria2Json(): HysteriaBean {
    return HysteriaBean().apply {
        protocolVersion = 2
        val server = optString("server")
        // Only split off a port when there's an explicit one. A bare host or an IPv6 literal
        // without a port must keep the whole string as the address (default port 443),
        // matching parseHysteria2(url)'s HttpUrl behavior.
        val lastColon = server.lastIndexOf(':')
        val portPart = if (lastColon >= 0) server.substring(lastColon + 1) else ""
        if (lastColon >= 0 && portPart.toIntOrNull() != null && !server.endsWith("]")) {
            serverAddress = server.substring(0, lastColon)
            serverPorts = portPart
        } else {
            serverAddress = server
            serverPorts = "443"
        }
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        // tls block (sni / insecure).
        optJSONObject("tls")?.also { tls ->
            tls.getStr("sni")?.also { sni = it }
            tls.getBool("insecure")?.also { allowInsecure = it }
        }
        // obfs block: { type: "salamander"|"gecko", salamander: { password }, gecko: {...} }.
        optJSONObject("obfs")?.also { obfs ->
            when (obfs.getStr("type")?.lowercase()) {
                "gecko" -> {
                    hysteria2ObfsType = HysteriaBean.OBFS_GECKO
                    obfs.optJSONObject("gecko")?.also { g ->
                        g.getStr("password")?.also { obfuscation = it }
                        g.getIntNya("min_packet_size")?.also { geckoMinPacketSize = it }
                        g.getIntNya("max_packet_size")?.also { geckoMaxPacketSize = it }
                    }
                }

                "salamander" -> {
                    hysteria2ObfsType = HysteriaBean.OBFS_SALAMANDER
                    obfs.optJSONObject("salamander")?.getStr("password")?.also { obfuscation = it }
                }

                else -> hysteria2ObfsType = HysteriaBean.OBFS_NONE
            }
        }
        // bandwidth block: accept a bare integer (Mbps) or a unit-suffixed string ("100 mbps",
        // "1 gbps"), normalizing to Mbps. HY2 configs commonly use the string form.
        optJSONObject("bandwidth")?.also { bw ->
            parseBandwidthMbps(bw, "up")?.also { uploadMbps = it }
            parseBandwidthMbps(bw, "down")?.also { downloadMbps = it }
        }
        name = optString("name").takeIf { it.isNotBlank() }
    }
}

/** Read a HY2 bandwidth value as Mbps: a bare int, or a unit-suffixed string (bps/kbps/mbps/gbps/tbps). */
private fun parseBandwidthMbps(obj: JSONObject, key: String): Int? {
    obj.getIntNya(key)?.let { return it }
    val raw = obj.getStr(key)?.trim()?.lowercase() ?: return null
    val match = Regex("""^(\d+(?:\.\d+)?)\s*([a-z]*)$""").matchEntire(raw) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val mbps = when (match.groupValues[2]) {
        "", "mbps", "m" -> value
        "bps", "b" -> value / 1_000_000.0
        "kbps", "k" -> value / 1_000.0
        "gbps", "g" -> value * 1_000.0
        "tbps", "t" -> value * 1_000_000.0
        else -> return null
    }
    return mbps.toInt().coerceAtLeast(0)
}

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    return JSONObject().apply {
        put("server", displayAddress())
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5",
            JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                ),
            ),
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
        if (sni.isNotBlank()) {
            put("server_name", sni)
        }
        if (alpn.isNotBlank()) put("alpn", alpn)
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window_conn", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    // Hysteria2 always uses the native sing-box outbound; the faketcp / wechat-video
    // transports are a Hysteria1-only concept, and gecko obfs is handled natively by
    // this fork's core (via the hawkff/sing-quic gecko backport).
    if (protocolVersion == 2) return true
    // Hysteria1 falls back to the external plugin for the non-UDP transports.
    return protocol == HysteriaBean.PROTOCOL_UDP
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            disable_mtu_discovery = bean.disableMtuDiscovery
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow > 0) {
                recv_window_conn = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                recv_window_conn = bean.connectionReceiveWindow.toLong()
            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                if (bean.alpn.isNotBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank() && bean.hysteria2ObfsType != HysteriaBean.OBFS_NONE) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    when (bean.hysteria2ObfsType) {
                        HysteriaBean.OBFS_GECKO -> {
                            type = "gecko"
                            password = bean.obfuscation
                            // Clamp and order the bounds (1..2048, min <= max);
                            // an inverted or out-of-range pair would otherwise be
                            // rejected by the core at connect time.
                            val min = bean.geckoMinPacketSize?.takeIf { it > 0 }?.coerceIn(1, 2048)
                            val max = bean.geckoMaxPacketSize?.takeIf { it > 0 }?.coerceIn(min ?: 1, 2048)
                            if (min != null) min_packet_size = min
                            if (max != null) max_packet_size = max
                        }

                        else -> {
                            type = "salamander"
                            password = bean.obfuscation
                        }
                    }
                }
            }
//            disable_mtu_discovery = bean.disableMtuDiscovery
            password = bean.authPayload
//            if (bean.streamReceiveWindow > 0) {
//                recv_window_conn = bean.streamReceiveWindow.toLong()
//            }
//            if (bean.connectionReceiveWindow > 0) {
//                recv_window_conn = bean.connectionReceiveWindow.toLong()
//            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val pRange = it.replace("-", ":")
        if (pRange.split(":").size == 2) {
            pRange
        } else {
            null
        }
    }
}
