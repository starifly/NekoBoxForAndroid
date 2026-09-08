package io.nekohasekai.sagernet.fmt

data class RouteOutboundChoice(
    val outbound: Long,
    val routerGroupId: Long,
)

internal fun serializeRouteOutboundChoice(
    value: Int,
    legacyProfileId: Long,
    routerGroupId: Long,
    routerChoiceValue: Int,
): RouteOutboundChoice = when (value) {
    0 -> RouteOutboundChoice(0L, 0L)
    1 -> RouteOutboundChoice(-1L, 0L)
    2 -> RouteOutboundChoice(-2L, 0L)
    routerChoiceValue -> RouteOutboundChoice(0L, routerGroupId)
    else -> RouteOutboundChoice(legacyProfileId, 0L)
}
