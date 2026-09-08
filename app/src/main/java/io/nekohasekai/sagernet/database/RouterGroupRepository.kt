package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.route.RouterFilterConfig
import io.nekohasekai.sagernet.route.RouterFilterException
import io.nekohasekai.sagernet.route.RouterMatchRequest
import io.nekohasekai.sagernet.route.RouterMatcher
import io.nekohasekai.sagernet.route.RouterNodeSnapshot
import io.nekohasekai.sagernet.route.routerNodeKey
import io.nekohasekai.sagernet.route.routerStableIdOrFallback
import io.nekohasekai.sagernet.ktx.Logs
import java.net.URI
import java.util.UUID

data class RouterGroupDraft(
    val id: Long = 0L,
    val name: String,
    val mode: Int,
    val enabled: Boolean,
    val sourceGroupIds: List<Long>,
    val filter: RouterFilterConfig,
)

data class RouterGroupPreview(
    val proxyIds: List<Long>,
    val names: List<String>,
)

sealed interface RouterDeleteResult {
    data object Deleted : RouterDeleteResult
    data class Referenced(val ruleCount: Int) : RouterDeleteResult
}

class RouterGroupValidationException(
    val field: Field,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    enum class Field {
        NAME,
        MODE,
        SOURCES,
        INCLUDE,
        EXCLUDE,
        URL,
        INTERVAL,
        TOLERANCE,
    }
}

fun RouterGroupDraft.validate(
    existingGroups: Iterable<RouterGroup>,
    validSubscriptionIds: Set<Long>,
) {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty() || existingGroups.any {
            it.id != id && it.name.trim().equals(normalizedName, ignoreCase = true)
        }
    ) throw RouterGroupValidationException(RouterGroupValidationException.Field.NAME, "Group name is empty or already used")

    if (mode != RouterGroup.MODE_SELECTOR && mode != RouterGroup.MODE_URL_TEST) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.MODE, "Unsupported group mode")
    }
    if (enabled && sourceGroupIds.isEmpty() || sourceGroupIds.any { it !in validSubscriptionIds }) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.SOURCES, "Select at least one existing subscription")
    }
    try {
        filter.validate()
    } catch (error: RouterFilterException) {
        val field = if (error.field == RouterFilterException.Field.INCLUDE) {
            RouterGroupValidationException.Field.INCLUDE
        } else {
            RouterGroupValidationException.Field.EXCLUDE
        }
        throw RouterGroupValidationException(field, error.message ?: "Invalid regular expression", error)
    }
    if (filter.intervalSeconds < 10) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.INTERVAL, "Interval must be at least 10 seconds")
    }
    if (filter.intervalSeconds > 86400) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.INTERVAL, "Interval must not exceed 86400 seconds (24 hours)")
    }
    if (filter.toleranceMs !in 0..65535) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.TOLERANCE, "Tolerance must be between 0 and 65535 ms")
    }
    val uri = runCatching { URI(filter.testUrl) }.getOrNull()
    if (mode == RouterGroup.MODE_URL_TEST && (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank())) {
        throw RouterGroupValidationException(RouterGroupValidationException.Field.URL, "Test URL must be HTTP or HTTPS")
    }
}

object RouterGroupRepository {
    fun all(): List<RouterGroup> = SagerDatabase.routerGroupDao.all()

    fun get(routerId: Long): RouterGroup? = SagerDatabase.routerGroupDao.getById(routerId)

    fun sourceIds(routerId: Long): List<Long> =
        SagerDatabase.routerGroupSourceDao.sourcesFor(routerId).map { it.sourceGroupId }

