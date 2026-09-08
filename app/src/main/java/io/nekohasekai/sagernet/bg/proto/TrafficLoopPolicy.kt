package io.nekohasekai.sagernet.bg.proto

object TrafficLoopPolicy {
    private const val MIN_BACKGROUND_NOTIFICATION_MILLIS = 5_000L
    private const val MIN_BACKGROUND_HIDDEN_MILLIS = 30_000L
    private const val MIN_INITIALIZATION_RETRY_MILLIS = 250L

    fun delayMillis(
        configuredMillis: Long,
        mainActivityForeground: Boolean,
        notificationSpeedVisible: Boolean,
    ): Long = when {
        mainActivityForeground -> if (configuredMillis > 0L) configuredMillis else 1_000L
        notificationSpeedVisible -> maxOf(configuredMillis, MIN_BACKGROUND_NOTIFICATION_MILLIS)
        else -> maxOf(configuredMillis, MIN_BACKGROUND_HIDDEN_MILLIS)
    }

    fun initializationRetryMillis(configuredMillis: Long): Long =
        maxOf(configuredMillis, MIN_INITIALIZATION_RETRY_MILLIS)
}
