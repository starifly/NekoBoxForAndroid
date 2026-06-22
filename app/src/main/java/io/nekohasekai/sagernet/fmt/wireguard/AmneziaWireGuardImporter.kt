package io.nekohasekai.sagernet.fmt.wireguard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.Inflater

object AmneziaWireGuardImporter {

    const val MAX_UNCOMPRESSED_SIZE = 8 * 1024 * 1024

    enum class ErrorReason {
        INVALID_CONTAINER,
        NO_AWG_PROFILES,
    }

    class ImportException(
        val reason: ErrorReason,
        cause: Throwable? = null,
    ) : IllegalArgumentException(reason.name, cause)

    private data class IniSection(
        val name: String,
        val values: MutableMap<String, MutableList<String>> = linkedMapOf(),
    ) {
        fun add(key: String, value: String) {
            values.getOrPut(key.lowercase(Locale.ROOT)) { mutableListOf() }.add(value)
        }

        fun get(key: String): String? = values[key.lowercase(Locale.ROOT)]?.lastOrNull()

        fun getAll(key: String): List<String> =
            values[key.lowercase(Locale.ROOT)].orEmpty()

        fun contains(key: String): Boolean = values.containsKey(key.lowercase(Locale.ROOT))
    }

    private data class Endpoint(val host: String, val port: Int)

    fun isAmneziaVpn(text: String): Boolean =
        normalizeText(text).startsWith(AMNEZIA_SCHEME, ignoreCase = true)

    fun isWireGuardConfig(text: String): Boolean =
        INTERFACE_SECTION_REGEX.containsMatchIn(normalizeText(text))

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val sections = parseIni(conf)
        val iface = sections.firstOrNull { it.name.equals("Interface", ignoreCase = true) }
            ?: error("Missing Interface section")
        val peers = sections.filter { it.name.equals("Peer", ignoreCase = true) }
        if (peers.isEmpty()) error("Missing Peer section")

        val addresses = iface.getAll("Address")
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (addresses.isEmpty()) error("Missing interface address")

        val privateKey = iface.get("PrivateKey")?.takeIf(String::isNotBlank)
            ?: error("Missing private key")

        val awgFields = listOf(
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
            "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5",
        )
        val isAmnezia = awgFields.any(iface::contains)
        val explicitMtu = iface.get("MTU")?.toIntOrNull()?.takeIf { it > 0 }

        val template = WireGuardBean().applyDefaultValues().apply {
            localAddress = addresses.joinToString("\n")
            this.privateKey = privateKey
            mtu = explicitMtu ?: if (isAmnezia) AMNEZIA_MOBILE_MTU else mtu
            jc = iface.intValue("Jc")
            jmin = iface.intValue("Jmin")
            jmax = iface.intValue("Jmax")
            s1 = iface.intValue("S1")
            s2 = iface.intValue("S2")
            s3 = iface.intValue("S3")
            s4 = iface.intValue("S4")
            h1 = iface.get("H1").orEmpty()
            h2 = iface.get("H2").orEmpty()
            h3 = iface.get("H3").orEmpty()
            h4 = iface.get("H4").orEmpty()
            i1 = iface.get("I1").orEmpty()
            i2 = iface.get("I2").orEmpty()
            i3 = iface.get("I3").orEmpty()
            i4 = iface.get("I4").orEmpty()
            i5 = iface.get("I5").orEmpty()
        }

