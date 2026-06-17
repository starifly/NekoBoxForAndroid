/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.masterdnsvpn

import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config for the bundled MasterDnsVPN client (sidecar).
 *
 * The client is launched as `libmasterdnsvpn.so -json <cfg.json> -resolvers <file>`.
 * Resolvers are written to a separate file (the client always loads resolvers from a
 * file); see [resolverLines]. The config uses the client's uppercase config keys.
 *
 * Upstream resolver sockets are protected from the VPN via FD_CONTROL_UNIX_SOCKET, which
 * points at libcore's protect_path server (relative socket in the process working dir).
 *
 * @param port local SOCKS5 port for the sidecar to listen on (== sing-box outbound port).
 * @param protectPath absolute path to libcore's protect unix socket.
 */
fun MasterDnsVpnBean.buildMasterDnsVpnConfig(port: Int, protectPath: String): String {
    // A tunnel with no domains can never connect; fail fast rather than emitting a config
    // the sidecar will reject at runtime.
    val domainList = domains.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
    if (domainList.isEmpty()) {
        error("MasterDnsVPN: at least one tunnel domain is required")
    }
    // Encryption (method != 0 == not None) requires a key that matches the server; emitting
    // a blank ENCRYPTION_KEY would let the sidecar fail opaquely at runtime. Fail fast here.
    if (dataEncryptionMethod != 0 && encryptionKey.isBlank()) {
        error("MasterDnsVPN: encryption method $dataEncryptionMethod requires a non-empty key")
    }
    val cfg = JSONObject().apply {
        put("PROTOCOL_TYPE", "SOCKS5")
        put("LISTEN_IP", LOCALHOST)
        put("LISTEN_PORT", port)
        // Loopback-only listener, no SOCKS auth (matches the other sidecars).
        put("SOCKS5_AUTH", false)

        put("DOMAINS", JSONArray().apply {
            domainList.forEach { put(it) }
        })
        put("DATA_ENCRYPTION_METHOD", dataEncryptionMethod)
        put("ENCRYPTION_KEY", encryptionKey)

        put("RESOLVER_BALANCING_STRATEGY", resolverBalancingStrategy)
        put("PACKET_DUPLICATION_COUNT", packetDuplicationCount)
        put("SETUP_PACKET_DUPLICATION_COUNT", setupPacketDuplicationCount)
        put("AUTO_DISABLE_TIMEOUT_SERVERS", autoDisableTimeoutServers)
        put("AUTO_REMOVE_LOW_MTU_SERVERS", autoRemoveLowMtuServers)
        put("BASE_ENCODE_DATA", baseEncodeData)

        put("UPLOAD_COMPRESSION_TYPE", uploadCompressionType)
        put("DOWNLOAD_COMPRESSION_TYPE", downloadCompressionType)
        put("COMPRESSION_MIN_SIZE", compressionMinSize)

        put("MIN_UPLOAD_MTU", minUploadMtu)
        put("MIN_DOWNLOAD_MTU", minDownloadMtu)
        put("MAX_UPLOAD_MTU", maxUploadMtu)
        put("MAX_DOWNLOAD_MTU", maxDownloadMtu)

        put("LOCAL_DNS_ENABLED", localDnsEnabled)
        if (localDnsEnabled) {
            put("LOCAL_DNS_IP", LOCALHOST)
            put("LOCAL_DNS_PORT", localDnsPort)
        }

        put("LOG_LEVEL", logLevel)

        // Android VPN socket protection.
        put("FD_CONTROL_UNIX_SOCKET", protectPath)
    }

    // Merge the advanced JSON override last so it can set/replace tuning keys, but never
    // safety-critical ones: the protect socket and the loopback SOCKS listener must stay
    // as generated, or VPN-mode socket protection / routing would silently break.
    if (advancedJson.isNotBlank()) {
        // Malformed override JSON must not crash config generation / VPN start.
        try {
            val protectedKeys = setOf(
                "FD_CONTROL_UNIX_SOCKET", "LISTEN_IP", "LISTEN_PORT",
                "PROTOCOL_TYPE", "SOCKS5_AUTH",
            )
            val extra = JSONObject(advancedJson)
            for (key in extra.keys()) {
                if (key in protectedKeys) {
                    Logs.w("MasterDnsVPN: ignoring protected key '$key' in advanced JSON override")
                    continue
                }
                cfg.put(key, extra.get(key))
            }
        } catch (e: Exception) {
            Logs.w("MasterDnsVPN: ignoring invalid advanced JSON override: ${e.message}")
        }
    }

    return cfg.toStringPretty()
}

