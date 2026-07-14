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
private const val TRANSPORT_VP8 = "vp8channel"
private const val TRANSPORT_DATA = "datachannel"
private const val DEFAULT_VP8_FPS = 30
private const val DEFAULT_VP8_BATCH = 8
private val SUPPORTED_TRANSPORTS_BY_CARRIER = mapOf(
    "jitsi" to setOf(TRANSPORT_VP8, TRANSPORT_DATA),
    "telemost" to setOf(TRANSPORT_VP8),
    "wbstream" to setOf(TRANSPORT_VP8),
)
private val DELIMITERS = setOf('<', '>', '&', '=', '@', '#', '$', '?')
private val VP8_FPS_RANGE = 1..120
private val VP8_BATCH_RANGE = 1..64

/**
 * Validates fields shared by URI import/export, the profile editor, and runtime args.
 * Plain upstream URIs do not carry a client id, so callers opt into that requirement.
 */
fun OlcrtcBean.validateOlcrtcProfile(requireClientId: Boolean = false) {
    val carrierName = carrier.orEmpty()
    val transportName = transport.orEmpty()
    val room = roomId.orEmpty()
    val identity = clientId.orEmpty()
    val hex = keyHex.orEmpty()
    val fps = vp8Fps ?: 0
    val batchSize = vp8BatchSize ?: 0
    val resolver = dnsServer.orEmpty()

    val allowedTransports = SUPPORTED_TRANSPORTS_BY_CARRIER[carrierName]
    require(allowedTransports != null) { "olcRTC: unsupported carrier" }
    require(transportName in allowedTransports) { "olcRTC: transport is not supported by carrier" }
    require(room.isNotBlank()) { "olcRTC: room id / URL is required" }
    if (requireClientId) {
        require(identity.isNotBlank()) { "olcRTC: client id is required" }
    }
    require(hex.length == 64 && hex.all { it.isHexDigit() }) {
        "olcRTC: encryption key must be 64 hex characters"
    }
    if (transportName == TRANSPORT_VP8) {
        require(fps in VP8_FPS_RANGE) { "olcRTC: VP8 FPS must be between 1 and 120" }
        require(batchSize in VP8_BATCH_RANGE) { "olcRTC: VP8 batch size must be between 1 and 64" }
    }
    require(resolver.isBlank() || resolver.isIpPortLiteral()) {
        "olcRTC: DNS resolver must be an IP literal with a valid port"
    }
}

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
    val afterQ = body.substring(q + 1)

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
        require(transportPart.substring(gt + 1).isBlank()) {
            "invalid olcrtc link: unexpected text after transport payload"
        }
        payload = transportPart.substring(lt + 1, gt)
        transportPart = transportPart.substring(0, lt)
    }
    val transport = transportPart.trim().ifEmpty { TRANSPORT_VP8 }
    val payloadValues = parsePayload(payload)

    return OlcrtcBean().apply {
        // serverAddress/Port are unused by this protocol; keep a stable placeholder.
        serverAddress = "olcrtc"
        this.carrier = carrier
        this.roomId = roomId
        this.keyHex = keyHex
        this.transport = transport
        name = comment
        initializeDefaultValues()

        payloadValues.forEach { (key, value) ->
            when (key) {
                "vp8-fps" -> vp8Fps = value.toIntOrNull()
                    ?: throw IllegalArgumentException("olcRTC: VP8 FPS must be an integer")

                "vp8-batch" -> vp8BatchSize = value.toIntOrNull()
                    ?: throw IllegalArgumentException("olcRTC: VP8 batch size must be an integer")

                // Our non-standard pairing-token carrier.
                "cid", "client-id", "clientid" -> {
                    require(value.none { it in DELIMITERS }) {
                        "olcRTC: client id contains a reserved delimiter"
                    }
                    clientId = value
                }
            }
        }

        validateOlcrtcProfile()
    }
}