        return peers.mapNotNull { peer ->
            val endpoint = peer.get("Endpoint")?.let(::parseEndpoint) ?: return@mapNotNull null
            val publicKey = peer.get("PublicKey")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            template.clone().apply {
                serverAddress = endpoint.host
                serverPort = endpoint.port
                peerPublicKey = publicKey
                peerPreSharedKey = peer.get("PresharedKey").orEmpty()
                initializeDefaultValues()
            }
        }.ifEmpty {
            error("No valid peers")
        }
    }

    fun parseVpn(text: String): List<WireGuardBean> {
        try {
            val normalized = normalizeText(text)
            if (!normalized.startsWith(AMNEZIA_SCHEME, ignoreCase = true)) {
                throw ImportException(ErrorReason.INVALID_CONTAINER)
            }
            val payload = normalized.substring(AMNEZIA_SCHEME.length)
                .filterNot(Char::isWhitespace)
            if (payload.length > MAX_BASE64_SIZE) {
                throw ImportException(ErrorReason.INVALID_CONTAINER)
            }
            val decoded = Base64.getUrlDecoder().decode(payload)
            val jsonBytes = decodeQtCompressedOrJson(decoded)
            val root = JsonParser.parseString(jsonBytes.toString(Charsets.UTF_8)).asJsonObject
            return extractAwgProfiles(root)
        } catch (e: ImportException) {
            throw e
        } catch (e: Throwable) {
            throw ImportException(ErrorReason.INVALID_CONTAINER, e)
        }
    }

    private fun extractAwgProfiles(root: JsonObject): List<WireGuardBean> {
        val containers = root.getAsJsonArray("containers")
            ?: throw ImportException(ErrorReason.NO_AWG_PROFILES)
        val awgContainers = containers.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
        }.filter { container ->
            container.stringValue("container")?.let(AWG_CONTAINERS::contains) == true
        }
        if (awgContainers.isEmpty()) {
            throw ImportException(ErrorReason.NO_AWG_PROFILES)
        }

        val profiles = awgContainers.flatMap { container ->
            runCatching { parseAwgContainer(container) }.getOrDefault(emptyList())
        }.toMutableList()
        if (profiles.isEmpty()) {
            throw ImportException(ErrorReason.INVALID_CONTAINER)
        }

        val baseName = root.stringValue("description")
            ?: root.stringValue("hostName")
            ?: DEFAULT_PROFILE_NAME
        profiles.forEachIndexed { index, bean ->
            bean.name = if (index == 0) baseName else "$baseName (${index + 1})"
        }
        return profiles
    }

    private fun parseAwgContainer(container: JsonObject): List<WireGuardBean> {
        val awg = container.getAsJsonObject("awg") ?: error("Missing AWG data")
        val lastConfigElement = awg["last_config"] ?: error("Missing last_config")
        val lastConfig = when {
            lastConfigElement.isJsonObject -> lastConfigElement.asJsonObject
            lastConfigElement.isJsonPrimitive ->
                JsonParser.parseString(lastConfigElement.asString).asJsonObject
            else -> error("Invalid last_config")
        }
        val conf = lastConfig.stringValue("config") ?: error("Missing config")
        val profiles = parseWireGuard(conf)
        if (!hasIniOption(conf, "MTU")) {
            lastConfig.intValue("mtu")?.takeIf { it > 0 }?.let { mtu ->
                profiles.forEach { it.mtu = mtu }
            }
        }
        return profiles
    }

    private fun decodeQtCompressedOrJson(decoded: ByteArray): ByteArray {
        if (decoded.size > MAX_UNCOMPRESSED_SIZE) {
            throw ImportException(ErrorReason.INVALID_CONTAINER)
        }
        if (decoded.firstNonWhitespace() == '{'.code.toByte()) return decoded
        if (decoded.size < QT_HEADER_SIZE + 2) {
            throw ImportException(ErrorReason.INVALID_CONTAINER)
        }

        val expectedSize = ((decoded[0].toInt() and 0xff) shl 24) or
            ((decoded[1].toInt() and 0xff) shl 16) or
            ((decoded[2].toInt() and 0xff) shl 8) or
            (decoded[3].toInt() and 0xff)
        if (expectedSize <= 0 || expectedSize > MAX_UNCOMPRESSED_SIZE) {
            throw ImportException(ErrorReason.INVALID_CONTAINER)
        }

        val inflater = Inflater()
        try {
            inflater.setInput(decoded, QT_HEADER_SIZE, decoded.size - QT_HEADER_SIZE)
            val output = ByteArrayOutputStream(minOf(expectedSize, 8192))
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    if (output.size() + count > expectedSize || output.size() + count > MAX_UNCOMPRESSED_SIZE) {
                        throw ImportException(ErrorReason.INVALID_CONTAINER)
                    }
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw ImportException(ErrorReason.INVALID_CONTAINER)
                } else {
                    throw ImportException(ErrorReason.INVALID_CONTAINER)
                }
            }
            val result = output.toByteArray()
            if (result.size != expectedSize || inflater.remaining != 0) {
                throw ImportException(ErrorReason.INVALID_CONTAINER)
            }
            return result
        } finally {
            inflater.end()
        }
    }

    private fun parseIni(conf: String): List<IniSection> {
        val sections = mutableListOf<IniSection>()
        var current: IniSection? = null
        normalizeText(conf).lineSequence().forEach { rawLine ->
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                current = IniSection(line.substring(1, line.length - 1).trim())
                sections += current!!
                return@forEach
            }
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            current?.add(
                line.substring(0, separator).trim(),
                line.substring(separator + 1).trim(),
            )
        }
        return sections
    }

    private fun stripComment(line: String): String {
        for (index in line.indices) {
            if ((line[index] == '#' || line[index] == ';') &&
                (index == 0 || line[index - 1].isWhitespace())
            ) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun parseEndpoint(value: String): Endpoint? {
        val endpoint = value.trim()
        val host: String
        val portText: String
        if (endpoint.startsWith('[')) {
            val closingBracket = endpoint.indexOf(']')
            if (closingBracket <= 1 || closingBracket + 1 >= endpoint.length ||
                endpoint[closingBracket + 1] != ':'
            ) return null
            host = endpoint.substring(1, closingBracket)
            portText = endpoint.substring(closingBracket + 2)
        } else {
            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0 || separator == endpoint.lastIndex) return null
            host = endpoint.substring(0, separator).trim()
            portText = endpoint.substring(separator + 1)
        }
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host.takeIf(String::isNotBlank)?.let { Endpoint(it, port) }
    }

    private fun hasIniOption(conf: String, key: String): Boolean =
        Regex("(?im)^\\s*${Regex.escape(key)}\\s*=").containsMatchIn(conf)

    private fun IniSection.intValue(key: String): Int = get(key)?.toIntOrNull() ?: 0

    private fun JsonObject.stringValue(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.intValue(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

    private fun ByteArray.firstNonWhitespace(): Byte? =
        firstOrNull { !it.toInt().toChar().isWhitespace() }

    private fun normalizeText(text: String): String = text.trim().removePrefix("\uFEFF")

    private const val AMNEZIA_SCHEME = "vpn://"
    private const val QT_HEADER_SIZE = 4
    private const val AMNEZIA_MOBILE_MTU = 1280
    private const val DEFAULT_PROFILE_NAME = "AmneziaWG"
    private const val MAX_BASE64_SIZE = MAX_UNCOMPRESSED_SIZE * 4 / 3 + 16
    private val AWG_CONTAINERS = setOf("amnezia-awg", "amnezia-awg2")
    private val INTERFACE_SECTION_REGEX = Regex("(?im)^\\s*\\[\\s*Interface\\s*]\\s*$")
}
