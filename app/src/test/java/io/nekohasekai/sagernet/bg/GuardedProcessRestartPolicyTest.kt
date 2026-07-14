package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedProcessRestartPolicyTest {

    @Test
    fun delayAfterExit_progressesAndCaps() {
        val backoff = GuardedProcessRestartPolicy().createBackoff()!!

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            List(7) { backoff.delayAfterExit(readyDurationMillis = null) },
        )
    }

    @Test
    fun delayAfterExit_resetsOnlyAfterStableReadyDuration() {
        val backoff = GuardedProcessRestartPolicy().createBackoff()!!

        assertEquals(1_000L, backoff.delayAfterExit(readyDurationMillis = null))
        assertEquals(2_000L, backoff.delayAfterExit(readyDurationMillis = 59_999L))
        assertEquals(1_000L, backoff.delayAfterExit(readyDurationMillis = 60_000L))
        assertEquals(2_000L, backoff.delayAfterExit(readyDurationMillis = null))
    }

    @Test
    fun delayAfterExit_capsWithoutOverflow() {
        val policy = GuardedProcessRestartPolicy(
            initialDelayMillis = Long.MAX_VALUE - 1L,
            maximumDelayMillis = Long.MAX_VALUE,
            stableAfterReadyMillis = 1L,
        )
        val backoff = policy.createBackoff()!!

        assertEquals(Long.MAX_VALUE - 1L, backoff.delayAfterExit(readyDurationMillis = null))
        assertEquals(Long.MAX_VALUE, backoff.delayAfterExit(readyDurationMillis = null))
    }

    @Test
    fun policy_rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            GuardedProcessRestartPolicy(initialDelayMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuardedProcessRestartPolicy(initialDelayMillis = 2L, maximumDelayMillis = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuardedProcessRestartPolicy(stableAfterReadyMillis = 0L)
        }
    }

    @Test
    fun absentPolicy_hasNoBackoff() {
        val policy: GuardedProcessRestartPolicy? = null

        assertNull(policy.createBackoff())
    }

    @Test
    fun disabledRestartFailsOnEveryExit() {
        assertTrue(
            shouldFailAfterProcessExit(
                restartOnExit = false,
                restartPolicy = null,
                processUptimeMillis = 60_000L,
            ),
        )
    }

    @Test
    fun legacyAndPolicyRestartDecisionsRemainUnchanged() {
        assertTrue(
            shouldFailAfterProcessExit(
                restartOnExit = true,
                restartPolicy = null,
                processUptimeMillis = 999L,
            ),
        )
        assertFalse(
            shouldFailAfterProcessExit(
                restartOnExit = true,
                restartPolicy = null,
                processUptimeMillis = 1_000L,
            ),
        )
        assertFalse(
            shouldFailAfterProcessExit(
                restartOnExit = true,
                restartPolicy = GuardedProcessRestartPolicy(),
                processUptimeMillis = 0L,
            ),
        )
    }
}