/** Serializes an [OlcrtcBean] to a shareable `olcrtc://` link, carrying clientId as `&cid=`. */
fun OlcrtcBean.toUri(): String {
    validateOlcrtcProfile()
    val carrierName = carrier.orEmpty()
    val transportName = transport.orEmpty()
    val room = roomId.orEmpty()
    val shareClientId = clientId.orEmpty()
    val hex = keyHex.orEmpty()
    val fps = vp8Fps ?: DEFAULT_VP8_FPS
    val batchSize = vp8BatchSize ?: DEFAULT_VP8_BATCH
    val profileName = name.orEmpty()

    // The URI uses bare delimiters with no escaping convention; refuse to emit a link that
    // would not round-trip rather than silently producing a corrupt one.
    require(shareClientId.none { it in DELIMITERS }) {
        "olcRTC: client id contains a reserved delimiter"
    }
    // roomId is emitted raw before '#'. A '$' would be mis-parsed as the comment delimiter,
    // while '<' would be mistaken for the transport payload opener on re-import.
    require(room.none { it == '$' || it == '<' }) {
        "olcRTC: room id contains a reserved delimiter"
    }
    require(profileName.none { it == '$' }) { "olcRTC: profile name must not contain '\$'" }

    val payloadParts = mutableListOf<String>()
    if (transportName == TRANSPORT_VP8) {
        val defaults = OlcrtcBean().apply { initializeDefaultValues() }
        if (fps != defaults.vp8Fps) payloadParts += "vp8-fps=$fps"
        if (batchSize != defaults.vp8BatchSize) payloadParts += "vp8-batch=$batchSize"
    }
    if (shareClientId.isNotBlank()) payloadParts += "cid=$shareClientId"

    val payload = if (payloadParts.isEmpty()) "" else "<${payloadParts.joinToString("&")}>"

    val sb = StringBuilder(SCHEME)
    sb.append(carrierName).append('?').append(transportName).append(payload)
    sb.append('@').append(room)
    sb.append('#').append(hex)
    if (profileName.isNotBlank()) sb.append('$').append(profileName)
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
    validateOlcrtcProfile(requireClientId = true)
    val carrierName = carrier.orEmpty()
    val transportName = transport.orEmpty()
    val room = roomId.orEmpty()
    val identity = clientId.orEmpty()
    val hex = keyHex.orEmpty()
    val fps = if (transportName == TRANSPORT_VP8) {
        vp8Fps ?: DEFAULT_VP8_FPS
    } else {
        DEFAULT_VP8_FPS
    }
    val batchSize = if (transportName == TRANSPORT_VP8) {
        vp8BatchSize ?: DEFAULT_VP8_BATCH
    } else {
        DEFAULT_VP8_BATCH
    }
    val resolver = dnsServer.orEmpty().ifBlank { dnsFallback }
    require(resolver.isIpPortLiteral()) {
        "olcRTC: DNS resolver must be an IP literal with a valid port"
    }
    val args = mutableListOf(
        "-carrier", carrierName,
        "-transport", transportName,
        "-room", room,
        "-client-id", identity,
        "-key", hex,
        "-socks-port", port.toString(),
        "-dns", resolver,
        "-vp8-fps", fps.toString(),
        "-vp8-batch", batchSize.toString(),
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
fun OlcrtcBean.carrierHost(): String? = when (carrier.orEmpty()) {
    "jitsi" -> {
        // Mirror upstream's permissive host/room split, but keep bracketed IPv6 intact.
        val room = roomId.orEmpty().trim()
        val authority = room.substringAfter("://", room).trimStart('/').substringBefore('/').trim()
        when {
            authority.isBlank() -> null
            authority.startsWith('[') -> {
                val closingBracket = authority.indexOf(']')
                if (closingBracket <= 1) null else authority.substring(1, closingBracket)
            }
            authority.count { it == ':' } == 1 -> authority.substringBefore(':').ifBlank { null }
            else -> authority
        }
    }
    "telemost" -> "telemost.yandex.ru"
    "wbstream" -> "stream.wb.ru"
    else -> null
}

private fun parsePayload(payload: String): Map<String, String> {
    if (payload.isBlank()) return emptyMap()
    return payload.split('&').associate { pair ->
        val separator = pair.indexOf('=')
        require(separator > 0) { "invalid olcrtc transport payload" }
        val key = pair.substring(0, separator).trim()
        require(key.isNotEmpty()) { "invalid olcrtc transport payload" }
        key to pair.substring(separator + 1).trim()
    }
}

private fun String.isIpPortLiteral(): Boolean {
    val (host, port) = if (startsWith('[')) {
        val closingBracket = indexOf(']')
        if (closingBracket <= 1 || closingBracket != lastIndexOf(']')) return false
        if (closingBracket + 1 >= length || this[closingBracket + 1] != ':') return false
        substring(1, closingBracket) to substring(closingBracket + 2)
    } else {
        val separator = indexOf(':')
        if (separator <= 0 || separator != lastIndexOf(':')) return false
        substring(0, separator) to substring(separator + 1)
    }
    if (!port.isValidPort()) return false
    return if (startsWith('[')) host.isIpv6Literal() else host.isIpv4Literal()
}

private fun String.isValidPort(): Boolean {
    if (isEmpty() || any { it !in '0'..'9' }) return false
    val value = toIntOrNull() ?: return false
    return value in 1..65535
}

private fun String.isIpv4Literal(): Boolean {
    val octets = split('.')
    return octets.size == 4 && octets.all { octet ->
        if (octet.isEmpty() || octet.any { it !in '0'..'9' }) return@all false
        if (octet.length > 1 && octet.startsWith('0')) return@all false
        val value = octet.toIntOrNull() ?: return@all false
        value in 0..255
    }
}

private fun String.isIpv6Literal(): Boolean {
    val zoneSeparator = indexOf('%')
    val address: String
    if (zoneSeparator >= 0) {
        if (zoneSeparator == 0 || zoneSeparator != lastIndexOf('%')) return false
        val zone = substring(zoneSeparator + 1)
        if (zone.isEmpty() || zone.any { !it.isSafeZoneCharacter() }) return false
        address = substring(0, zoneSeparator)
    } else {
        address = this
    }
    if (':' !in address) return false

    val compression = address.indexOf("::")
    if (compression != address.lastIndexOf("::")) return false
    val left: List<String>
    val right: List<String>
    if (compression >= 0) {
        left = address.substring(0, compression).ipv6Segments() ?: return false
        right = address.substring(compression + 2).ipv6Segments() ?: return false
        if (left.any { '.' in it }) return false
    } else {
        left = address.ipv6Segments() ?: return false
        right = emptyList()
    }
    val segments = left + right
    var groups = 0
    segments.forEachIndexed { index, segment ->
        if ('.' in segment) {
            if (index != segments.lastIndex || !segment.isIpv4Literal()) return false
            groups += 2
        } else {
            if (segment.length !in 1..4 || !segment.all { it.isHexDigit() }) return false
            groups += 1
        }
    }
    return if (compression >= 0) groups < 8 else groups == 8
}

private fun String.ipv6Segments(): List<String>? {
    if (isEmpty()) return emptyList()
    if (startsWith(':') || endsWith(':')) return null
    return split(':').takeIf { segments -> segments.none { it.isEmpty() } }
}

private fun Char.isSafeZoneCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-' || this == '.'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
