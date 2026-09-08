package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLoopPolicyTest {

    @Test
    fun disablingSpeedStillPollsRouterSelectionWithoutBusySpinning() {
        assertEquals(1_000L, TrafficLoopPolicy.delayMillis(0L, true, false))
        assertEquals(30_000L, TrafficLoopPolicy.delayMillis(0L, false, false))
        assertEquals(1_000L, TrafficLoopPolicy.delayMillis(-1L, true, false))
    }

    @Test
    fun keepsConfiguredRefreshRateWhileHomePageIsVisible() {
        assertEquals(
            1_000L,
            TrafficLoopPolicy.delayMillis(
                configuredMillis = 1_000L,
                mainActivityForeground = true,
                notificationSpeedVisible = false,
            ),
        )
    }

    @Test
    fun limitsVisibleBackgroundNotificationRefreshToFiveSeconds() {
        assertEquals(
            5_000L,
            TrafficLoopPolicy.delayMillis(
                configuredMillis = 1_000L,
                mainActivityForeground = false,
                notificationSpeedVisible = true,
            ),
        )
    }

    @Test
    fun throttlesHiddenBackgroundStatisticsToThirtySeconds() {
        assertEquals(
            30_000L,
            TrafficLoopPolicy.delayMillis(
                configuredMillis = 1_000L,
                mainActivityForeground = false,
                notificationSpeedVisible = false,
            ),
        )
    }

    @Test
    fun initializationRetryNeverBusySpins() {
        assertEquals(250L, TrafficLoopPolicy.initializationRetryMillis(0L))
        assertEquals(1_000L, TrafficLoopPolicy.initializationRetryMillis(1_000L))
    }
}
