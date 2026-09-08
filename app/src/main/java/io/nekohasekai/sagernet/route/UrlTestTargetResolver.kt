package io.nekohasekai.sagernet.route

internal object UrlTestTargetResolver {

    fun <T> resolve(
        routerGroupId: Long?,
        loadRouterTargets: (Long) -> List<T>,
        loadNormalGroupTargets: () -> List<T>,
    ): List<T> = if (routerGroupId != null) {
        loadRouterTargets(routerGroupId)
    } else {
        loadNormalGroupTargets()
    }
}
