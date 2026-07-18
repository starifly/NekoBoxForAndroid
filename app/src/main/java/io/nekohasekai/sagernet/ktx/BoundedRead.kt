package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Shared cap for untrusted imported/downloaded content. Mirrors the Clash YAML codePointLimit. */
const val MAX_IMPORT_BYTES: Long = 10L * 1024 * 1024 // 10 MiB

class ImportTooLargeException(limit: Long) :
    IOException("Imported content exceeds the $limit-byte limit")

/** Read at most [limit] bytes; throw [ImportTooLargeException] if the stream is larger. */
fun InputStream.readBytesBounded(limit: Long = MAX_IMPORT_BYTES): ByteArray {
    val buf = ByteArray(8 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw ImportTooLargeException(limit)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Read at most [limit] bytes and decode as UTF-8. */
fun InputStream.readTextBounded(limit: Long = MAX_IMPORT_BYTES): String =
    readBytesBounded(limit).toString(Charsets.UTF_8)
