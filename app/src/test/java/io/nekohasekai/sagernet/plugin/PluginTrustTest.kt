package io.nekohasekai.sagernet.plugin

import io.nekohasekai.sagernet.plugin.PluginTrust.RawSignerData
import io.nekohasekai.sagernet.plugin.PluginTrust.SignerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTrustTest {

    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)
    private val signerC = "c".repeat(64)
    private val packageName = "io.example.plugin"

    @Test
    fun sha256Hex_knownVectorAndCanonicalValidation() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PluginTrust.sha256Hex("abc".toByteArray()),
        )
        assertTrue(PluginTrust.isCanonicalFingerprint(signerA))
        assertFalse(PluginTrust.isCanonicalFingerprint(signerA.uppercase()))
        assertFalse(PluginTrust.isCanonicalFingerprint("a".repeat(63)))
        assertFalse(PluginTrust.isCanonicalFingerprint("g".repeat(64)))
        assertEquals(2, PluginTrust.builtinTrustedFingerprints.size)
        assertTrue(PluginTrust.builtinTrustedFingerprints.all(PluginTrust::isCanonicalFingerprint))
    }

    @Test
    fun singleSigner_acceptsBuiltinOwnSignerAndPackageApproval() {
        val signer = singleSigner(signerA)

        assertTrue(
            PluginTrust.isTrusted(packageName, signer, emptySet(), emptySet(), setOf(signerA)),
        )
        assertTrue(
            PluginTrust.isTrusted(packageName, signer, setOf(signerA), emptySet(), emptySet()),
        )
        assertTrue(
            PluginTrust.isTrusted(
                packageName,
                signer,
                emptySet(),
                setOf(requireNotNull(PluginTrust.approvalIdentity(packageName, setOf(signerA)))),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(packageName, signer, emptySet(), emptySet(), emptySet()),
        )
    }

    @Test
    fun approval_isScopedToPackage() {
        val approval = requireNotNull(PluginTrust.approvalIdentity(packageName, setOf(signerA)))

        assertFalse(
            PluginTrust.isTrusted(
                "io.example.other",
                singleSigner(signerA),
                emptySet(),
                setOf(approval),
                emptySet(),
            ),
        )
    }

    @Test
    fun singleSignerRotation_acceptsTrustedHistoryOnly() {
        val rotated = SignerRecord(
            current = setOf(signerB),
            history = setOf(signerA, signerB),
            hasMultipleCurrentSigners = false,
        )
        val approval = requireNotNull(PluginTrust.approvalIdentity(packageName, setOf(signerA)))

        assertTrue(
            PluginTrust.isTrusted(packageName, rotated, emptySet(), setOf(approval), emptySet()),
        )
        assertTrue(
            PluginTrust.isTrusted(packageName, rotated, emptySet(), emptySet(), setOf(signerA)),
        )
        assertFalse(
            PluginTrust.isTrusted(packageName, rotated, emptySet(), emptySet(), setOf(signerC)),
        )
    }

    @Test
    fun multiSignerApproval_requiresExactCurrentSet() {
        val approved = requireNotNull(
            PluginTrust.approvalIdentity(packageName, setOf(signerA, signerB)),
        )

        assertTrue(
            PluginTrust.isTrusted(
                packageName,
                multiSigner(signerA, signerB),
                emptySet(),
                setOf(approved),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                singleSigner(signerA),
                emptySet(),
                setOf(approved),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                multiSigner(signerA, signerB, signerC),
                emptySet(),
                setOf(approved),
                emptySet(),
            ),
        )
    }

    @Test
    fun multiSigner_requiresEveryIndependentSigner() {
        val signer = multiSigner(signerA, signerB)

        assertTrue(
            PluginTrust.isTrusted(
                packageName,
                signer,
                emptySet(),
                emptySet(),
                setOf(signerA, signerB),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                signer,
                emptySet(),
                emptySet(),
                setOf(signerA),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                SignerRecord(setOf(signerA, signerB), setOf(signerC), true),
                emptySet(),
                emptySet(),
                setOf(signerA, signerB),
            ),
        )
    }

    @Test
    fun multiSignerHost_requiresExactHostSet() {
        val host = setOf(signerA, signerB)

        assertTrue(
            PluginTrust.isTrusted(
                packageName,
                multiSigner(signerA, signerB),
                host,
                emptySet(),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                singleSigner(signerA),
                host,
                emptySet(),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                singleSigner(signerB),
                host,
                emptySet(),
                emptySet(),
            ),
        )
    }

    @Test
    fun malformedAndEmptySignerRecordsFailClosed() {
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                SignerRecord(emptySet(), emptySet(), false),
                emptySet(),
                emptySet(),
                emptySet(),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                SignerRecord(setOf(signerA), emptySet(), true),
                emptySet(),
                emptySet(),
                setOf(signerA),
            ),
        )
        assertFalse(
            PluginTrust.isTrusted(
                packageName,
                SignerRecord(setOf(signerA.uppercase()), emptySet(), false),
                emptySet(),
                emptySet(),
                setOf(signerA),
            ),
        )
    }

    @Test
    fun approvals_parseStrictCanonicalIdentities() {
        val single = requireNotNull(PluginTrust.approvalIdentity(packageName, setOf(signerA)))
        val multiple = requireNotNull(
            PluginTrust.approvalIdentity(packageName, setOf(signerB, signerA)),
        )
        val parsed = PluginTrust.parseApprovals(
            setOf(
                single,
                multiple,
                "$packageName|$signerB,$signerA",
                "$packageName|${signerA.uppercase()}",
                "$packageName|",
                "missing-separator",
            ),
        )

        assertEquals(setOf(single, multiple), parsed)
        assertEquals("$packageName|$signerA,$signerB", multiple)
    }

    @Test
    fun signerMaterialConversion_coversLegacyHistoryMultipleAndFailures() {
        val current = "current".toByteArray()
        val old = "old".toByteArray()
        val expectedCurrent = PluginTrust.sha256Hex(current)
        val expectedOld = PluginTrust.sha256Hex(old)

        assertEquals(
            SignerRecord(setOf(expectedCurrent), emptySet(), false),
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, listOf(current), emptyList(), false),
            ),
        )
        assertEquals(
            SignerRecord(setOf(expectedCurrent), setOf(expectedOld), false),
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, listOf(current), listOf(old), false),
            ),
        )
        assertEquals(
            2,
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, listOf(current, old), emptyList(), true),
            )?.current?.size,
        )
        assertNull(PluginTrust.toSignerRecord(packageName, null))
        assertNull(
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData("io.example.other", listOf(current), emptyList(), false),
            ),
        )
        assertNull(
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, null, emptyList(), false),
            ),
        )
        assertNull(
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, emptyList(), emptyList(), false),
            ),
        )
        assertNull(
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, listOf(byteArrayOf()), emptyList(), false),
            ),
        )
        assertNull(
            PluginTrust.toSignerRecord(
                packageName,
                RawSignerData(packageName, listOf(current), listOf(old), true),
            ),
        )
    }

    @Test
    fun filterTrustedCandidates_excludesBeforeSelectionAndHandlesUnreadable() {
        data class Candidate(val packageName: String, val authority: String, val signer: SignerRecord?)

        val trusted = Candidate(
            "io.example.trusted",
            "io.nekohasekai.sagernet.plugin.trusted",
            singleSigner(signerA),
        )
        val preferredButUntrusted = Candidate(
            "io.example.untrusted",
            "moe.matsuri.exe.untrusted",
            singleSigner(signerB),
        )
        val unreadable = Candidate("io.example.unreadable", "moe.matsuri.exe.unreadable", null)
        val result = PluginTrust.filterTrustedCandidates(
            listOf(preferredButUntrusted, unreadable, trusted),
            Candidate::packageName,
            Candidate::signer,
            emptySet(),
            emptySet(),
            setOf(signerA),
        )

        assertEquals(listOf(trusted), result.trusted)
        assertEquals(
            listOf(
                PluginTrust.RejectedPlugin(preferredButUntrusted.packageName, setOf(signerB)),
                PluginTrust.RejectedPlugin(unreadable.packageName, null),
            ),
            result.rejected,
        )

        val allRejected = PluginTrust.filterTrustedCandidates(
            listOf(preferredButUntrusted),
            Candidate::packageName,
            Candidate::signer,
            emptySet(),
            emptySet(),
            setOf(signerA),
        )
        assertTrue(allRejected.trusted.isEmpty())
    }

    @Test
    fun rejectionDeduplicator_handlesChangesAndBoundedEviction() {
        val deduplicator = BoundedDeduplicator(2)
        val first = PluginTrust.rejectionKey(
            "plugin",
            PluginTrust.RejectedPlugin(packageName, setOf(signerA)),
        )
        val changed = PluginTrust.rejectionKey(
            "plugin",
            PluginTrust.RejectedPlugin(packageName, setOf(signerB)),
        )
        val other = PluginTrust.rejectionKey(
            "other",
            PluginTrust.RejectedPlugin(packageName, null),
        )

        assertTrue(deduplicator.shouldNotify(first))
        assertFalse(deduplicator.shouldNotify(first))
        assertTrue(deduplicator.shouldNotify(changed))
        assertTrue(deduplicator.shouldNotify(other))
        assertTrue(deduplicator.shouldNotify(first))
    }

    private fun singleSigner(fingerprint: String) = SignerRecord(
        current = setOf(fingerprint),
        history = emptySet(),
        hasMultipleCurrentSigners = false,
    )

    private fun multiSigner(vararg fingerprints: String) = SignerRecord(
        current = fingerprints.toSet(),
        history = emptySet(),
        hasMultipleCurrentSigners = true,
    )
}
