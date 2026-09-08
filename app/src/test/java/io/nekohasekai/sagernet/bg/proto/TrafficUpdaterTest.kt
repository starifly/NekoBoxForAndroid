package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficUpdaterTest {
    @Test
    fun firstSampleUsesElapsedSamplingTimeAndQueriesEachTagOnce() {
        var now = 40_000L
        var calls = 0
        val first = TrafficUpdater.TrafficLooperData("node", rx = 100L)
        val second = TrafficUpdater.TrafficLooperData("node")
        val updater = TrafficUpdater(
            queryStats = { _, _ -> calls++; 2_000L },
            items = listOf(first, second),
            monotonicMillis = { now },
        )
        now += 2_000L
        updater.updateAll()
        assertEquals(2, calls)
        assertEquals(1_000L, first.rxRate)
        assertEquals(first.rxRate, second.rxRate)
        assertEquals(2_100L, first.rx)
        assertTrue(first.hasTrafficDelta)
    }

    @Test
    fun identicalClockReadingDoesNotConsumeCounters() {
        var now = 10L
        var calls = 0
        val item = TrafficUpdater.TrafficLooperData("node")
        val updater = TrafficUpdater({ _, _ -> calls++; 50L }, listOf(item), { now })
        updater.updateAll()
        assertEquals(0, calls)
        now += 1_000L
        updater.updateAll()
        assertEquals(50L, item.rx)
        assertEquals(50L, item.rxRate)
    }
}
