package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.unUrlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// URI 格式: snell://base64(psk)@server:port?version=6&userkey=base64(userkey)&mode=default&reuse=true&network=tcp#name
fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
        ?: error("Invalid snell URL")

    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        psk = link.username.unUrlSafe()
        name = link.fragment ?: ""

        link.queryParameter("version")?.toIntOrNull()?.let {
            version = it.coerceIn(1, 6)
        }
        link.queryParameter("userkey")?.let { userKey = it.unUrlSafe() }
        link.queryParameter("obfs-mode")?.let { obfsMode = it }
        link.queryParameter("obfs-host")?.let { obfsHost = it }
        link.queryParameter("reuse")?.let { reuse = it.toBoolean() }
        link.queryParameter("network")?.let { network = it }
        link.queryParameter("mode")?.let { mode = it }
    }
}

fun SnellBean.toUri(): String {
    val builder = StringBuilder("snell://")
    builder.append(psk.urlSafe()).append("@")
    builder.append(serverAddress).append(":").append(serverPort)

    val params = mutableListOf<String>()
    params.add("version=$version")
    if (userKey.isNotBlank()) params.add("userkey=${userKey.urlSafe()}")
    if (version == 6) {
        if (mode.isNotBlank() && mode != "default") params.add("mode=$mode")
    } else {
        if (obfsMode.isNotBlank()) params.add("obfs-mode=$obfsMode")
        if (obfsHost.isNotBlank()) params.add("obfs-host=$obfsHost")
    }
    if (reuse) params.add("reuse=true")
    if (network.isNotBlank()) params.add("network=$network")

    builder.append("?").append(params.joinToString("&"))

    if (name.isNotBlank()) {
        builder.append("#").append(name.urlSafe())
    }

    return builder.toString()
}

fun parseClashSnell(proxy: Map<String, Any?>): SnellBean {
    return SnellBean().apply {
        name = proxy["name"] as? String ?: ""
        serverAddress = proxy["server"] as? String ?: ""
        serverPort = (proxy["port"] as? Number)?.toInt() ?: 443
        psk = proxy["psk"] as? String ?: ""

        val clashVersion = ((proxy["version"] as? Number)?.toInt() ?: 4).coerceIn(1, 5)
        version = if (clashVersion == 5) 4 else clashVersion

        reuse = proxy["reuse"] as? Boolean ?: false

        val udpEnabled = proxy["udp"] as? Boolean ?: false
        network = if (udpEnabled) {
            ""
        } else {
            "tcp"
        }

        // obfs-opts
        (proxy["obfs-opts"] as? Map<*, *>)?.let { obfsOpts ->
            obfsMode = obfsOpts["mode"] as? String ?: ""
            obfsHost = obfsOpts["host"] as? String ?: ""
        }
    }
}
