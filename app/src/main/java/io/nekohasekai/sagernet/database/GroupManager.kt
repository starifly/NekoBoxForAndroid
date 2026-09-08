package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.route.RouterFilterConfig
import io.nekohasekai.sagernet.route.RouterMembership
import io.nekohasekai.sagernet.route.RouterMemberSnapshot
import io.nekohasekai.sagernet.route.RouterNodeSnapshot
import io.nekohasekai.sagernet.route.RouterReconcileGroup
import io.nekohasekai.sagernet.route.RouterReconciler
import io.nekohasekai.sagernet.route.danglingRouterMemberProxyIds
import io.nekohasekai.sagernet.route.routerStableIdOrFallback
import io.nekohasekai.sagernet.route.routerNodeKey

object GroupManager {

    data class RouterRefreshSnapshot(
        val membersByRouterId: Map<Long, List<RouterMemberSnapshot>>
    )

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
        suspend fun routerGroupsUpdated() = Unit
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        DataStore.selectedProxy = 0L
        SagerDatabase.proxyDao.deleteAll(groupId)
        cleanupDanglingRouterMembers()
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    fun replaceRouterMembers(
        router: RouterGroup,
        availableProxyIds: List<Long>,
        requestedProxyIds: Iterable<Long>,
    ) {
        val plan = RouterMembership.plan(
            availableProxyIds = availableProxyIds,
            requestedProxyIds = requestedProxyIds,
            currentSelectedProxyId = router.selectedProxyId
                .takeIf { it != RouterGroup.NO_SELECTION },
        )
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.routerMemberDao.replaceMembers(
                router.id,
                plan.memberProxyIds.mapIndexed { index, proxyId ->
                    RouterMember(
                        routerId = router.id,
                        proxyId = proxyId,
                        userOrder = index.toLong(),
                    )
                },
            )
            SagerDatabase.routerGroupDao.update(
                router.copy(selectedProxyId = plan.selectedProxyId ?: RouterGroup.NO_SELECTION),
            )
        }
    }

    fun snapshotRouterMembers(): RouterRefreshSnapshot {
        val proxies = SagerDatabase.proxyDao.getAll().associateBy { it.id }
        val sourceGroups = SagerDatabase.groupDao.allGroups().associateBy { it.id }
        val members = SagerDatabase.routerGroupDao.all().associate { router ->
            router.id to SagerDatabase.routerMemberDao.getByRouter(router.id).mapNotNull { member ->
                proxies[member.proxyId]?.let { proxy ->
                    RouterMemberSnapshot(
                        proxyId = proxy.id,
                        stableId = proxy.routerStableId(),
                        sourceGroupId = sourceGroups[proxy.groupId]
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.id,
                        userOrder = member.userOrder
                    )
                }
            }
        }
        return RouterRefreshSnapshot(members)
    }

    suspend fun reconcileRouterMembers(previous: RouterRefreshSnapshot) {
        // Keep the old selected ID until reconciliation can resolve it through the snapshot.
        cleanupDanglingRouterMembers(clearInvalidSelections = false)
        val routers = SagerDatabase.routerGroupDao.all()
            .filter { it.stableTag.isNotBlank() }
        if (routers.isEmpty()) {
            cleanupDanglingRouterMembers()
            return
        }

        val groups = routers.mapNotNull { router ->
            runCatching {
                RouterReconcileGroup(
                    routerId = router.id,
                    stableTag = router.stableTag,
                    sourceGroupIds = SagerDatabase.routerGroupSourceDao.sourcesFor(router.id)
                        .map { it.sourceGroupId },
                    filter = RouterFilterConfig.fromJson(router.matchConfig).validate(),
                    selectedProxyId = router.selectedProxyId.takeIf { it != RouterGroup.NO_SELECTION }
                )
            }.onFailure { error ->
                Logs.e("Router ${router.stableTag} match configuration is invalid", error)
                SagerDatabase.routerGroupDao.setLastError(router.id, "Invalid Router match configuration")
            }.getOrNull()
        }
        if (groups.isEmpty()) {
            cleanupDanglingRouterMembers()
            return
        }

        val sourceGroups = SagerDatabase.groupDao.allGroups().associateBy { it.id }
        val nodes = SagerDatabase.proxyDao.getAll().mapNotNull { proxy ->
            runCatching {
                RouterNodeSnapshot(
                    id = proxy.id,
                    stableId = proxy.routerStableId(),
                    name = proxy.displayNameOrFallback(),
                    subscriptionId = sourceGroups[proxy.groupId]
                        ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                        ?.id,
                    enabled = true,
                    available = true,
                )
            }.onFailure { error ->
                Logs.e("Failed to snapshot proxy ${proxy.id}", error)
            }.getOrNull()
        }

        val result = RouterReconciler.reconcile(nodes, groups, previous.membersByRouterId)
        if (result.error != null) {
            Logs.e("Router reconciliation preserved existing members: ${result.error}")
            routers.filter { router -> groups.any { it.routerId == router.id } }.forEach { router ->
                SagerDatabase.routerGroupDao.setLastError(router.id, result.error)
            }
            cleanupDanglingRouterMembers()
            return
        }

        val matchedAt = System.currentTimeMillis()
        // Record the matchConfig and sourceGroupIds that were active when we computed members, so we can
        // detect concurrent configuration or source changes inside the synchronized block.
        val snapshotMatchConfigs = routers.associate { it.id to it.matchConfig }
        val snapshotSourceGroupIds = groups.associate { it.routerId to it.sourceGroupIds.sorted() }
        synchronized(RouterGroupRepository.routerSyncLock) {
            SagerDatabase.instance.runInTransaction {
                result.membersByRouterId.forEach { (routerId, members) ->
                    // Re-read the router and its sources inside the lock to detect concurrent changes.
                    val freshRouter = SagerDatabase.routerGroupDao.getById(routerId) ?: return@forEach
                    val freshSources = SagerDatabase.routerGroupSourceDao.sourcesFor(routerId).map { it.sourceGroupId }.sorted()
                    // If the persisted matchConfig or sources have changed since we computed members, our
                    // result is stale. Skip writing; the save() that changed the config/sources will
                    // trigger a new reconcile with up-to-date filter and source data.
                    val computedMatchConfig = snapshotMatchConfigs[routerId]
                    val computedSources = snapshotSourceGroupIds[routerId]
                    if (computedMatchConfig != null && computedMatchConfig != freshRouter.matchConfig) {
                        Logs.w("Router ${freshRouter.stableTag}: matchConfig changed during reconcile, skipping stale members")
                        return@forEach
                    }
                    if (computedSources != null && computedSources != freshSources) {
                        Logs.w("Router ${freshRouter.stableTag}: sources changed during reconcile, skipping stale members")
                        return@forEach
                    }
                    SagerDatabase.routerMemberDao.replaceMembers(
                        routerId,
                        members.map { member ->
                            RouterMember(
                                routerId = routerId,
                                proxyId = member.proxyId,
                                userOrder = member.userOrder,
                                lastMatchedAt = matchedAt
                            )
                        }
                    )
                    val selectedProxyId = if (freshRouter.selectedProxyId != RouterGroup.NO_SELECTION &&
                        members.any { it.proxyId == freshRouter.selectedProxyId }
                    ) {
                        freshRouter.selectedProxyId
                    } else {
                        result.selectedProxyIdsByRouterId[routerId] ?: RouterGroup.NO_SELECTION
                    }
                    val selectedNodeKey = members.firstOrNull { it.proxyId == selectedProxyId }
                        ?.let { routerNodeKey(it.sourceGroupId, it.stableId) }
                        .orEmpty()
                    val lastError = if (members.isEmpty()) "No nodes match ${freshRouter.name}" else ""
                    // Use a field-level update to avoid overwriting fields that may have been
                    // changed by a concurrent save() (e.g. matchConfig, mode, name).
                    SagerDatabase.routerGroupDao.updateSelectionAndError(
                        routerId = routerId,
                        selectedProxyId = selectedProxyId,
                        selectedNodeKey = selectedNodeKey,
                        lastError = lastError,
                    )
                }
                cleanupDanglingRouterMembers()
            }
        }
        iterator { routerGroupsUpdated() }
    }

    fun markRouterRefreshFailed(sourceGroupId: Long, message: String) {
        val error = message.ifBlank { "Subscription refresh failed" }
        SagerDatabase.routerGroupSourceDao.routersForSource(sourceGroupId)
            .forEach { source ->
                // Use a field-level update to avoid overwriting concurrent changes to other fields
                // (e.g. matchConfig updated by a save() racing with a failed refresh).
                SagerDatabase.routerGroupDao.setLastError(source.routerId, error)
            }
    }

    fun cleanupDanglingRouterMembers(clearInvalidSelections: Boolean = true) {
        runCatching {
            val currentProxyIds = SagerDatabase.proxyDao.getAll()
                .filter { runCatching { it.requireBean() }.isSuccess }
                .map { it.id }
                .toSet()
            val members = SagerDatabase.routerGroupDao.all().flatMap { router ->
                SagerDatabase.routerMemberDao.getByRouter(router.id)
            }
            danglingRouterMemberProxyIds(members.map { member ->
                RouterMemberSnapshot(member.proxyId, "proxy:${member.proxyId}")
            }, currentProxyIds).forEach { proxyId ->
                SagerDatabase.routerMemberDao.deleteByProxy(proxyId)
            }
            if (clearInvalidSelections) SagerDatabase.routerGroupDao.clearInvalidSelections()
        }.onFailure { error ->
            Logs.e("Unable to clean dangling router members", error)
        }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        val routerSnapshot = snapshotRouterMembers()
        SagerDatabase.routerGroupSourceDao.deleteBySource(groupId)
        SagerDatabase.groupDao.deleteById(groupId)
        SagerDatabase.proxyDao.deleteByGroup(groupId)
        reconcileRouterMembers(routerSnapshot)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val routerSnapshot = snapshotRouterMembers()
        group.forEach { SagerDatabase.routerGroupSourceDao.deleteBySource(it.id) }
        SagerDatabase.groupDao.deleteGroup(group)
        SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
        reconcileRouterMembers(routerSnapshot)
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}

internal fun ProxyEntity.routerStableId(): String {
    return routerStableIdOrFallback(
        uuid.takeIf { it.isNotBlank() }
            ?: runCatching { requireBean().routerStableIdentity() }.getOrNull(),
        id
    )
}

internal fun ProxyEntity.displayNameOrFallback(): String =
    runCatching { displayName() }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: runCatching { displayAddress() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uuid.takeIf { it.isNotBlank() }
        ?: "Proxy $id"

internal fun AbstractBean.routerStableIdentity(): String {
    return clone().apply {
        name = ""
        customOutboundJson = ""
        customConfigJson = ""
    }.toUniversalLink()
}
