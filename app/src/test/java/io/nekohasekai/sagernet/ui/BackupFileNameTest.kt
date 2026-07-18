package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class BackupFileNameTest {

    @Test
    fun fixedDate_hasStableAsciiTimestampShape() {
        val name = backupFileName(Date(0))

        assertTrue(Regex("nekobox_backup_\\d{8}_\\d{6}\\.json").matches(name))
    }

    @Test
    fun generatedName_hasNoUnsafeFilenameCharacters() {
        val name = backupFileName(Date(0))

        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('\n'))
        assertFalse(name.contains(' '))
    }
}
