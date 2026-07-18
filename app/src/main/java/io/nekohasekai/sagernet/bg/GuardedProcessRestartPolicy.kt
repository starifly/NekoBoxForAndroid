package io.nekohasekai.sagernet.bg

data class GuardedProcessRestartPolicy(
    val initialDelayMillis: Long = 1_000L,
    val maximumDelayMillis: Long = 30_000L,
    val stableAfterReadyMillis: Long = 60_000L,
) {
    init {
        require(initialDelayMillis > 0L) { "initial restart delay must be positive" }
        require(maximumDelayMillis >= initialDelayMillis) {
            "maximum restart delay must not be smaller than the initial delay"
        }
        require(stableAfterReadyMillis > 0L) { "stable readiness duration must be positive" }
    }
}

internal class GuardedProcessRestartBackoff(
    private val policy: GuardedProcessRestartPolicy,
) {
    private var nextDelayMillis = policy.initialDelayMillis

    fun delayAfterExit(readyDurationMillis: Long?): Long {
        if (readyDurationMillis != null && readyDurationMillis >= policy.stableAfterReadyMillis) {
            nextDelayMillis = policy.initialDelayMillis
        }
        val delayMillis = nextDelayMillis
        nextDelayMillis = if (nextDelayMillis >= policy.maximumDelayMillis - nextDelayMillis) {
            policy.maximumDelayMillis
        } else {
            nextDelayMillis * 2L
        }
        return delayMillis
    }
}

internal fun GuardedProcessRestartPolicy?.createBackoff() = this?.let(::GuardedProcessRestartBackoff)

internal fun shouldFailAfterProcessExit(
    restartOnExit: Boolean,
    restartPolicy: GuardedProcessRestartPolicy?,
    processUptimeMillis: Long,
) = !restartOnExit || (restartPolicy == null && processUptimeMillis < 1_000L)
