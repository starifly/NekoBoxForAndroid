package io.nekohasekai.sagernet.ui

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class BackupArchiveFormat {
    JSON,
    ZIP,
}

internal fun encodeBackupArchive(jsonContent: String, format: BackupArchiveFormat) = when (format) {
    BackupArchiveFormat.JSON -> jsonContent.toByteArray(Charsets.UTF_8)
    BackupArchiveFormat.ZIP -> ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(
                ZipEntry("nekobox_backup.json").apply {
                    method = ZipEntry.DEFLATED
                },
            )
            zip.write(jsonContent.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }
        output.toByteArray()
    }
}
