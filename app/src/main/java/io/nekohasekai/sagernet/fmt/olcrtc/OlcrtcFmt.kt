/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.olcrtc

/**
 * Parser/emitter for the `olcrtc://` client URI.
 *
 * Shape (per the upstream client convention):
 *
 *     olcrtc://<carrier>?<transport>@<roomId>#<keyHex>$<comment>
 *     olcrtc://<carrier>?<transport><k=v&k=v>@<roomId>#<keyHex>$<comment>
 *
 * Delimiters: `://` after the scheme, `?` before transport, optional `<...>`
 * transport payload, `@` before roomId, `#` before keyHex, `$` before a free
 * comment (used only as the profile name). roomId may itself be a full URL
 * (jitsi uses `https://host/room`), so `@`/`#`/`$` are located by scanning.
 *
 * clientId is NOT part of the upstream URI. We carry it as a non-standard
 * `&cid=` entry inside the transport payload block so an exported profile
 * round-trips and works on re-import; a plain upstream link parses fine and
 * leaves clientId blank for the user to fill in.
 */

private const val SCHEME = "olcrtc://"
private val SUPPORTED_TRANSPORTS = setOf("vp8channel", "datachannel")
private val SUPPORTED_CARRIERS = setOf("jitsi", "telemost", "wbstream")
private val DELIMITERS = setOf('<', '>', '&', '=', '@', '#', '$', '?')

/** Parses an `olcrtc://` link into an [OlcrtcBean]. Fails fast on malformed input. */
fun parseOlcrtc(url: String): OlcrtcBean {
    val raw = url.trim()
    require(raw.startsWith(SCHEME)) { "invalid olcrtc link: missing $SCHEME scheme" }
    var body = raw.substring(SCHEME.length)

    // Trailing comment after the LAST '$' (room URLs do not contain '$').
    var comment = ""
    val dollar = body.lastIndexOf('$')
    if (dollar >= 0) {
        comment = body.substring(dollar + 1)
        body = body.substring(0, dollar)
    }

    // keyHex after the LAST '#'.
    var keyHex = ""
    val hash = body.lastIndexOf('#')
    if (hash >= 0) {
        keyHex = body.substring(hash + 1).trim()
        body = body.substring(0, hash)
    }

    // carrier before the FIRST '?'.
    val q = body.indexOf('?')
    require(q >= 0) { "invalid olcrtc link: missing '?' before transport" }
    val carrier = body.substring(0, q).trim()
    require(carrier.isNotEmpty()) { "invalid olcrtc link: empty carrier" }
    require(carrier in SUPPORTED_CARRIERS) {
        "olcrtc link unsupported carrier '$carrier' (supported: ${SUPPORTED_CARRIERS.joinToString()})"
    }
    var afterQ = body.substring(q + 1)

    // roomId after the FIRST '@' that is NOT inside the `<...>` payload block.
    val payloadEnd = if (afterQ.startsWith("<") || afterQ.contains('<')) afterQ.indexOf('>') else -1
    val atSearchFrom = if (payloadEnd >= 0) payloadEnd else 0
    val at = afterQ.indexOf('@', atSearchFrom)
    require(at >= 0) { "invalid olcrtc link: missing '@' before room id" }
    val roomId = afterQ.substring(at + 1).trim()
    require(roomId.isNotEmpty()) { "invalid olcrtc link: empty room id" }
    var transportPart = afterQ.substring(0, at)

    // Optional `<k=v&...>` payload after the transport name.
    var payload = ""
    val lt = transportPart.indexOf('<')
    if (lt >= 0) {
        val gt = transportPart.indexOf('>', lt)
        require(gt >= 0) { "invalid olcrtc link: unterminated '<...>' transport payload" }
        payload = transportPart.substring(lt + 1, gt)
        transportPart = transportPart.substring(0, lt)
    }
    val transport = transportPart.trim().ifEmpty { "vp8channel" }

    return OlcrtcBean().apply {
        // serverAddress/Port are unused by this protocol; keep a stable placeholder.
        serverAddress = "olcrtc"
        this.carrier = carrier
        this.roomId = roomId
        this.keyHex = keyHex
        require(transport in SUPPORTED_TRANSPORTS) {
            "olcrtc link unsupported transport '$transport' (supported: ${SUPPORTED_TRANSPORTS.joinToString()})"
        }
        this.transport = transport
        name = comment

        parsePayload(payload).forEach { (k, v) ->
            when (k) {
                "vp8-fps" -> v.toIntOrNull()?.let { vp8Fps = it }
                "vp8-batch" -> v.toIntOrNull()?.let { vp8BatchSize = it }
                // Our non-standard pairing-token carrier.
                "cid", "client-id", "clientid" -> clientId = v
            }
        }

        initializeDefaultValues()

        require(keyHex.isNotBlank()) { "olcrtc link missing encryption key" }
        require(keyHex.length == 64 && keyHex.all { it.isHexDigit() }) {
            "olcrtc link encryption key must be 64 hex characters"
        }
    }
}

