package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM interleaving tests for [ServiceStopGate] — the stop/reload decision core extracted
 * from BaseService.Data's pendingRestart + stopGeneration fields (Plan 013). Each test pins one
 * interleaving of the stop-vs-reload race so a future refactor can't silently break it. All
 * transitions are synchronous; no coroutine machinery needed.
 */
class ServiceStopGateTest {

    /** A reload captured before a stop transition must be detected as stale. */
    @Test
    fun reloadCapturedBeforeStop_isStaleAfterStop() {
        val gate = ServiceStopGate()
        val captured = gate.captureGeneration()
        gate.onEnterStopState()
        assertTrue(gate.isStale(captured))
    }

    /**
     * Connecting->Connected progress does NOT bump the generation, so a legitimate reload
     * issued while connecting is not falsely dropped.
     */
    @Test
    fun reloadDuringConnecting_notStale() {
        val gate = ServiceStopGate()
        val captured = gate.captureGeneration()
        // No stop-state transition happens on Connecting->Connected progress.
        assertFalse(gate.isStale(captured))
    }

    /**
     * An explicit CLOSE/user stop racing a reload-induced restart teardown cancels the pending
     * restart: the user's stop wins instead of being silently dropped.
     */
    @Test
    fun explicitStopDuringRestartTeardown_cancelsRestart() {
        val gate = ServiceStopGate()
        assertFalse(gate.onStopRequested(restart = true, alreadyStopping = false))
        assertTrue(gate.onStopRequested(restart = false, alreadyStopping = true))
        assertFalse(gate.consumeRestart())
    }

    /** A restart-teardown with no racing explicit stop launches the restart. */
    @Test
    fun restartSurvivesWhenNoExplicitStop() {
        val gate = ServiceStopGate()
        assertFalse(gate.onStopRequested(restart = true, alreadyStopping = false))
        assertTrue(gate.consumeRestart())
    }

    /** A second restart request merged into an in-flight teardown keeps the restart pending. */
    @Test
    fun secondRestartRequestDuringTeardown_keepsRestart() {
        val gate = ServiceStopGate()
        assertFalse(gate.onStopRequested(restart = true, alreadyStopping = false))
        assertTrue(gate.onStopRequested(restart = true, alreadyStopping = true))
        assertTrue(gate.consumeRestart())
    }

    /** The generation is monotonic: a capture from before each stop is stale after it. */
    @Test
    fun generationMonotonic_multipleStops() {
        val gate = ServiceStopGate()
        repeat(3) {
            val captured = gate.captureGeneration()
            gate.onEnterStopState()
            assertTrue(gate.isStale(captured))
        }
    }

    /**
     * Pins current semantics: once an explicit stop cleared the pending restart during a
     * teardown, a later restart=true merged into the SAME teardown does NOT re-set it — the
     * user's stop stays honored.
     */
    @Test
    fun clearedThenRestartDuringSameTeardown_remainsCleared() {
        val gate = ServiceStopGate()
        assertFalse(gate.onStopRequested(restart = true, alreadyStopping = false))
        assertTrue(gate.onStopRequested(restart = false, alreadyStopping = true))
        assertTrue(gate.onStopRequested(restart = true, alreadyStopping = true))
        assertFalse(gate.consumeRestart())
    }
}