    fun preview(draft: RouterGroupDraft): RouterGroupPreview {
        draft.filter.validate()
        val sourceIds = draft.sourceGroupIds.distinct()
        val nodes = sourceIds.flatMap { sourceId ->
            SagerDatabase.proxyDao.getByGroup(sourceId).mapNotNull { proxy ->
                runCatching {
                    proxy.requireBean()
                    RouterNodeSnapshot(
                        id = proxy.id,
                        stableId = proxy.routerStableId(),
                        name = proxy.displayNameOrFallback(),
                        subscriptionId = sourceId,
                        enabled = true,
                        available = true,
                    )
                }.onFailure { error ->
                    Logs.e("Failed to snapshot proxy ${proxy.id}", error)
                }.getOrNull()
            }
        }
        val subNames = runCatching { SagerDatabase.groupDao.allGroups().associate { it.id to it.displayName() } }.getOrDefault(emptyMap())
        val ids = RouterMatcher.match(
            nodes,
            listOf(RouterMatchRequest(draft.id, sourceIds, draft.filter.validate())),
        )[draft.id].orEmpty()
        val byId = nodes.associateBy { it.id }
        val names = ids.mapNotNull { id ->
            byId[id]?.let { node ->
                val subName = node.subscriptionId?.let { subNames[it] }
                if (!subName.isNullOrBlank()) "[${subName}] ${node.name.trim()}" else node.name.trim()
            }
        }
        return RouterGroupPreview(ids, names)
    }

    internal val routerSyncLock = Any()

    suspend fun save(draft: RouterGroupDraft): RouterGroup {
        val subscriptions = SagerDatabase.groupDao.allGroups()
            .filter { it.type == GroupType.SUBSCRIPTION }
            .mapTo(mutableSetOf()) { it.id }
        draft.validate(all(), subscriptions)
        val snapshot = GroupManager.snapshotRouterMembers()
        val savedGroup = synchronized(routerSyncLock) {
            SagerDatabase.instance.runInTransaction<RouterGroup> {
                val existing = draft.id.takeIf { it > 0 }?.let(SagerDatabase.routerGroupDao::getById)
                val group = RouterGroup(
                    id = existing?.id ?: 0L,
                    stableTag = existing?.stableTag ?: newStableTag(),
                    name = draft.name.trim(),
                    mode = draft.mode,
                    enabled = draft.enabled,
                    matchConfig = draft.filter.toJson(),
                    selectedProxyId = existing?.selectedProxyId ?: RouterGroup.NO_SELECTION,
                    userOrder = existing?.userOrder ?: (SagerDatabase.routerGroupDao.nextOrder() ?: 1L),
                    selectedNodeKey = existing?.selectedNodeKey.orEmpty(),
                    lastError = existing?.lastError.orEmpty(),
                )
                if (existing == null) group.id = SagerDatabase.routerGroupDao.create(group)
                else SagerDatabase.routerGroupDao.update(group)
                SagerDatabase.routerGroupSourceDao.replaceSources(group.id, draft.sourceGroupIds)
                group
            }
        }
        GroupManager.reconcileRouterMembers(snapshot)
        return SagerDatabase.routerGroupDao.getById(savedGroup.id) ?: savedGroup
    }

    fun delete(routerId: Long): RouterDeleteResult {
        val references = SagerDatabase.rulesDao.countByRouterGroup(routerId)
        if (references > 0) return RouterDeleteResult.Referenced(references)
        val group = SagerDatabase.routerGroupDao.getById(routerId) ?: return RouterDeleteResult.Deleted
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.routerMemberDao.deleteByRouter(routerId)
            SagerDatabase.routerGroupSourceDao.deleteByRouter(routerId)
            SagerDatabase.routerGroupDao.delete(group)
        }
        return RouterDeleteResult.Deleted
    }

    fun select(routerId: Long, proxyId: Long): RouterGroup = synchronized(routerSyncLock) {
        SagerDatabase.instance.runInTransaction<RouterGroup> {
            val group = SagerDatabase.routerGroupDao.getById(routerId)
                ?: throw IllegalArgumentException("Proxy group does not exist")
            check(group.enabled && group.mode == RouterGroup.MODE_SELECTOR) {
                "Only an enabled selector group accepts a manual selection"
            }
            check(SagerDatabase.routerMemberDao.getByRouter(routerId).any { it.proxyId == proxyId }) {
                "Selected node is not a member of ${group.name}"
            }
            val proxy = SagerDatabase.proxyDao.getById(proxyId)
                ?: throw IllegalArgumentException("Selected node does not exist")
            proxy.requireBean()
            val stableId = proxy.routerStableId()
            val updated = group.copy(
                selectedProxyId = proxyId,
                selectedNodeKey = routerNodeKey(proxy.groupId, stableId),
            )
            SagerDatabase.routerGroupDao.update(updated)
            updated
        }
    }

    private fun newStableTag(): String =
        "router." + UUID.randomUUID().toString().replace("-", "").lowercase()
}
