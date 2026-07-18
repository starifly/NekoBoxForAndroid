package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionUpdaterScheduleTest {

    @Test
    fun overdueSubscription_schedulesImmediately() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 15)),
            nowSeconds = 1_900,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(0L, schedule.initialDelaySeconds)
    }

    @Test
    fun nearFutureSubscription_usesSecondsUntilDue() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 15)),
            nowSeconds = 1_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }

    @Test
    fun farFutureSubscription_preservesSecondsUntilDue() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 60)),
            nowSeconds = 1_100,
        )!!

        assertEquals(60L, schedule.intervalMinutes)
        assertEquals(3_500L, schedule.initialDelaySeconds)
    }

    @Test
    fun delayBelowWorkManagerMinimum_isCoercedForIntervalAndDueTime() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 5)),
            nowSeconds = 1_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }

    @Test
    fun multipleSubscriptions_useSoonestDueSubscription() {
        val schedule = computeSubscriptionWorkSchedule(
            listOf(
                SubscriptionScheduleInput(lastUpdated = 1_000, autoUpdateDelay = 60),
                SubscriptionScheduleInput(lastUpdated = 2_000, autoUpdateDelay = 15),
            ),
            nowSeconds = 2_870,
        )!!

        assertEquals(15L, schedule.intervalMinutes)
        assertEquals(30L, schedule.initialDelaySeconds)
    }
}
