package io.nekohasekai.sagernet.route

data class RouterNodeSnapshot(
    val id: Long,
    val stableId: String? = null,
    val name: String,
    val subscriptionId: Long? = null,
    val enabled: Boolean = true,
    val available: Boolean = true,
)

data class RouterMatchRequest(
    val routerId: Long,
    val sourceGroupIds: List<Long>,
    val filter: RouterFilterValidation,
)

object RouterMatcher {
    fun match(
        nodes: Iterable<RouterNodeSnapshot>,
        requests: Iterable<RouterMatchRequest>,
    ): Map<Long, List<Long>> = requests.associate { request ->
        val nodesBySource = nodes.filter { it.enabled && it.available }.groupBy { it.subscriptionId }
        request.routerId to request.sourceGroupIds.asSequence().distinct()
            .flatMap { sourceId -> nodesBySource[sourceId].orEmpty().asSequence() }
            .filter { node -> request.filter.include?.containsMatchIn(node.name) != false }
            .filterNot { node -> request.filter.exclude?.containsMatchIn(node.name) == true }
            .distinctBy { node -> node.id }
            .map { node -> node.id }
            .toList()
    }
}