/** Resolver entries to write into the client's resolvers file (one per line). */
fun MasterDnsVpnBean.resolverLines(): String {
    return resolvers.split("\n", ",").map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

/**
 * Serializes a MasterDnsVPN profile to a shareable `masterdns://` link.
 *
 * Shape: `masterdns://<encryptionKey>@<firstDomain>?<params>#<name>`. The encryption key
 * is carried as URL userinfo; the first tunnel domain is used as a human-readable host
 * (the protocol has no real server host). Comma-joined lists are used for the multi-value
 * `domains` and `resolvers` fields. Only non-default tuning values are emitted to keep the
 * link short; [parseMasterDnsVpn] re-applies defaults via [MasterDnsVpnBean.initializeDefaultValues].
 */
fun MasterDnsVpnBean.toUri(): String {
    val domainList = domains.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
    if (domainList.isEmpty()) {
        error("MasterDnsVPN: cannot build share link without a tunnel domain")
    }
    val host = domainList.first()

    val builder = linkBuilder()
        .host(host)
        .username(encryptionKey)

    builder.addQueryParameter("domains", domainList.joinToString(","))
    builder.addQueryParameter("enc", dataEncryptionMethod.toString())

    val resolverList = resolvers.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
    if (resolverList.isNotEmpty()) {
        builder.addQueryParameter("resolvers", resolverList.joinToString(","))
    }

    val defaults = MasterDnsVpnBean().apply { initializeDefaultValues() }
    if (resolverBalancingStrategy != defaults.resolverBalancingStrategy) {
        builder.addQueryParameter("balance", resolverBalancingStrategy.toString())
    }
    if (packetDuplicationCount != defaults.packetDuplicationCount) {
        builder.addQueryParameter("dup", packetDuplicationCount.toString())
    }
    if (setupPacketDuplicationCount != defaults.setupPacketDuplicationCount) {
        builder.addQueryParameter("setupDup", setupPacketDuplicationCount.toString())
    }
    if (autoDisableTimeoutServers != defaults.autoDisableTimeoutServers) {
        builder.addQueryParameter("autoDisableTimeout", if (autoDisableTimeoutServers) "1" else "0")
    }
    if (autoRemoveLowMtuServers != defaults.autoRemoveLowMtuServers) {
        builder.addQueryParameter("autoRemoveLowMtu", if (autoRemoveLowMtuServers) "1" else "0")
    }
    if (baseEncodeData != defaults.baseEncodeData) {
        builder.addQueryParameter("baseEncode", if (baseEncodeData) "1" else "0")
    }
    if (uploadCompressionType != defaults.uploadCompressionType) {
        builder.addQueryParameter("upComp", uploadCompressionType.toString())
    }
    if (downloadCompressionType != defaults.downloadCompressionType) {
        builder.addQueryParameter("downComp", downloadCompressionType.toString())
    }
    if (compressionMinSize != defaults.compressionMinSize) {
        builder.addQueryParameter("compMinSize", compressionMinSize.toString())
    }
    if (minUploadMtu != defaults.minUploadMtu) {
        builder.addQueryParameter("minUpMtu", minUploadMtu.toString())
    }
    if (minDownloadMtu != defaults.minDownloadMtu) {
        builder.addQueryParameter("minDownMtu", minDownloadMtu.toString())
    }
    if (maxUploadMtu != defaults.maxUploadMtu) {
        builder.addQueryParameter("maxUpMtu", maxUploadMtu.toString())
    }
    if (maxDownloadMtu != defaults.maxDownloadMtu) {
        builder.addQueryParameter("maxDownMtu", maxDownloadMtu.toString())
    }
    if (localDnsEnabled != defaults.localDnsEnabled) {
        builder.addQueryParameter("localDns", if (localDnsEnabled) "1" else "0")
    }
    if (localDnsPort != defaults.localDnsPort) {
        builder.addQueryParameter("localDnsPort", localDnsPort.toString())
    }
    if (logLevel != defaults.logLevel) {
        builder.addQueryParameter("log", logLevel)
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("masterdns", appendDefaultPort = false)
}

/**
 * Parses a `masterdns://` share link into a [MasterDnsVpnBean]. Tolerant of missing optional
 * params (defaults are filled by [MasterDnsVpnBean.initializeDefaultValues]).
 */
fun parseMasterDnsVpn(url: String): MasterDnsVpnBean {
    val link = url.replace("masterdns://", "https://").toHttpUrlOrNull()
        ?: error("invalid masterdns link $url")
    return MasterDnsVpnBean().apply {
        // serverAddress/Port are unused by this protocol; keep the placeholder.
        serverAddress = "masterdnsvpn"

        encryptionKey = link.username

        link.queryParameter("domains")?.also {
            domains = it.split(",").map { d -> d.trim() }.filter { d -> d.isNotEmpty() }
                .joinToString("\n")
        }
        if (domains.isNullOrEmpty() && link.host.isNotBlank() && link.host != "masterdnsvpn") {
            // Fall back to the host segment as the single tunnel domain.
            domains = link.host
        }
        link.queryParameter("enc")?.toIntOrNull()?.also { dataEncryptionMethod = it }
        link.queryParameter("resolvers")?.also {
            resolvers = it.split(",").map { r -> r.trim() }.filter { r -> r.isNotEmpty() }
                .joinToString("\n")
        }

        link.queryParameter("balance")?.toIntOrNull()?.also { resolverBalancingStrategy = it }
        link.queryParameter("dup")?.toIntOrNull()?.also { packetDuplicationCount = it }
        link.queryParameter("setupDup")?.toIntOrNull()?.also { setupPacketDuplicationCount = it }
        link.queryParameter("autoDisableTimeout")?.also {
            autoDisableTimeoutServers = it == "1" || it == "true"
        }
        link.queryParameter("autoRemoveLowMtu")?.also {
            autoRemoveLowMtuServers = it == "1" || it == "true"
        }
        link.queryParameter("baseEncode")?.also { baseEncodeData = it == "1" || it == "true" }
        link.queryParameter("upComp")?.toIntOrNull()?.also { uploadCompressionType = it }
        link.queryParameter("downComp")?.toIntOrNull()?.also { downloadCompressionType = it }
        link.queryParameter("compMinSize")?.toIntOrNull()?.also { compressionMinSize = it }
        link.queryParameter("minUpMtu")?.toIntOrNull()?.also { minUploadMtu = it }
        link.queryParameter("minDownMtu")?.toIntOrNull()?.also { minDownloadMtu = it }
        link.queryParameter("maxUpMtu")?.toIntOrNull()?.also { maxUploadMtu = it }
        link.queryParameter("maxDownMtu")?.toIntOrNull()?.also { maxDownloadMtu = it }
        link.queryParameter("localDns")?.also { localDnsEnabled = it == "1" || it == "true" }
        link.queryParameter("localDnsPort")?.toIntOrNull()?.also { localDnsPort = it }
        link.queryParameter("log")?.also { logLevel = it }

        name = link.fragment ?: ""

        // Ensure every field is non-null before any consumer (toUri / config builder /
        // resolverLines) touches it; the import pipeline also calls this, but a freshly
        // parsed bean may be read before that happens.
        initializeDefaultValues()

        // A profile with no tunnel domain can never connect; fail the import early with a
        // clear message instead of silently creating a broken entry.
        if (domains.isBlank()) {
            error("masterdns link missing tunnel domain(s)")
        }

        // When encryption is enabled the key is mandatory (and must match the server).
        // dataEncryptionMethod 0 == None, where an empty key is intentionally allowed.
        if (dataEncryptionMethod != 0 && encryptionKey.isBlank()) {
            error("masterdns link missing encryption key for encryption method $dataEncryptionMethod")
        }
    }
}
