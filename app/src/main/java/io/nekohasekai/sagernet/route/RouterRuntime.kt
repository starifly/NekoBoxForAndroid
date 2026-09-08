package io.nekohasekai.sagernet.route

/** Runtime-only Router group data after ConfigBuilder has read the database. */
data class RouterRuntimeGroup(
    val stableTag: String,
    val mode: RouterRuntimeMode,
    val memberProxyIds: List<Long>,
    val selectedProxyId: Long,
    val id: Long = 0L,
    val name: String = "",
    val filter: RouterFilterConfig = RouterFilterConfig(),
)

enum class RouterRuntimeMode {
    SELECTOR,
    URL_TEST
}

/** A sing-box-independent Router outbound description. */
data class RouterRuntimeOutbound(
    val tag: String,
    val mode: RouterRuntimeMode,
    val outbounds: List<String>,
    val defaultTag: String?,
    val filter: RouterFilterConfig,
)

class RouterRuntimeException(
    val groupId: Long,
    val groupName: String,
    val reason: Reason,
) : IllegalStateException(
    when (reason) {
        Reason.MISSING -> "Proxy group ${groupName.ifBlank { groupId.toString() }} is missing"
        Reason.DISABLED -> "Proxy group ${groupName.ifBlank { groupId.toString() }} is disabled"
        Reason.EMPTY -> "Proxy group ${groupName.ifBlank { groupId.toString() }} has no available nodes"
    }
) {
    enum class Reason { MISSING, DISABLED, EMPTY }
}

/**
 * Resolves persisted Router member IDs against the outbound tags built for the current config.
 * Router tags are persisted separately from ProxyEntity IDs, so subscription refreshes cannot
 * invalidate references to a Router group.
 */
object RouterRuntime {
    fun findUrlTestGroupForProxy(
        groups: Iterable<RouterRuntimeGroup>,
        selectedProxyId: Long,
    ): String? = groups.firstOrNull {
        it.mode == RouterRuntimeMode.URL_TEST &&
            it.stableTag.isNotBlank() &&
            selectedProxyId in it.memberProxyIds
    }?.stableTag

    fun build(
        groups: Iterable<RouterRuntimeGroup>,
        proxyTags: Map<Long, String>,
        reservedTags: Set<String> = emptySet()
    ): List<RouterRuntimeOutbound> {
        val usedTags = reservedTags.toMutableSet()

        return groups.mapNotNull { group ->
            if (group.stableTag.isBlank() || !usedTags.add(group.stableTag)) return@mapNotNull null

            val memberTags = group.memberProxyIds.mapNotNull(proxyTags::get).distinct()
            if (memberTags.isEmpty()) return@mapNotNull null
            val outbounds = memberTags
            val defaultTag = if (group.mode == RouterRuntimeMode.SELECTOR) {
                proxyTags[group.selectedProxyId]?.takeIf { it in memberTags } ?: outbounds.first()
            } else {
                null
            }

            RouterRuntimeOutbound(group.stableTag, group.mode, outbounds, defaultTag, group.filter)
        }
    }
}

object RouterRuntimeSelection {
    fun resolve(
        routerTags: Map<Long, String>,
        profileTags: Map<Long, String>,
        currentOutbound: (String) -> String,
    ): LongArray {
        if (routerTags.isEmpty() || profileTags.isEmpty()) return longArrayOf()
        val profileIdsByTag = profileTags.entries.associate { (id, tag) -> tag to id }
        val result = ArrayList<Long>(routerTags.size * 2)
        routerTags.forEach { (routerId, routerTag) ->
            val profileId = profileIdsByTag[currentOutbound(routerTag)] ?: return@forEach
            result += routerId
            result += profileId
        }
        return result.toLongArray()
    }

    fun toMap(pairs: LongArray): Map<Long, Long> {
        val result = linkedMapOf<Long, Long>()
        var index = 0
        while (index + 1 < pairs.size) {
            result[pairs[index]] = pairs[index + 1]
            index += 2
        }
        return result
    }
}
