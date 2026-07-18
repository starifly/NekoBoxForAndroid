package io.nekohasekai.sagernet.bg

import java.util.concurrent.atomic.AtomicLong

/**
 * The stop/reload decision core previously spread across BaseService.Data's
 * pendingRestart + stopGeneration fields. Pure logic so interleavings are
 * testable on the JVM. Threading contract unchanged from the fields it
 * replaces: mutations happen on the main dispatcher, except generation reads
 * ([captureGeneration]/[isStale]) which may occur on any thread (AtomicLong).
 */
class ServiceStopGate {

    // Bumped only when the service transitions into a stop (Stopping/Stopped). An async
    // reload() captures this at entry and bails in reloadInner() if it advanced, so a stop
    // that raced the async refresh can't revive a stopped service. Crucially this does NOT
    // bump on Connecting->Connected progress, so a legitimate reload/profile-switch issued
    // while connecting is not falsely dropped. ABA-safe (monotonic).
    private val stopGeneration = AtomicLong(0)

    // True while a reload-induced restart (stopRunner(restart=true)) is in flight. An explicit
    // CLOSE/user stop racing the teardown clears it so the restart is cancelled rather than
    // reviving a service the user stopped. Touched only on the main dispatcher.
    var pendingRestart = false
        private set

    /** Snapshot the current stop generation (any thread). */
    fun captureGeneration(): Long = stopGeneration.get()

    /** True if a stop-state transition happened since [captured] was taken (any thread). */
    fun isStale(captured: Long): Boolean = stopGeneration.get() != captured

    /** The service transitioned into Stopping or Stopped: advance the generation. */
    fun onEnterStopState() {
        stopGeneration.incrementAndGet()
    }

    /**
     * stopRunner entry. Returns true if a teardown is already in flight (caller must return).
     * If this is an explicit stop (restart=false) racing a reload-induced restart
     * (restart=true), the pending restart is cancelled so an explicit CLOSE/user-stop wins
     * instead of being silently dropped while the in-flight stopRunner(true) goes on to call
     * startRunner(). A restart=true request merged into an in-flight teardown does NOT
     * re-set the flag: a prior explicit stop stays cancelled.
     */
    fun onStopRequested(restart: Boolean, alreadyStopping: Boolean): Boolean {
        if (alreadyStopping) {
            if (!restart) pendingRestart = false
            return true
        }
        pendingRestart = restart
        return false
    }

    /**
     * Teardown finished: should a restart be launched? Re-reads (does NOT clear)
     * pendingRestart: an explicit CLOSE that raced this teardown may have cleared it.
     */
    fun consumeRestart(): Boolean = pendingRestart
}