/** Serializes an [OlcrtcBean] to a shareable `olcrtc://` link, carrying clientId as `&cid=`. */
fun OlcrtcBean.toUri(): String {
    require(carrier.isNotBlank()) { "olcRTC: cannot build share link without a carrier" }
    require(roomId.isNotBlank()) { "olcRTC: cannot build share link without a room id" }
    // The URI uses bare delimiters with no escaping convention; refuse to emit a link that
    // would not round-trip rather than silently producing a corrupt one.
    require(clientId.none { it in DELIMITERS }) {
        "olcRTC: clientId must not contain any of: ${DELIMITERS.joinToString(" ")}"
    }
    // roomId is emitted raw before '#'; a '$' in it would be mis-parsed as the comment
    // delimiter on re-import. Refuse rather than emit a link that won't round-trip.
    require(roomId.none { it == '$' }) { "olcRTC: room id must not contain '\$'" }
    require(name.none { it == '$' }) { "olcRTC: profile name must not contain '\$'" }

    val payloadParts = mutableListOf<String>()
    if (transport == "vp8channel") {
        val defaults = OlcrtcBean().apply { initializeDefaultValues() }
        if (vp8Fps != defaults.vp8Fps) payloadParts += "vp8-fps=$vp8Fps"
        if (vp8BatchSize != defaults.vp8BatchSize) payloadParts += "vp8-batch=$vp8BatchSize"
    }
    if (clientId.isNotBlank()) payloadParts += "cid=$clientId"

    val payload = if (payloadParts.isEmpty()) "" else "<${payloadParts.joinToString("&")}>"

    val sb = StringBuilder(SCHEME)
    sb.append(carrier).append('?').append(transport).append(payload)
    sb.append('@').append(roomId)
    sb.append('#').append(keyHex)
    if (name.isNotBlank()) sb.append('$').append(name)
    return sb.toString()
}

/**
 * Builds the CLI args for the bundled olcRTC client sidecar (libolcrtc.so).
 *
 * @param port local SOCKS5 port the sidecar listens on (== sing-box outbound port).
 * @param protectPath absolute path to libcore's protect unix socket (fd protection).
 * @param socksUser/socksPass optional loopback SOCKS5 credentials.
 * @param verbose enable the sidecar's verbose logging.
 * @param dnsFallback resolver used when the profile leaves DNS blank (never Google).
 */
fun OlcrtcBean.buildOlcrtcArgs(
    port: Int,
    protectPath: String,
    socksUser: String,
    socksPass: String,
    verbose: Boolean,
    dnsFallback: String,
    readyTimeoutMs: Long,
): List<String> {
    require(!carrier.isNullOrBlank()) { "olcRTC: carrier is required" }
    require(!roomId.isNullOrBlank()) { "olcRTC: room id is required" }
    val hex = keyHex ?: ""
    require(hex.length == 64 && hex.all { it.isHexDigit() }) {
        "olcRTC: encryption key must be 64 hex characters"
    }
    val transportName = if (transport in SUPPORTED_TRANSPORTS) transport else "vp8channel"
    val args = mutableListOf(
        "-carrier", carrier,
        "-transport", transportName,
        "-room", roomId,
        "-client-id", clientId ?: "",
        "-key", hex,
        "-socks-port", port.toString(),
        "-dns", (dnsServer ?: "").ifBlank { dnsFallback },
        "-vp8-fps", vp8Fps.toString(),
        "-vp8-batch", vp8BatchSize.toString(),
        "-protect-path", protectPath,
        "-ready-timeout-ms", readyTimeoutMs.toString(),
    )
    if (socksUser.isNotEmpty()) args += listOf("-socks-user", socksUser)
    if (socksPass.isNotEmpty()) args += listOf("-socks-pass", socksPass)
    if (verbose) args += "-debug"
    return args
}

/**
 * The carrier signaling host olcRTC contacts for this profile, used to force-direct DNS
 * (real IP, bypassing fakeip) so the in-process sidecar's protected socket reaches it
 * instead of looping back through the tun. Returns null when no fixed host applies.
 *
 * Note: this covers the carrier SIGNALING host only. WebRTC media (ICE/STUN/TURN/SFU)
 * may resolve further hosts at runtime; those rely on the sidecar's own protected
 * resolver. ICE candidates are typically raw IPs, so signaling is the common blocker.
 */
fun OlcrtcBean.carrierHost(): String? = when (carrier) {
    "jitsi" -> {
        // roomId is host/room or https://host/room; extract the host.
        val s = roomId.substringAfter("://").trimStart('/')
        s.substringBefore('/').substringBefore(':').ifBlank { null }
    }
    "telemost" -> "telemost.yandex.ru"
    "wbstream" -> "stream.wb.ru"
    else -> null
}

private fun parsePayload(payload: String): Map<String, String> {
    if (payload.isBlank()) return emptyMap()
    return payload.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) null else pair.substring(0, i).trim() to pair.substring(i + 1).trim()
    }.toMap()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
