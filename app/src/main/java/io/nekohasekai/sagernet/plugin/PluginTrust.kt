package io.nekohasekai.sagernet.plugin

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import java.util.LinkedHashSet

object PluginTrust {

    private const val HEX = "0123456789abcdef"

    val builtinTrustedFingerprints = setOf(
        "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641",
        "35762758ce86a6ec297d9ccac689469bc43b9fed8ae1b27f100a86bbac00a055",
    )

    data class SignerRecord(
        val current: Set<String>,
        val history: Set<String>,
        val hasMultipleCurrentSigners: Boolean,
    )

    data class RawSignerData(
        val packageName: String,
        val current: List<ByteArray>?,
        val history: List<ByteArray>?,
        val hasMultipleCurrentSigners: Boolean,
    )

    data class RejectedPlugin(
        val packageName: String,
        val currentFingerprints: Set<String>?,
    )

    data class CandidateTrustResult<T>(
        val trusted: List<T>,
        val rejected: List<RejectedPlugin>,
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    fun isCanonicalFingerprint(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun approvalIdentity(packageName: String, currentFingerprints: Set<String>): String? {
        if (!isValidPackageName(packageName) || currentFingerprints.isEmpty()) return null
        if (currentFingerprints.any { !isCanonicalFingerprint(it) }) return null
        return "$packageName|${currentFingerprints.sorted().joinToString(",")}"
    }

    fun parseApprovals(values: Set<String>): Set<String> = values.mapNotNullTo(mutableSetOf()) { value ->
        val separator = value.indexOf('|')
        if (separator <= 0 || separator != value.lastIndexOf('|')) return@mapNotNullTo null
        val packageName = value.substring(0, separator)
        val fingerprints = value.substring(separator + 1).split(',')
        if (fingerprints.any { it.isEmpty() }) return@mapNotNullTo null
        val canonical = approvalIdentity(packageName, fingerprints.toSet())
        canonical.takeIf { it == value }
    }

    fun isTrusted(
        packageName: String,
        signer: SignerRecord,
        hostCurrentFingerprints: Set<String>,
        approvals: Set<String>,
        builtinFingerprints: Set<String> = builtinTrustedFingerprints,
    ): Boolean {
        if (!isValidPackageName(packageName)) return false
        if (!isValidSignerRecord(signer)) return false
        if (builtinFingerprints.any { !isCanonicalFingerprint(it) }) return false

        val hostCurrent = hostCurrentFingerprints.takeIf {
            it.isNotEmpty() && it.all(::isCanonicalFingerprint)
        } ?: emptySet()
        val parsedApprovals = parseApprovals(approvals)

        if (signer.hasMultipleCurrentSigners) {
            val approvalIdentity = approvalIdentity(packageName, signer.current)
            val exactApproval = approvalIdentity != null && approvalIdentity in parsedApprovals
            val exactHostIdentity = hostCurrent.size > 1 && signer.current == hostCurrent
            val independentRoots = builtinFingerprints + hostCurrent.takeIf { it.size == 1 }.orEmpty()
            return exactApproval || exactHostIdentity || signer.current.all { it in independentRoots }
        }

        val independentRoots = builtinFingerprints + hostCurrent.takeIf { it.size == 1 }.orEmpty()
        val lineage = signer.history + signer.current
        return lineage.any { fingerprint ->
            val approvalIdentity = approvalIdentity(packageName, setOf(fingerprint))
            fingerprint in independentRoots ||
                (approvalIdentity != null && approvalIdentity in parsedApprovals)
        }
    }

    fun toSignerRecord(expectedPackageName: String, raw: RawSignerData?): SignerRecord? {
        if (raw == null || raw.packageName != expectedPackageName) return null
        val currentBytes = raw.current ?: return null
        if (currentBytes.isEmpty() || currentBytes.any { it.isEmpty() }) return null
        val current = currentBytes.mapTo(mutableSetOf(), ::sha256Hex)
        if (current.size != currentBytes.size) return null
        if (raw.hasMultipleCurrentSigners != (current.size > 1)) return null

        val historyBytes = raw.history.orEmpty()
        if (historyBytes.any { it.isEmpty() }) return null
        val history = historyBytes.mapTo(mutableSetOf(), ::sha256Hex)
        if (history.size != historyBytes.size) return null
        if (raw.hasMultipleCurrentSigners && history.isNotEmpty()) return null

        return SignerRecord(current, history, raw.hasMultipleCurrentSigners)
    }

    fun readSignerRecord(packageManager: PackageManager, packageName: String): SignerRecord? = try {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            readModernSignerData(packageManager, packageName)
        } else {
            readLegacySignerData(packageManager, packageName)
        }
        toSignerRecord(packageName, raw)
    } catch (_: Exception) {
        null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readModernSignerData(packageManager: PackageManager, packageName: String): RawSignerData? {
        val packageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = packageInfo.signingInfo ?: return null
        val hasMultipleSigners = signingInfo.hasMultipleSigners()
        return RawSignerData(
            packageName = packageInfo.packageName,
            current = signingInfo.apkContentsSigners.map { it.toByteArray() },
            history = if (hasMultipleSigners) {
                emptyList()
            } else {
                signingInfo.signingCertificateHistory?.map { it.toByteArray() }
            },
            hasMultipleCurrentSigners = hasMultipleSigners,
        )
    }

    @Suppress("DEPRECATION")
    private fun readLegacySignerData(packageManager: PackageManager, packageName: String): RawSignerData {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val signatures = packageInfo.signatures?.map { it.toByteArray() }
        return RawSignerData(
            packageName = packageInfo.packageName,
            current = signatures,
            history = emptyList(),
            hasMultipleCurrentSigners = signatures?.size?.let { it > 1 } == true,
        )
    }

    fun <T> filterTrustedCandidates(
        candidates: List<T>,
        packageName: (T) -> String,
        signerRecord: (T) -> SignerRecord?,
        hostCurrentFingerprints: Set<String>,
        approvals: Set<String>,
        builtinFingerprints: Set<String> = builtinTrustedFingerprints,
    ): CandidateTrustResult<T> {
        val trusted = ArrayList<T>(candidates.size)
        val rejected = ArrayList<RejectedPlugin>()
        for (candidate in candidates) {
            val candidatePackage = packageName(candidate)
            val signer = signerRecord(candidate)
            if (
                signer != null &&
                isTrusted(
                    candidatePackage,
                    signer,
                    hostCurrentFingerprints,
                    approvals,
                    builtinFingerprints,
                )
            ) {
                trusted += candidate
            } else {
                rejected += RejectedPlugin(candidatePackage, signer?.current)
            }
        }
        return CandidateTrustResult(trusted, rejected)
    }

    fun rejectionKey(pluginId: String, rejection: RejectedPlugin): String {
        val identity = rejection.currentFingerprints?.sorted()?.joinToString(",") ?: "unreadable"
        return "$pluginId\u0000${rejection.packageName}\u0000$identity"
    }

    private fun isValidPackageName(packageName: String) =
        packageName.isNotBlank() && packageName.none { it == '|' || it == ',' || it == '\n' || it == '\r' }

    private fun isValidSignerRecord(signer: SignerRecord): Boolean {
        if (signer.current.isEmpty()) return false
        if (signer.current.any { !isCanonicalFingerprint(it) }) return false
        if (signer.history.any { !isCanonicalFingerprint(it) }) return false
        if (signer.hasMultipleCurrentSigners != (signer.current.size > 1)) return false
        return !signer.hasMultipleCurrentSigners || signer.history.isEmpty()
    }
}

class BoundedDeduplicator(private val capacity: Int = 128) {

    private val keys = LinkedHashSet<String>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun shouldNotify(key: String): Boolean {
        if (!keys.add(key)) return false
        if (keys.size > capacity) {
            val iterator = keys.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }
}
