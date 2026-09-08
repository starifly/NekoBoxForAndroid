package io.nekohasekai.sagernet.route

data class RouterMembershipPlan(
    val memberProxyIds: List<Long>,
    val selectedProxyId: Long?,
)

object RouterMembership {

    fun plan(
        availableProxyIds: Iterable<Long>,
        requestedProxyIds: Iterable<Long>,
        currentSelectedProxyId: Long?,
    ): RouterMembershipPlan {
        val requested = requestedProxyIds.toSet()
        val members = availableProxyIds.distinct()
            .filter { it in requested }
        val selected = currentSelectedProxyId?.takeIf(members::contains)
            ?: members.firstOrNull()
        return RouterMembershipPlan(members, selected)
    }
}
