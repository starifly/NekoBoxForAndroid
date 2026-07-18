package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeConfigDiagnosticsTest {

    @Test
    fun reportsCountsWithoutContent() {
        val markers = listOf(
            "config-marker-α",
            "traffic-marker",
            "profile-marker",
            "username-marker",
            "password-marker",
            "plugin-marker",
        )
        val result = ConfigBuildResult(
            config = markers[0],
            externalIndex = listOf(
                ConfigBuildResult.IndexEntity(linkedMapOf()),
                ConfigBuildResult.IndexEntity(linkedMapOf()),
            ),
            mainEntId = 91L,
            trafficMap = mapOf(markers[1] to emptyList()),
            profileTagMap = mapOf(92L to markers[2]),
            selectorGroupId = 93L,
            localProxyCredentials = mapOf(1234 to (markers[3] to markers[4])),
        )

        val summary = safeConfigDiagnostics(result, pluginConfigCount = 3)

        assertEquals(
            "config_bytes=${markers[0].toByteArray(Charsets.UTF_8).size} " +
                "external_indexes=2 traffic_mappings=1 profile_tags=1 plugin_configs=3",
            summary,
        )
        markers.forEach { marker -> assertFalse(summary.contains(marker)) }
    }

    @Test
    fun reportsEmptyCollections() {
        val result = ConfigBuildResult(
            config = "",
            externalIndex = emptyList(),
            mainEntId = 0L,
            trafficMap = emptyMap(),
            profileTagMap = emptyMap(),
            selectorGroupId = 0L,
        )

        assertEquals(
            "config_bytes=0 external_indexes=0 traffic_mappings=0 profile_tags=0 plugin_configs=0",
            safeConfigDiagnostics(result),
        )
    }

    @Test
    fun countsSerializedConfigurationUtf8Bytes() {
        val result = ConfigBuildResult(
            config = "é🙂",
            externalIndex = emptyList(),
            mainEntId = 0L,
            trafficMap = emptyMap(),
            profileTagMap = emptyMap(),
            selectorGroupId = 0L,
        )

        val summary = safeConfigDiagnostics(result)

        assertEquals(
            "config_bytes=6 external_indexes=0 traffic_mappings=0 profile_tags=0 plugin_configs=0",
            summary,
        )
    }
}
