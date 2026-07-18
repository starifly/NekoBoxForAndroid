package moe.matsuri.nb4a.utils

import io.nekohasekai.sagernet.ktx.ImportTooLargeException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.Deflater

/**
 * Tests for the bounded zlib inflate (Plan 035) that caps decompression output to prevent a
 * decompression bomb on deep-link / universal-link import. Pure JVM (java.util.zip only; no
 * Android Base64 / libcore JNI on these paths).
 */
class ZlibDecompressTest {

    @Test
    fun roundTripUnderLimit() {
        val original = "hello world — proxy config 世界".toByteArray(Charsets.UTF_8)
        val compressed = Util.zlibCompress(original, Deflater.BEST_COMPRESSION)
        val restored = Util.zlibDecompress(compressed)
        assertArrayEquals(original, restored)
    }

    @Test
    fun inflatedOutputOverLimitThrows() {
        // Highly compressible input: a small compressed blob that inflates well past a tiny cap.
        val big = ByteArray(64 * 1024) // 64 KiB of zeros compresses to a few hundred bytes
        val compressed = Util.zlibCompress(big, Deflater.BEST_COMPRESSION)
        assertThrows(ImportTooLargeException::class.java) {
            Util.zlibDecompress(compressed, limit = 1024)
        }
    }

    @Test
    fun inflatedOutputAtLimitSucceeds() {
        val data = ByteArray(1024) { (it % 7).toByte() }
        val compressed = Util.zlibCompress(data, Deflater.BEST_COMPRESSION)
        val restored = Util.zlibDecompress(compressed, limit = 1024)
        assertArrayEquals(data, restored)
    }

    @Test
    fun truncatedStreamThrows() {
        val original = "a reasonably long config payload ".repeat(50).toByteArray(Charsets.UTF_8)
        val compressed = Util.zlibCompress(original, Deflater.BEST_COMPRESSION)
        // Drop the tail so the stream never reaches finished(); must be rejected, not accepted
        // as partial output.
        val truncated = compressed.copyOf(compressed.size / 2)
        assertThrows(Exception::class.java) {
            Util.zlibDecompress(truncated)
        }
    }
}
