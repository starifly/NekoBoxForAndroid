package io.nekohasekai.sagernet.bg.proto

internal fun olcrtcReadyMarkerFileName(port: Int, ownerToken: String) = "olcrtc_ready_${port}_$ownerToken"

internal fun olcrtcSidecarReadyTimeoutMillis(configuredTimeoutMillis: Long, recoveryEnabled: Boolean) =
    maxOf(if (recoveryEnabled) 60_000L else 15_000L, configuredTimeoutMillis)

internal fun readinessMarkerSatisfied(markerRequired: Boolean, markerPresent: Boolean) =
    !markerRequired || markerPresent

internal fun shouldFailSidecarReadiness(pendingPorts: Set<Int>, requiredPorts: Set<Int>, strict: Boolean) =
    pendingPorts.isNotEmpty() && (strict || pendingPorts.any { it in requiredPorts })
