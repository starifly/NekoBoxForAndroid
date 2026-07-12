package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.ConfigBuildResult

internal fun safeConfigDiagnostics(config: ConfigBuildResult, pluginConfigCount: Int = 0): String {
    return "config_bytes=${config.config.toByteArray(Charsets.UTF_8).size} " +
        "external_indexes=${config.externalIndex.size} " +
        "traffic_mappings=${config.trafficMap.size} " +
        "profile_tags=${config.profileTagMap.size} " +
        "plugin_configs=$pluginConfigCount"
}
