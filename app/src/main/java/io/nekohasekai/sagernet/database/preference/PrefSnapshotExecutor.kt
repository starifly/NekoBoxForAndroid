package io.nekohasekai.sagernet.database.preference

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Dedicated ordered single-thread disk executor for the cached preference store
 * ([RoomPreferenceDataStore] in its `cached` mode).
 *
 * Deliberately NOT [io.nekohasekai.sagernet.database.DbExecutors.query]: that pool uses a
 * `CallerRunsPolicy` rejection handler, which under queue saturation runs the DB task on the
 * *submitting* thread — possibly the main thread. For this bridge we must NEVER run SQLite on the
 * main thread (the whole point of dropping `allowMainThreadQueries()` on `PublicDatabase`), so we
 * use a single ordered worker with an unbounded queue: tasks always run on this worker, in
 * submission order, and never bounce back to the caller.
 *
 * Single-thread + ordered guarantees:
 *  - prime / refresh full-table reads and write-through commits are serialized, so a write
 *    enqueued before a refresh commits before that refresh's `all()` runs (no lost write).
 *  - the worker is a daemon so it never blocks process shutdown.
 */
internal object PrefSnapshotExecutor {

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "pref-snapshot").apply { isDaemon = true }
    }

    // corePoolSize == maxPoolSize == 1, unbounded queue: a strict ordered single worker.
    // allowCoreThreadTimeOut lets the idle worker exit so the pool costs nothing at rest; it is
    // recreated lazily on the next submit.
    val disk: Executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        threadFactory,
    ).apply { allowCoreThreadTimeOut(true) }
}
