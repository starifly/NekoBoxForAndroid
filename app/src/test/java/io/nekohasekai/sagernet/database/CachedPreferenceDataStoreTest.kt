package io.nekohasekai.sagernet.database

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Pure-JVM tests for [RoomPreferenceDataStore] in its cached (snapshot) mode — the bridge that
 * lets `PublicDatabase` drop `allowMainThreadQueries()`. Uses a fake in-memory [KeyValuePair.Dao]
 * and a direct (synchronous) executor so prime/refresh/write-through are deterministic without
 * Room or a real thread pool.
 */
class CachedPreferenceDataStoreTest {

    /** Minimal in-memory KeyValuePair.Dao. Models the SQLite row store the cache sits over. */
    private class FakeDao : KeyValuePair.Dao {
        val rows = LinkedHashMap<String, KeyValuePair>()

        override fun all(): List<KeyValuePair> = rows.values.map { it.deepCopy() }
        override fun get(key: String): KeyValuePair? = rows[key]?.deepCopy()
        override fun put(value: KeyValuePair): Long {
            rows[value.key] = value.deepCopy()
            return 1
        }
        override fun delete(key: String): Int = if (rows.remove(key) != null) 1 else 0
        override fun reset(): Int {
            val n = rows.size
            rows.clear()
            return n
        }
        override fun insert(list: List<KeyValuePair>) {
            list.forEach { rows[it.key] = it.deepCopy() }
        }
    }

    /** Runs submitted tasks immediately on the calling thread (deterministic, ordered). */
    private val directExecutor = Executor { it.run() }

    private fun newStore(dao: FakeDao) =
        RoomPreferenceDataStore(dao, cached = true, database = null, diskExecutor = directExecutor)

    @Test
    fun prime_seedsReadsFromDb() {
        val dao = FakeDao().apply {
            put(KeyValuePair("s").put("hello"))
            put(KeyValuePair("n").put(42L))
            put(KeyValuePair("b").put(true))
        }
        val store = newStore(dao)
        store.prime()

        assertEquals("hello", store.getString("s", null))
        assertEquals(42L, store.getLong("n", -1L))
        assertTrue(store.getBoolean("b", false))
        assertEquals("def", store.getString("missing", "def"))
    }

    @Test
    fun write_immediateReadAfterWrite_andPersisted() {
        val dao = FakeDao()
        val store = newStore(dao)
        store.prime()

        store.putString("k", "v1")
        // read-after-write served from the snapshot
        assertEquals("v1", store.getString("k", null))
        // and the DB write went through (direct executor => already committed)
        assertEquals("v1", dao.get("k")?.string)

        store.putLong("num", 7L)
        assertEquals(7L, store.getLong("num", -1L))
    }

    @Test
    fun remove_returnsDefault() {
        val dao = FakeDao().apply { put(KeyValuePair("k").put("v")) }
        val store = newStore(dao)
        store.prime()
        assertEquals("v", store.getString("k", null))

        store.remove("k")
        assertNull(store.getString("k", null))
        assertNull(dao.get("k"))
    }

