package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Tests for the bounded-read helpers (Plan 032) that cap untrusted imported/downloaded content
 * to prevent OOM/decompression-bomb DoS. Pure JVM (no Android / libcore).
 */
class BoundedReadTest {

    @Test
    fun underLimitReturnsFullContent() {
        val data = ByteArray(1024) { (it % 256).toByte() }
        val out = ByteArrayInputStream(data).readBytesBounded(limit = 4096)
        assertArrayEquals(data, out)
    }

    @Test
    fun exactlyAtLimitReturnsContent() {
        val data = ByteArray(2048) { 1 }
        val out = ByteArrayInputStream(data).readBytesBounded(limit = 2048)
        assertArrayEquals(data, out)
    }

    @Test
    fun overLimitThrows() {
        val data = ByteArray(4097)
        assertThrows(ImportTooLargeException::class.java) {
            ByteArrayInputStream(data).readBytesBounded(limit = 4096)
        }
    }

    @Test
    fun readTextBoundedDecodesUtf8() {
        val text = "héllo, 世界 — proxy"
        val out = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).readTextBounded(limit = 4096)
        assertEquals(text, out)
    }

    @Test
    fun readTextBoundedOverLimitThrows() {
        val text = "x".repeat(5000)
        assertThrows(ImportTooLargeException::class.java) {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).readTextBounded(limit = 1024)
        }
    }

    /**
     * Mirrors the ConfigurationFragment WireGuard-zip loop, which caps each entry at the
     * REMAINING budget so cumulative decompressed bytes across entries can never exceed the
     * total cap (defeats a many-entry zip bomb). Verifies the remaining-budget arithmetic and
     * that the entry overflowing the cumulative cap is the one that throws.
     */
    @Test
    fun cumulativeRemainingBudgetEnforcedAcrossEntries() {
        val total = 4096L
        var remaining = total
        // Two entries, each 1500 bytes: both fit (3000 <= 4096).
        repeat(2) {
            val bytes = ByteArrayInputStream(ByteArray(1500)).readBytesBounded(remaining)
            remaining -= bytes.size
        }
        assertEquals(1096L, remaining)
        // A third 1500-byte entry exceeds the remaining 1096-byte budget -> throws.
        assertThrows(ImportTooLargeException::class.java) {
            ByteArrayInputStream(ByteArray(1500)).readBytesBounded(remaining)
        }
    }
}
