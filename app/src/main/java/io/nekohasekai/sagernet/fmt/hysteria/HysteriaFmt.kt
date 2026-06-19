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
        "invalid hysteria link $url"
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
        queryInt(link, "initStreamReceiveWindow", "init_stream_receive_window", "init-stream-receive-window")?.also {
            hy2InitialStreamReceiveWindow = it
        }
        queryInt(link, "maxStreamReceiveWindow", "max_stream_receive_window", "max-stream-receive-window")?.also {
            hy2MaxStreamReceiveWindow = it
        }
        queryInt(link, "initConnReceiveWindow", "init_conn_receive_window", "init-conn-receive-window")?.also {
            hy2InitialConnectionReceiveWindow = it
        }
        queryInt(link, "maxConnReceiveWindow", "max_conn_receive_window", "max-conn-receive-window")?.also {
            hy2MaxConnectionReceiveWindow = it
        }
        querySeconds(link, "maxIdleTimeout", "max_idle_timeout", "max-idle-timeout")?.also {
            hy2MaxIdleTimeout = it
        }
        querySeconds(link, "keepAlivePeriod", "keep_alive_period", "keep-alive-period")?.also {
            hy2KeepAlivePeriod = it
        }
        queryBool(link, "disablePathMTUDiscovery", "disable_path_mtu_discovery", "disable-path-mtu-discovery")?.also {
            disableMtuDiscovery = it
        }
        querySeconds(link, "hopInterval", "hop_interval", "hop-interval")?.also {
            hopInterval = it
        }
        querySeconds(link, "minHopInterval", "min_hop_interval", "min-hop-interval")?.also {
            hy2MinHopInterval = it
        }
        querySeconds(link, "maxHopInterval", "max_hop_interval", "max-hop-interval")?.also {
            hy2MaxHopInterval = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}

private fun queryInt(link: okhttp3.HttpUrl, vararg names: String): Int? {
    return names.firstNotNullOfOrNull { link.queryParameter(it)?.toIntOrNull() }
}

private fun queryBool(link: okhttp3.HttpUrl, vararg names: String): Boolean? {
    return names.firstNotNullOfOrNull { name ->
        link.queryParameter(name)?.let { it == "1" || it.equals("true", ignoreCase = true) }
    }
}

private fun querySeconds(link: okhttp3.HttpUrl, vararg names: String): Int? {
    return names.firstNotNullOfOrNull { name ->
        parseDurationSeconds(link.queryParameter(name))
    }
}

