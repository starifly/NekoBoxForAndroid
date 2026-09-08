package io.nekohasekai.sagernet.route

/** A node selection request from the Router surface. Null routerTag means the legacy selector. */
data class RouterSelectionRequest(
    val routerTag: String?,
    val proxyId: Long,
    val mode: RouterRuntimeMode,
    val routerEnabled: Boolean = true,
)

sealed interface RouterSelectionPlan {
    data class HotSwitch(
        val routerTag: String?,
        val selectorTag: String,
        val targetTag: String,
    ) : RouterSelectionPlan

    data object Reload : RouterSelectionPlan

    data object IgnoreMissingRouter : RouterSelectionPlan
}

/** Decides whether a node click can use the running selector or needs a full reload. */
object RouterSelection {

    fun plan(
        request: RouterSelectionRequest,
        routerSelectorTags: Map<String, String>,
        routerMemberIds: Map<String, Set<Long>>,
        profileTags: Map<Long, String>,
        selectorGroupId: Long,
    ): RouterSelectionPlan {
        val selectorTag = if (request.routerTag == null) {
            if (selectorGroupId < 0L) return RouterSelectionPlan.Reload
            "proxy"
        } else {
            if (!request.routerEnabled || request.routerTag.isBlank()) {
                return RouterSelectionPlan.IgnoreMissingRouter
            }
            routerSelectorTags[request.routerTag]
                ?: return RouterSelectionPlan.IgnoreMissingRouter
        }

        if (request.routerTag != null && request.mode != RouterRuntimeMode.SELECTOR) {
            return RouterSelectionPlan.Reload
        }

        val targetTag = profileTags[request.proxyId]
            ?.takeIf { it.isNotBlank() }
            ?: return RouterSelectionPlan.Reload
        if (request.routerTag != null && request.proxyId !in routerMemberIds[request.routerTag].orEmpty()) {
            return RouterSelectionPlan.Reload
        }

        return RouterSelectionPlan.HotSwitch(request.routerTag, selectorTag, targetTag)
    }
}
