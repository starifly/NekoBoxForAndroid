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
import io.nekohasekai.sagernet.ktx.toStringPretty
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config for the bundled MasterDnsVPN client (sidecar).
 *
 * The client is launched as `libmasterdnsvpn.so -json-base64 <b64> -resolvers <file>`.
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
    val cfg = JSONObject().apply {
        put("PROTOCOL_TYPE", "SOCKS5")
        put("LISTEN_IP", LOCALHOST)
        put("LISTEN_PORT", port)
        // Loopback-only listener, no SOCKS auth (matches the other sidecars).
        put("SOCKS5_AUTH", false)

        put("DOMAINS", JSONArray().apply {
            domains.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { put(it) }
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

    // Merge the advanced JSON override last so it can set/replace any key.
    if (advancedJson.isNotBlank()) {
        val extra = JSONObject(advancedJson)
        for (key in extra.keys()) {
            cfg.put(key, extra.get(key))
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
