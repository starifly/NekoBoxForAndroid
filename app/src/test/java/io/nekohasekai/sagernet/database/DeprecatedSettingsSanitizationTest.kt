package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeprecatedSettingsSanitizationTest {

    @Test
    fun deprecatedSettingKeysContainRemovedSettings() {
        val keys = Key.DEPRECATED_SETTING_KEYS
        assertFalse("GLOBAL_ALLOW_INSECURE is active and should not be stripped", keys.contains(Key.GLOBAL_ALLOW_INSECURE))
        assertFalse("ALLOW_INSECURE_ON_REQUEST is active and should not be stripped", keys.contains(Key.ALLOW_INSECURE_ON_REQUEST))
        assertTrue(keys.contains(Key.APPEND_HTTP_PROXY))
        assertTrue(keys.contains(Key.HTTP_PROXY_BYPASS))
        assertTrue(keys.contains(Key.ENABLE_TLS_FRAGMENT))
        assertTrue(keys.contains(Key.FRAGMENT_LENGTH))
        assertTrue(keys.contains(Key.FRAGMENT_INTERVAL))
        assertTrue(keys.contains("enableHevTun"))
        assertTrue(keys.contains("showBottomBar"))
        assertTrue(keys.contains("meteredNetwork"))
        assertTrue(keys.contains("wakeResetConnections"))
    }

    @Test
    fun backupSettingsFilterStripsDeprecatedKeysWhilePreservingValidSettings() {
        val incomingSettings = listOf(
            KeyValuePair().apply {
                key = Key.GLOBAL_ALLOW_INSECURE
                valueType = KeyValuePair.TYPE_BOOLEAN
                value = byteArrayOf(1)
            },
            KeyValuePair().apply {
                key = Key.APPEND_HTTP_PROXY
                valueType = KeyValuePair.TYPE_BOOLEAN
                value = byteArrayOf(1)
            },
            KeyValuePair().apply {
                key = "enableHevTun"
                valueType = KeyValuePair.TYPE_BOOLEAN
                value = byteArrayOf(1)
            },
            KeyValuePair().apply {
                key = Key.REMOTE_DNS
                valueType = KeyValuePair.TYPE_STRING
                value = "https://1.1.1.1/dns-query".toByteArray(Charsets.UTF_8)
            },
            KeyValuePair().apply {
                key = Key.STRICT_ROUTE
                valueType = KeyValuePair.TYPE_BOOLEAN
                value = byteArrayOf(1)
            }
        )

        val filtered = incomingSettings.filterNot { it.key in Key.DEPRECATED_SETTING_KEYS }
        assertEquals(3, filtered.size)
        assertEquals(listOf(Key.GLOBAL_ALLOW_INSECURE, Key.REMOTE_DNS, Key.STRICT_ROUTE), filtered.map { it.key })
    }
}