private fun parseDurationSeconds(value: String?): Int? {
    val raw = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val (number, multiplier, roundUpDivisor) = when {
        raw.endsWith("ms") -> Triple(raw.removeSuffix("ms"), 1L, 1000L)
        raw.endsWith("s") -> Triple(raw.removeSuffix("s"), 1L, 1L)
        raw.endsWith("m") -> Triple(raw.removeSuffix("m"), 60L, 1L)
        raw.endsWith("h") -> Triple(raw.removeSuffix("h"), 3600L, 1L)
        else -> Triple(raw, 1L, 1L)
    }
    val base = number.toLongOrNull() ?: return null
    val seconds = if (roundUpDivisor > 1) {
        (base + roundUpDivisor - 1) / roundUpDivisor
    } else {
        base * multiplier
    }
    return seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
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
        if (hy2InitialStreamReceiveWindow > 0) {
            builder.addQueryParameter("initStreamReceiveWindow", hy2InitialStreamReceiveWindow.toString())
        }
        if (hy2MaxStreamReceiveWindow > 0) {
            builder.addQueryParameter("maxStreamReceiveWindow", hy2MaxStreamReceiveWindow.toString())
        }
        if (hy2InitialConnectionReceiveWindow > 0) {
            builder.addQueryParameter("initConnReceiveWindow", hy2InitialConnectionReceiveWindow.toString())
        }
        if (hy2MaxConnectionReceiveWindow > 0) {
            builder.addQueryParameter("maxConnReceiveWindow", hy2MaxConnectionReceiveWindow.toString())
        }
        if (hy2MaxIdleTimeout > 0) {
            builder.addQueryParameter("maxIdleTimeout", "${hy2MaxIdleTimeout}s")
        }
        if (hy2KeepAlivePeriod > 0) {
            builder.addQueryParameter("keepAlivePeriod", "${hy2KeepAlivePeriod}s")
        }
        if (disableMtuDiscovery) {
            builder.addQueryParameter("disablePathMTUDiscovery", "1")
        }
        if (hy2MinHopInterval > 0 || hy2MaxHopInterval > 0) {
            if (hy2MinHopInterval > 0) builder.addQueryParameter("minHopInterval", "${hy2MinHopInterval}s")
            if (hy2MaxHopInterval > 0) builder.addQueryParameter("maxHopInterval", "${hy2MaxHopInterval}s")
        } else if (hopInterval != 10) {
            builder.addQueryParameter("hopInterval", "${hopInterval}s")
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    // TODO parse HY2 JSON+YAML
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
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
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
    return portStr.substringBefore(",").substringBefore("-").substringBefore(":").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    if (protocolVersion == 2 && hasAdvancedHysteria2Options()) {
        // The bundled sing-box Hysteria2 option model supports Gecko obfs and fixed
        // port hopping, but not Hysteria's advanced QUIC knobs or random hop
        // intervals. Use the Hysteria2 sidecar when those fields are configured.
        return false
    }
    // Gecko obfs is now supported natively by this fork's sing-box core (via the
    // hawkff/sing-quic gecko backport), so default HY2 profiles do not need the sidecar.
    return true
}

fun HysteriaBean.hasAdvancedHysteria2Options(): Boolean {
    if (protocolVersion != 2) return false
    return hy2InitialStreamReceiveWindow > 0 ||
        hy2MaxStreamReceiveWindow > 0 ||
        hy2InitialConnectionReceiveWindow > 0 ||
        hy2MaxConnectionReceiveWindow > 0 ||
        hy2MaxIdleTimeout > 0 ||
        hy2KeepAlivePeriod > 0 ||
        disableMtuDiscovery ||
        (isMultiPort(displayAddress()) && (hy2MinHopInterval > 0 || hy2MaxHopInterval > 0))
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
                            // Clamp + order to match the sidecar builder's bounds
                            // (1..2048, min <= max); an inverted/out-of-range pair
                            // would otherwise be rejected by the core at connect time.
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

/**
 * Builds a config for the bundled official apernet/hysteria client binary (sidecar).
 *
 * NOTE: Default Hysteria2 (including Gecko obfs and fixed port hopping) runs natively in
 * the sing-box core. This sidecar path is used when HY2 advanced QUIC options or random
 * hop intervals are configured, because those fields are not exposed by the pinned core.
 *
 * Emitted as JSON (hysteria uses viper, which detects format by the .json extension).
 * The client's upstream QUIC sockets are protected from the VPN via
 * quic.sockopts.fdControlUnixSocket, pointing at libcore's protect_server socket
 * ("protect_path" in the process working dir). It exposes a loopback-only SOCKS5 listener
 * that the generated sing-box "socks" outbound dials (no auth, matching the other sidecars).
 *
 * @param port local SOCKS5 port for the sidecar to listen on (== sing-box outbound port).
 * @param protectPath absolute path to libcore's protect unix socket.
 */
fun HysteriaBean.buildHysteria2SidecarConfig(
    port: Int,
    protectPath: String,
): String {
    if (protocolVersion != 2) {
        throw Exception("error version: $protocolVersion")
    }
    var realSni = sni
    if (realSni.isBlank() && !serverAddress.isIpAddress()) {
        realSni = serverAddress
    }
    return JSONObject().apply {
        put("server", "$serverAddress:$serverPorts")
        if (authPayload.isNotBlank()) put("auth", authPayload)
        put("tls", JSONObject().apply {
            if (realSni.isNotBlank()) put("sni", realSni)
            put("insecure", allowInsecure || DataStore.globalAllowInsecure)
            if (caText.isNotBlank()) put("ca", caText)
        })
        if (hysteria2ObfsType == HysteriaBean.OBFS_GECKO && obfuscation.isNotBlank()) {
            // Clamp to hysteria's accepted bounds: 1 <= min <= max <= 2048.
            val min = geckoMinPacketSize.coerceIn(1, 2048)
            val max = geckoMaxPacketSize.coerceIn(min, 2048)
            put("obfs", JSONObject().apply {
                put("type", "gecko")
                put("gecko", JSONObject().apply {
                    put("password", obfuscation)
                    put("minPacketSize", min)
                    put("maxPacketSize", max)
                })
            })
        } else if (hysteria2ObfsType == HysteriaBean.OBFS_SALAMANDER && obfuscation.isNotBlank()) {
            put("obfs", JSONObject().apply {
                put("type", "salamander")
                put("salamander", JSONObject().apply {
                    put("password", obfuscation)
                })
            })
        }
        if (uploadMbps > 0 || downloadMbps > 0) {
            put("bandwidth", JSONObject().apply {
                if (uploadMbps > 0) put("up", "$uploadMbps mbps")
                if (downloadMbps > 0) put("down", "$downloadMbps mbps")
            })
        }
        if (isMultiPort(displayAddress())) {
            put("transport", JSONObject().apply {
                put("type", "udp")
                put("udp", JSONObject().apply {
                    if (hy2MinHopInterval > 0 || hy2MaxHopInterval > 0) {
                        val rawMin = hy2MinHopInterval.takeIf { it > 0 } ?: hy2MaxHopInterval
                        val min = rawMin.coerceAtLeast(5)
                        val max = (hy2MaxHopInterval.takeIf { it > 0 } ?: min).coerceAtLeast(min)
                        put("minHopInterval", "${min}s")
                        put("maxHopInterval", "${max}s")
                    } else if (hopInterval > 0) {
                        put("hopInterval", "${hopInterval.coerceAtLeast(5)}s")
                    }
                })
            })
        }
        put("quic", JSONObject().apply {
            if (hy2InitialStreamReceiveWindow > 0) put("initStreamReceiveWindow", hy2InitialStreamReceiveWindow)
            if (hy2MaxStreamReceiveWindow > 0) put("maxStreamReceiveWindow", hy2MaxStreamReceiveWindow)
            if (hy2InitialConnectionReceiveWindow > 0) put("initConnReceiveWindow", hy2InitialConnectionReceiveWindow)
            if (hy2MaxConnectionReceiveWindow > 0) put("maxConnReceiveWindow", hy2MaxConnectionReceiveWindow)
            if (hy2MaxIdleTimeout > 0) put("maxIdleTimeout", "${hy2MaxIdleTimeout}s")
            if (hy2KeepAlivePeriod > 0) put("keepAlivePeriod", "${hy2KeepAlivePeriod}s")
            if (disableMtuDiscovery) put("disablePathMTUDiscovery", true)
            put("sockopts", JSONObject().apply {
                put("fdControlUnixSocket", protectPath)
            })
        })
        put("socks5", JSONObject().apply {
            put("listen", "$LOCALHOST:$port")
            put("disableUDP", false)
        })
        put("lazy", true)
    }.toStringPretty()
}
