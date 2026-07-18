package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * androidx [PreferenceDataStore] backed by a Room `KeyValuePair.Dao`.
 *
 * Two modes:
 *  - **uncached** (default, e.g. `profileCacheStore` over the in-memory `TempDatabase`): every
 *    getter is a synchronous SELECT and every setter a synchronous write, as before. Behaviour
 *    unchanged.
 *  - **cached** (`configurationStore` over the disk `PublicDatabase`): reads serve from an
 *    immutable in-memory snapshot (no SQLite on any thread after priming); writes update the
 *    snapshot synchronously and persist to the DB off-main on [PrefSnapshotExecutor]; a
 *    `KeyValuePair` `InvalidationTracker.Observer` coalesces cross-process refreshes. This lets
 *    `PublicDatabase` drop `allowMainThreadQueries()` because the hot read path never touches the
 *    DB on the main thread.
 *
 * Cached values are stored as typed [CachedValue]s (not mutable [KeyValuePair]/`ByteArray`), and
 * `StringSet` values are defensively copied on store and on return.
 *
 * @param cached enable the snapshot cache (only for the single-writer-ish settings store).
 * @param database the `RoomDatabase` whose `KeyValuePair` invalidations should refresh the
 *   snapshot. When non-null, cross-process / other-instance writes are picked up automatically
 *   via an `InvalidationTracker` observer. May be null to disable automatic invalidation
 *   (single-process or test usage); callers then refresh explicitly via
 *   `refreshSuspend()`/`refreshBlocking()`.
 * @param diskExecutor ordered single-thread disk executor for prime/refresh/write-through.
 *   Defaults to `PrefSnapshotExecutor.disk`; overridable in tests.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(
    private val kvPairDao: KeyValuePair.Dao,
    private val cached: Boolean = false,
    private val database: RoomDatabase? = null,
    private val diskExecutor: Executor = PrefSnapshotExecutor.disk,
) : PreferenceDataStore() {

    // ---- snapshot cache (cached mode only) -------------------------------------------------

    private sealed interface CachedValue {
        data class Bool(val v: Boolean) : CachedValue
        data class Flt(val v: Float) : CachedValue
        data class Lng(val v: Long) : CachedValue
        data class Str(val v: String) : CachedValue
        data class StrSet(val v: Set<String>) : CachedValue
    }

    @Volatile
    private var snapshot: Map<String, CachedValue> = emptyMap()

    @Volatile
    private var primed = false

    private val primeLock = Any()

    // Serializes write-through + refresh so a refresh's all() cannot interleave a write and roll
    // back a just-written value (combined with the pending-write generation overlay below).
    private val writeLock = Any()

    // Monotonic generation bumped on every local write. A refresh captures the generation BEFORE
    // its all() read; if a write landed during the read, the refreshed snapshot is rebased so the
    // local write wins (it is the more recent same-process value).
    private val generation = AtomicLong(0)

    // Local writes since a refresh started, applied on top of the refreshed map (rebase overlay).
    // Value carries the generation at which it was written so a stale persist's cleanup cannot
    // drop a newer pending entry for the same key.
    private data class Pending(val value: CachedValue?, val gen: Long)

    private val pendingWrites = HashMap<String, Pending>()
    private val pendingLock = Any()

    // Captures the most recent write-through failure from persist(); surfaced (and cleared) by
    // awaitWrites() so a caller does not trigger a cross-process reload off a failed commit.
    private val writeThroughError = AtomicReference<Throwable?>(null)

    private val refreshScheduled = AtomicBoolean(false)

    // Generation of the most recent reset(). A refresh that started before this reset must not
    // resurrect rows it read from the DB before kvPairDao.reset() committed; doRefresh compares
    // the generation it captured at entry against this and drops its DB base if a reset intervened.
    private val resetGeneration = AtomicLong(0)

    private fun KeyValuePair.toCached(): CachedValue? {
        // Use the entity's typed accessors (long already folds the deprecated TYPE_INT into
        // TYPE_LONG), so this never references the deprecated constant directly.
        boolean?.let { return CachedValue.Bool(it) }
        float?.let { return CachedValue.Flt(it) }
        long?.let { return CachedValue.Lng(it) }
        string?.let { return CachedValue.Str(it) }
        stringSet?.let { return CachedValue.StrSet(HashSet(it)) }
        return null
    }

    private fun buildSnapshot(): Map<String, CachedValue> {
        val rows = kvPairDao.all()
        val map = HashMap<String, CachedValue>(rows.size)
        for (row in rows) {
            row.toCached()?.let { map[row.key] = it }
        }
        return map
    }

    /**
     * Prime the snapshot once with a single bulk SELECT, on [PrefSnapshotExecutor], blocking the
     * caller until loaded. Must be called off the main thread (it would otherwise just block the
     * main thread on the worker; callers are responsible for off-main invocation at startup).
     */
    fun prime() {
        if (!cached || primed) return
        synchronized(primeLock) {
            if (primed) return
            // Register the invalidation observer BEFORE the bulk SELECT so a cross-process write
            // that lands between the SELECT and observer registration still schedules a refresh
            // (otherwise that write would be silently missed until the next local write/refresh).
            registerObserver()
            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<Map<String, CachedValue>>(1)
            val error = arrayOfNulls<Throwable>(1)
            // Capture resetGen + submit the SELECT under writeLock so it is ordered consistently
            // with concurrent writes/resets (all of which submit under writeLock).
            val resetGenAtSchedule: Long
            synchronized(writeLock) {
                resetGenAtSchedule = resetGeneration.get()
                diskExecutor.execute {
                    try {
                        holder[0] = buildSnapshot()
                    } catch (t: Throwable) {
                        error[0] = t
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            error[0]?.let { throw it } // do not mark primed on a failed load (no silent empty cache)
            val dbRows = holder[0] ?: emptyMap()
            // Apply the same reset-generation + pending-overlay logic as doRefresh under writeLock.
            // The reset re-check MUST be inside the lock so a reset() that takes writeLock after
            // the SELECT but before the swap is detected here (otherwise we'd resurrect cleared
            // rows after reset() returned).
            synchronized(writeLock) {
                val resetIntervened = resetGeneration.get() != resetGenAtSchedule
                val built = if (resetIntervened) HashMap<String, CachedValue>() else HashMap(dbRows)
                synchronized(pendingLock) {
                    for ((k, p) in pendingWrites) {
                        if (p.value == null) built.remove(k) else built[k] = p.value
                    }
                }
                snapshot = built
                primed = true
            }
        }
    }

    private fun ensureLoaded() {
        if (primed) return
        prime()
    }

    @Volatile
    private var observerRegistered = false

    private fun registerObserver() {
        val db = database ?: return
        if (observerRegistered) return
        observerRegistered = true
        val observer = object : InvalidationTracker.Observer(arrayOf("KeyValuePair")) {
            override fun onInvalidated(tables: Set<String>) {
                scheduleRefresh()
            }
        }
        db.invalidationTracker.addObserver(observer)
    }

    /** Coalesce a single full refresh + atomic swap (refresh+swap, not clear-and-miss). */
    private fun scheduleRefresh() {
        if (!cached) return
        if (!refreshScheduled.compareAndSet(false, true)) return
        // Capture the reset generation and enqueue the refresh task under writeLock so the submit
        // is ordered consistently with concurrent writes/resets (which also submit under writeLock).
        synchronized(writeLock) {
            val resetGenAtSchedule = resetGeneration.get()
            diskExecutor.execute {
                refreshScheduled.set(false)
                doRefresh(resetGenAtSchedule)
            }
        }
    }

    private fun doRefresh(resetGenAtSchedule: Long) {
        // If a reset() landed at or after this refresh was scheduled, or while we read the DB,
        // the DB rows are stale-relative-to-reset: drop them (start from empty) and let the
        // pending overlay below reapply any post-reset local writes.
        val resetGenAtStart = resetGeneration.get()
        val dbRows = buildSnapshot()
        // Serialize the reset re-check + overlay-apply + snapshot swap under writeLock. The final
        // resetGeneration read MUST happen inside the lock so a reset() that takes writeLock after
        // we read the DB but before we swap is detected here (otherwise we'd overwrite the
        // cleared snapshot with stale rows).
        synchronized(writeLock) {
            val resetGenAtSwap = resetGeneration.get()
            val resetIntervened =
                resetGenAtSwap != resetGenAtSchedule || resetGenAtSwap != resetGenAtStart
            val refreshed = if (resetIntervened) {
                HashMap<String, CachedValue>()
            } else {
                dbRows.toMutableMap()
            }
            synchronized(pendingLock) {
                for ((k, p) in pendingWrites) {
                    if (p.value == null) refreshed.remove(k) else refreshed[k] = p.value
                }
            }
            snapshot = refreshed
        }
    }

    /** Suspend until a full refresh has been read off-main and swapped in. Used by `:bg`. */
    suspend fun refreshSuspend() {
        if (!cached) return
        ensureLoadedSuspend()
        withContext(Dispatchers.IO) { refreshBlocking() }
    }

    /**
     * Suspend until all currently-enqueued write-through DB commits have drained. Because the
     * disk executor is a single ordered worker, a task enqueued after all pending writes runs
     * only once they have committed; awaiting it guarantees prior `put*`/`remove` writes are
     * durable. Use before triggering a cross-process action (e.g. service reload) that re-reads
     * a just-written config key from the DB. No-op when uncached.
     */
    suspend fun awaitWrites() {
        if (!cached) return
        // Capture the write generation BEFORE the barrier. Every put*/remove issued before this
        // call has a pending-overlay entry with gen <= awaitGen; a SUCCESSFUL persist removes it.
        // Because the disk executor is a single ordered worker, after the barrier task runs every
        // such persist has executed, so any pending entry still present with gen <= awaitGen is a
        // write that FAILED to commit. That is the authoritative durability signal.
        val awaitGen: Long
        val latch = CountDownLatch(1)
        // Capture the generation and submit the barrier under writeLock so the barrier is enqueued
        // strictly after every write with gen <= awaitGen (writes also submit under writeLock).
        synchronized(writeLock) {
            awaitGen = generation.get()
            diskExecutor.execute { latch.countDown() }
        }
        withContext(Dispatchers.IO) {
            latch.await()
            val leftover = synchronized(pendingLock) {
                pendingWrites.values.any { it.gen <= awaitGen }
            }
            if (leftover) {
                throw (
                    writeThroughError.get()
                        ?: IOException("preference write-through did not commit")
                    )
            }
        }
    }

    /**
     * Merge [additions] into a string-set row without exposing speculative trust state.
     *
     * Unlike the normal cached setters, this operation reads the durable row and commits the
     * merged value before swapping the process snapshot. Submissions share the ordered disk
     * executor, so concurrent merges cannot overwrite each other. A failed commit leaves the
     * snapshot unchanged and is rethrown to the caller.
     */
    suspend fun addToStringSetDurable(key: String, additions: Set<String>): Set<String> {
        val additionsCopy = HashSet(additions)
        if (!cached) {
            val merged = withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    HashSet(kvPairDao[key]?.stringSet.orEmpty()).apply {
                        addAll(additionsCopy)
                        kvPairDao.put(KeyValuePair(key).put(this))
                    }
                }
            }
            fireChangeListener(key)
            return HashSet(merged)
        }

        ensureLoadedSuspend()
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Set<String>>(1)
        val error = arrayOfNulls<Throwable>(1)
        synchronized(writeLock) {
            val resetGenAtSchedule = resetGeneration.get()
            diskExecutor.execute {
                try {
                    if (resetGeneration.get() != resetGenAtSchedule) {
                        throw IOException("preference reset interrupted durable merge")
                    }
                    val merged = HashSet(kvPairDao[key]?.stringSet.orEmpty()).apply {
                        addAll(additionsCopy)
                    }
                    kvPairDao.put(KeyValuePair(key).put(merged))
                    synchronized(writeLock) {
                        if (resetGeneration.get() == resetGenAtSchedule) {
                            val next = HashMap(snapshot)
                            next[key] = CachedValue.StrSet(HashSet(merged))
                            snapshot = next
                        }
                    }
                    result[0] = HashSet(merged)
                } catch (t: Throwable) {
                    error[0] = t
                } finally {
                    latch.countDown()
                }
            }
        }
        withContext(Dispatchers.IO) { latch.await() }
        error[0]?.let { throw it }
        val merged = result[0] ?: throw IOException("durable preference merge returned no result")
        fireChangeListener(key)
        return HashSet(merged)
    }

    /**
     * Replace every preference row through the same ordering boundary as cached writes and
     * durable string-set merges. The database transaction commits before the snapshot swap.
     */
    suspend fun replaceAllDurable(values: List<KeyValuePair>) {
        val copies = values.map { value ->
            KeyValuePair(value.key).also {
                it.valueType = value.valueType
                it.value = value.value.copyOf()
            }
        }
        if (!cached) {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) { replaceRows(copies) }
            }
            return
        }

        ensureLoadedSuspend()
        val latch = CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        synchronized(writeLock) {
            val replacementGeneration = generation.incrementAndGet()
            val resetGenAtSchedule = resetGeneration.incrementAndGet()
            diskExecutor.execute {
                try {
                    if (resetGeneration.get() != resetGenAtSchedule) {
                        throw IOException("preference reset interrupted durable replacement")
                    }
                    replaceRows(copies)
                    val replacement = HashMap<String, CachedValue>(copies.size)
                    for (value in copies) {
                        value.toCached()?.let { replacement[value.key] = it }
                    }
                    synchronized(writeLock) {
                        if (resetGeneration.get() == resetGenAtSchedule) {
                            synchronized(pendingLock) {
                                val iterator = pendingWrites.iterator()
                                while (iterator.hasNext()) {
                                    val (key, pending) = iterator.next()
                                    if (pending.gen <= replacementGeneration) {
                                        iterator.remove()
                                    } else if (pending.value == null) {
                                        replacement.remove(key)
                                    } else {
                                        replacement[key] = pending.value
                                    }
                                }
                            }
                            snapshot = replacement
                        }
                    }
                } catch (t: Throwable) {
                    error[0] = t
                } finally {
                    latch.countDown()
                }
            }
        }
        withContext(Dispatchers.IO) { latch.await() }
        error[0]?.let { throw it }
    }

    private fun replaceRows(values: List<KeyValuePair>) {
        val replace = {
            kvPairDao.reset()
            kvPairDao.insert(values)
        }
        val roomDatabase = database
        if (roomDatabase == null) {
            replace()
        } else {
            roomDatabase.runInTransaction { replace() }
        }
    }

    private suspend fun ensureLoadedSuspend() {
        if (primed) return
        withContext(Dispatchers.IO) { prime() }
    }

    /** Run a full refresh on [PrefSnapshotExecutor], blocking the caller until swapped in. Must
     *  NOT be called on the main thread. */
    fun refreshBlocking() {
        if (!cached) return
        ensureLoaded()
        val latch = CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        // Capture resetGen + submit under writeLock so the refresh is ordered consistently with
        // concurrent writes/resets (all of which submit under writeLock).
        synchronized(writeLock) {
            val resetGenAtSchedule = resetGeneration.get()
            diskExecutor.execute {
                try {
                    doRefresh(resetGenAtSchedule)
                } catch (t: Throwable) {
                    error[0] = t
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        error[0]?.let { throw it }
    }

    // Apply a local write to the snapshot + pending overlay AND enqueue the off-main DB persist,
    // all under writeLock. Holding the lock across the executor submit is essential: it keeps the
    // executor submission order consistent with `generation`, so an awaitWrites() barrier or a
    // reset() (which also submit under writeLock) can never be enqueued between the pending-overlay
    // update and the DB write (which would break the durability/ordering guarantees).
    private fun writeThrough(key: String, value: CachedValue?, kv: KeyValuePair?) {
        synchronized(writeLock) {
            val gen = generation.incrementAndGet()
            val next = HashMap(snapshot)
            if (value == null) next.remove(key) else next[key] = value
            snapshot = next
            synchronized(pendingLock) { pendingWrites[key] = Pending(value, gen) }
            diskExecutor.execute {
                var persisted = false
                try {
                    if (kv == null) kvPairDao.delete(key) else kvPairDao.put(kv)
                    persisted = true
                } catch (t: Throwable) {
                    // Record the first write-through failure so awaitWrites() can surface a cause.
                    writeThroughError.compareAndSet(null, t)
                } finally {
                    // Drop the pending overlay entry only after a SUCCESSFUL DB write and only if no
                    // newer write for this key superseded it. On failure the overlay is kept so a
                    // later refresh can't roll the snapshot back to the un-persisted value, and
                    // awaitWrites() (which checks for leftover pending entries) still fails.
                    if (persisted) {
                        writeThroughError.set(null)
                        synchronized(pendingLock) {
                            if (pendingWrites[key]?.gen == gen) pendingWrites.remove(key)
                        }
                    }
                }
            }
        }
    }

    // ---- getters ---------------------------------------------------------------------------

    fun getBoolean(key: String): Boolean? =
        if (cached) cachedRead(key) { (it as? CachedValue.Bool)?.v } else kvPairDao[key]?.boolean

    fun getFloat(key: String): Float? =
        if (cached) cachedRead(key) { (it as? CachedValue.Flt)?.v } else kvPairDao[key]?.float

    fun getInt(key: String): Int? =
        if (cached) cachedRead(key) { (it as? CachedValue.Lng)?.v?.toInt() } else kvPairDao[key]?.long?.toInt()

    fun getLong(key: String): Long? =
        if (cached) cachedRead(key) { (it as? CachedValue.Lng)?.v } else kvPairDao[key]?.long

    fun getString(key: String): String? =
        if (cached) cachedRead(key) { (it as? CachedValue.Str)?.v } else kvPairDao[key]?.string

    fun getStringSet(key: String): Set<String>? = if (cached) {
        cachedRead(
            key,
        ) { (it as? CachedValue.StrSet)?.v?.let { s -> HashSet(s) } }
    } else {
        kvPairDao[key]?.stringSet
    }

    private inline fun <T> cachedRead(key: String, extract: (CachedValue?) -> T?): T? {
        ensureLoaded()
        return extract(snapshot[key])
    }

    fun reset() {
        if (cached) {
            synchronized(writeLock) {
                generation.incrementAndGet()
                resetGeneration.incrementAndGet()
                snapshot = emptyMap()
                synchronized(pendingLock) { pendingWrites.clear() }
                // Enqueue the DB reset while holding writeLock so a refresh task cannot capture a
                // resetGenAtSchedule that slips between the cache clear and the reset commit.
                diskExecutor.execute { kvPairDao.reset() }
            }
        } else {
            kvPairDao.reset()
        }
    }

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) = getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) = if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) = if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) = if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)

    override fun putBoolean(key: String, value: Boolean) {
        if (cached) {
            writeThrough(key, CachedValue.Bool(value), KeyValuePair(key).put(value))
        } else {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putFloat(key: String, value: Float) {
        if (cached) {
            writeThrough(key, CachedValue.Flt(value), KeyValuePair(key).put(value))
        } else {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putInt(key: String, value: Int) {
        if (cached) {
            writeThrough(key, CachedValue.Lng(value.toLong()), KeyValuePair(key).put(value.toLong()))
        } else {
            kvPairDao.put(KeyValuePair(key).put(value.toLong()))
        }
        fireChangeListener(key)
    }

    override fun putLong(key: String, value: Long) {
        if (cached) {
            writeThrough(key, CachedValue.Lng(value), KeyValuePair(key).put(value))
        } else {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putString(key: String, value: String?) = if (value == null) {
        remove(key)
    } else {
        if (cached) {
            writeThrough(key, CachedValue.Str(value), KeyValuePair(key).put(value))
        } else {
            kvPairDao.put(KeyValuePair(key).put(value))
        }
        fireChangeListener(key)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) = if (values == null) {
        remove(key)
    } else {
        if (cached) {
            // One defensive copy reused for both the cached snapshot value and the DB write, so
            // neither aliases the caller's mutable set.
            val copy = HashSet(values)
            writeThrough(key, CachedValue.StrSet(copy), KeyValuePair(key).put(copy))
        } else {
            kvPairDao.put(KeyValuePair(key).put(values))
        }
        fireChangeListener(key)
    }

    fun remove(key: String) {
        if (cached) {
            writeThrough(key, null, null)
        } else {
            kvPairDao.delete(key)
        }
        fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
