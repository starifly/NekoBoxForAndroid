package io.nekohasekai.sagernet.fmt.wireguard

import java.net.Inet6Address
import java.net.InetAddress

internal fun normalizeWireGuardLocalAddresses(value: String): List<String> {
    val addresses = value.lineSequence()
        .flatMap { it.splitToSequence(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::normalizeWireGuardLocalAddress)
        .toList()
    require(addresses.isNotEmpty()) { "Missing WireGuard local address" }
    return addresses
}

internal fun normalizeWireGuardLocalAddress(value: String): String {
    val parts = value.trim().split('/', limit = 3)
    require(parts.size <= 2) { "Invalid WireGuard local address: $value" }

    val rawHost = parts[0].trim()
    val host = if (rawHost.startsWith('[') && rawHost.endsWith(']')) {
        rawHost.substring(1, rawHost.length - 1)
    } else {
        rawHost
    }
    val addressBits: Int
    val normalizedHost: String
    if (host.contains(':')) {
        require('%' !in host && isIpv6Address(host)) {
            "Invalid WireGuard local address: $value"
        }
        addressBits = 128
        normalizedHost = host
    } else {
        val octets = host.split('.')
        require(octets.size == 4 && octets.all(::isValidIpv4Octet)) {
            "Invalid WireGuard local address: $value"
        }
        addressBits = 32
        normalizedHost = octets.joinToString(".") { it.toInt().toString() }
    }

    val prefix = if (parts.size == 2) {
        parts[1].trim().toIntOrNull()?.takeIf { it in 0..addressBits }
            ?: throw IllegalArgumentException("Invalid WireGuard local address: $value")
    } else {
        addressBits
    }
    return "$normalizedHost/$prefix"
}

private fun isValidIpv4Octet(value: String): Boolean =
    value.isNotEmpty() && value.all(Char::isDigit) &&
        value.toIntOrNull()?.let { it in 0..255 } == true

private fun isIpv6Address(value: String): Boolean = try {
    InetAddress.getByName(value) is Inet6Address
} catch (_: Exception) {
    false
}
