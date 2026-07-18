package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving the JVM unit-test harness runs without a device or libcore.aar.
 * Real tests live alongside the code they cover (see Plan 007 for parser tests).
 */
class SampleSmokeTest {
    @Test
    fun harness_runs() {
        assertEquals(4, 2 + 2)
    }
}
