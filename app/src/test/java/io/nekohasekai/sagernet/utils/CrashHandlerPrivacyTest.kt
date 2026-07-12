package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashHandlerPrivacyTest {

    private val allowedKeys = listOf(
        Key.APP_THEME,
        Key.NIGHT_THEME,
        Key.SERVICE_MODE,
        Key.METERED_NETWORK,
        Key.SPEED_INTERVAL,
        Key.SHOW_DIRECT_SPEED,
        Key.LOG_LEVEL,
        Key.PROFILE_TRAFFIC_STATISTICS,
    )

    @Test
    fun allowedRows_areRenderedInAllowlistOrder() {
        val rows = allowedKeys.mapIndexed { index, key ->
            KeyValuePair(key).put("value-$index")
        }.reversed()

        assertEquals(
            allowedKeys.mapIndexed { index, key -> "$key: value-$index" },
            formatDiagnosticSettings(rows),
        )
    }

    @Test
    fun disallowedAndUnknownRows_areOmittedWithTheirValues() {
        val marker = "private-marker-value"
        val rows = listOf(
            KeyValuePair("webdavPassword").put(marker),
            KeyValuePair("serverConfig").put(marker),
            KeyValuePair("selectedProxy").put(marker),
            KeyValuePair("unknownFutureKey").put(marker),
            KeyValuePair(Key.LOG_LEVEL).put(2L),
        )

        val rendered = formatDiagnosticSettings(rows).joinToString("\n")

        assertEquals("${Key.LOG_LEVEL}: 2", rendered)
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("unknownFutureKey"))
    }

    @Test
    fun missingAllowedRows_areNotSynthesized() {
        val rendered = formatDiagnosticSettings(
            listOf(KeyValuePair(Key.SERVICE_MODE).put("vpn")),
        )

        assertEquals(listOf("${Key.SERVICE_MODE}: vpn"), rendered)
        assertTrue(rendered.none { it.startsWith(Key.APP_THEME) })
    }

    @Test
    fun emptyRows_produceNoDiagnosticSettings() {
        assertEquals(emptyList<String>(), formatDiagnosticSettings(emptyList()))
    }
}
