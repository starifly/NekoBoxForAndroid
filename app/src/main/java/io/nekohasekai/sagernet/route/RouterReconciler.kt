package io.nekohasekai.sagernet.route

data class RouterMemberSnapshot(
    val proxyId: Long,
    val stableId: String,
    val sourceGroupId: Long? = null,
    val userOrder: Long = 0L,
)

data class RouterReconcileGroup(
    val routerId: Long,
    val stableTag: String,
    val sourceGroupIds: List<Long>,
    val filter: RouterFilterValidation,
    val selectedProxyId: Long? = null,
)

data class RouterReconciliationResult(
    val membersByRouterId: Map<Long, List<RouterMemberSnapshot>>,
    val selectedProxyIdsByRouterId: Map<Long, Long?>,
    val preservedPreviousMembers: Boolean,
    val error: String? = null,
)

internal fun routerStableIdOrFallback(stableId: String?, proxyId: Long): String =
    stableId?.takeIf(String::isNotBlank) ?: "proxy:$proxyId"

internal fun routerNodeKey(sourceGroupId: Long?, stableId: String): String =
    "${sourceGroupId ?: 0}:$stableId"

internal fun danglingRouterMemberProxyIds(
    members: Iterable<RouterMemberSnapshot>,
    currentProxyIds: Set<Long>,
): Set<Long> = members.map { it.proxyId }.filterNot(currentProxyIds::contains).toSet()

private data class StableNodeKey(val sourceGroupId: Long?, val stableId: String)

object RouterReconciler {
    fun reconcile(
        currentNodes: Iterable<RouterNodeSnapshot>,
        groups: Iterable<RouterReconcileGroup>,
        previousMembers: Map<Long, List<RouterMemberSnapshot>> = emptyMap(),
    ): RouterReconciliationResult {
        val nodes = currentNodes.toList()
        val groupList = groups.toList()
        if (nodes.isEmpty()) return preserved(groupList, previousMembers, "subscription refresh returned no nodes")

        val validNodes = nodes.filter { it.enabled && it.available }
        if (validNodes.isEmpty()) {
            return preserved(groupList, previousMembers, "subscription refresh returned no valid nodes")
        }

        val currentById = validNodes.associateBy { it.id }
        val currentByStableKey = validNodes.asSequence()
            .filter { !it.stableId.isNullOrBlank() }
            .distinctBy { StableNodeKey(it.subscriptionId, it.stableId!!) }
            .associateBy { StableNodeKey(it.subscriptionId, it.stableId!!) }
        val matchedByGroup = RouterMatcher.match(
            validNodes,
            groupList.map { RouterMatchRequest(it.routerId, it.sourceGroupIds, it.filter) },
        )

        val membersByRouterId = groupList.associate { group ->
            val matchedIds = matchedByGroup[group.routerId].orEmpty()
            val matchedIdSet = matchedIds.toSet()
            val retained = previousMembers[group.routerId].orEmpty()
                .withIndex()
                .sortedWith(compareBy<IndexedValue<RouterMemberSnapshot>> { it.value.userOrder }.thenBy { it.index })
                .mapNotNull { indexed ->
                    val previous = indexed.value
                    val current = currentById[previous.proxyId]
                        ?.takeIf {
                            it.subscriptionId == previous.sourceGroupId && it.id in matchedIdSet &&
                                routerStableIdOrFallback(it.stableId, it.id) == previous.stableId
                        }
                        ?: currentByStableKey[StableNodeKey(previous.sourceGroupId, previous.stableId)]
                            ?.takeIf { it.id in matchedIdSet }
                    current?.let { node ->
                        previous.copy(
                            proxyId = node.id,
                            stableId = routerStableIdOrFallback(node.stableId, node.id),
                            sourceGroupId = node.subscriptionId,
                        )
                    }
                }
                .distinctBy { it.proxyId }

            val retainedIds = retained.mapTo(mutableSetOf()) { it.proxyId }
            val nextOrder = retained.maxOfOrNull { it.userOrder } ?: 0L
            val added = matchedIds.asSequence()
                .filterNot(retainedIds::contains)
                .mapNotNull(currentById::get)
                .map { node ->
                    RouterMemberSnapshot(
                        proxyId = node.id,
                        stableId = routerStableIdOrFallback(node.stableId, node.id),
                        sourceGroupId = node.subscriptionId,
                    )
                }
                .mapIndexed { index, member -> member.copy(userOrder = nextOrder + index + 1) }
                .toList()
            group.routerId to retained + added
        }

        val selectedByRouterId = groupList.associate { group ->
            val members = membersByRouterId[group.routerId].orEmpty()
            val previousSelected = previousMembers[group.routerId].orEmpty()
                .firstOrNull { it.proxyId == group.selectedProxyId }
            val selected = previousSelected?.let { old ->
                    members.firstOrNull {
                        it.stableId == old.stableId && it.sourceGroupId == old.sourceGroupId
                    }
                }
                ?: members.firstOrNull {
                    previousSelected == null && it.proxyId == group.selectedProxyId
                }
                ?: members.firstOrNull()
            group.routerId to selected?.proxyId
        }

        return RouterReconciliationResult(membersByRouterId, selectedByRouterId, false)
    }

    private fun preserved(
        groups: List<RouterReconcileGroup>,
        previous: Map<Long, List<RouterMemberSnapshot>>,
        error: String,
    ) = RouterReconciliationResult(
        membersByRouterId = previous,
        selectedProxyIdsByRouterId = groups.associate { it.routerId to it.selectedProxyId },
        preservedPreviousMembers = true,
        error = error,
    )
}