    @Test
    fun listener_firesSynchronouslyOnWriteAndRemove() {
        val dao = FakeDao()
        val store = newStore(dao)
        store.prime()

        val fired = ArrayList<String>()
        store.registerChangeListener(object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                fired += key
            }
        })

        store.putString("a", "1")
        store.remove("a")
        assertEquals(listOf("a", "a"), fired)
    }

    @Test
    fun refresh_swapsInExternalChanges() {
        val dao = FakeDao().apply { put(KeyValuePair("k").put("old")) }
        val store = newStore(dao)
        store.prime()
        assertEquals("old", store.getString("k", null))

        // simulate another process committing directly to the DB (bypassing the cache)
        dao.put(KeyValuePair("k").put("new"))
        dao.put(KeyValuePair("added").put("x"))
        // cache is stale until a refresh
        assertEquals("old", store.getString("k", null))

        store.refreshBlocking()
        assertEquals("new", store.getString("k", null))
        assertEquals("x", store.getString("added", null))
    }

    @Test
    fun stringSet_isDefensivelyCopied() {
        val dao = FakeDao()
        val store = newStore(dao)
        store.prime()

        val input = mutableSetOf("a", "b")
        store.putStringSet("set", input)
        // mutating the caller's set must not affect the stored value
        input.add("c")
        assertEquals(setOf("a", "b"), store.getStringSet("set", null))

        // mutating the returned set must not affect subsequent reads
        val out = store.getStringSet("set", null)!!.toMutableSet()
        out.clear()
        assertEquals(setOf("a", "b"), store.getStringSet("set", null))
    }

    @Test
    fun reset_clearsCacheAndDb() {
        val dao = FakeDao().apply { put(KeyValuePair("k").put("v")) }
        val store = newStore(dao)
        store.prime()
        assertEquals("v", store.getString("k", null))

        store.reset()
        assertNull(store.getString("k", null))
        // a subsequent refresh must not resurrect the cleared row
        store.refreshBlocking()
        assertNull(store.getString("k", null))
    }

    @Test
    fun awaitWrites_surfacesWriteThroughFailure() {
        // A DAO whose put() throws simulates a failed DB commit on the disk worker.
        val failingDao = object : KeyValuePair.Dao {
            override fun all(): List<KeyValuePair> = emptyList()
            override fun get(key: String): KeyValuePair? = null
            override fun put(value: KeyValuePair): Long = throw IllegalStateException("disk full")
            override fun delete(key: String): Int = 0
            override fun reset(): Int = 0
            override fun insert(list: List<KeyValuePair>) {}
        }
        val store =
            RoomPreferenceDataStore(failingDao, cached = true, database = null, diskExecutor = directExecutor)
        store.prime()

        store.putString("k", "v") // snapshot updated; persist() fails on the worker and records it
        assertEquals("v", store.getString("k", null)) // read-after-write still works from snapshot
        // awaitWrites() must surface the recorded write-through failure rather than report success.
        assertThrows(IllegalStateException::class.java) { runBlocking { store.awaitWrites() } }
    }

    @Test
    fun awaitWrites_failedWriteNotMaskedByLaterUnrelatedSuccess() {
        // DAO that fails to persist key "bad" but succeeds for every other key.
        val dao = object : KeyValuePair.Dao {
            val rows = LinkedHashMap<String, KeyValuePair>()
            override fun all(): List<KeyValuePair> = rows.values.map { it.deepCopy() }
            override fun get(key: String): KeyValuePair? = rows[key]?.deepCopy()
            override fun put(value: KeyValuePair): Long {
                if (value.key == "bad") throw IllegalStateException("disk full for bad")
                rows[value.key] = value.deepCopy()
                return 1
            }
            override fun delete(key: String): Int = if (rows.remove(key) != null) 1 else 0
            override fun reset(): Int {
                rows.clear()
                return 0
            }
            override fun insert(list: List<KeyValuePair>) {
                list.forEach { rows[it.key] = it.deepCopy() }
            }
        }
        val store =
            RoomPreferenceDataStore(dao, cached = true, database = null, diskExecutor = directExecutor)
        store.prime()

        store.putString("bad", "v") // fails to persist; pending overlay retained
        store.putString("good", "ok") // later UNRELATED successful write
        // The unrelated write succeeded and is durable in the fake DB...
        assertEquals("ok", dao.get("good")?.string)
        assertEquals("ok", store.getString("good", null))
        // ...but awaitWrites() must STILL fail: the "bad" write never reached disk (its pending
        // overlay entry remains), so a reload would re-read a stale DB. The leftover-pending check
        // is authoritative even though the later successful write cleared the best-effort cause
        // flag, so a generic durability failure is thrown rather than success being reported.
        assertThrows(Exception::class.java) { runBlocking { store.awaitWrites() } }
    }

    @Test
    fun uncachedMode_passesThroughToDao() {
        val dao = FakeDao()
        val store = RoomPreferenceDataStore(dao) // cached = false
        store.putString("k", "v")
        assertEquals("v", dao.get("k")?.string)
        assertEquals("v", store.getString("k", null))
        // absent key returns the caller-provided default (use a non-falsy default so the
        // assertion fails if the store ignored it)
        assertTrue(store.getBoolean("absent", true))
        assertFalse(store.getBoolean("absent", false))
    }
}

// Deep copy so the cache can never alias a mutable row/ByteArray held by the fake store.
private fun KeyValuePair.deepCopy(): KeyValuePair = KeyValuePair(key).also {
    it.valueType = valueType
    it.value = value.copyOf()
}
