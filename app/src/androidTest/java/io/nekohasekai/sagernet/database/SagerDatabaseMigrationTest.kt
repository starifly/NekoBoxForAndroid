package io.nekohasekai.sagernet.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test proving the SagerDatabase 8 -> 9 migration PRESERVES user data
 * (it migrates the DB, it does NOT drop-and-recreate it).
 *
 * This is the guard against the data-loss landmine fixed in Plan 025: previously the DB was
 * declared version = 9 with no 8 -> 9 migration and a blanket fallbackToDestructiveMigration(),
 * so an upgrade from a v8 device silently wiped all saved proxy configs/groups/rules.
 *
 * MigrationTestHelper operates on its own throwaway test database (TEST_DB), so this test never
 * touches a real device's sager_net.db — safe to run on the physical device per AGENTS.md.
 */
@RunWith(AndroidJUnit4::class)
class SagerDatabaseMigrationTest {

    companion object {
        private const val TEST_DB = "migration-test-sager_net"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate8To9_preservesExistingRows() {
        // Create the DB at version 8 and insert a representative proxy entity.
        helper.createDatabase(TEST_DB, 8).use { db ->
            val values = ContentValues().apply {
                put("id", 1L)
                put("groupId", 1L)
                put("type", 0)
                put("userOrder", 0L)
                put("tx", 0L)
                put("rx", 0L)
                put("status", 0)
                put("ping", 0)
                put("uuid", "test-uuid-8to9")
            }
            db.insert("proxy_entities", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
        }

        // Run the auto-migration to version 9 (validateDroppedTables = true catches recreation).
        helper.runMigrationsAndValidate(TEST_DB, 9, true).use { db ->
            // The pre-existing row must survive (DB migrated, not recreated).
            db.query("SELECT id, uuid FROM proxy_entities").use { cursor ->
                assertEquals("row count after 8->9 migration", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals("test-uuid-8to9", cursor.getString(cursor.getColumnIndexOrThrow("uuid")))
            }

            // The two columns added in v9 must now exist and be writable as NULL.
            db.query("SELECT masterDnsVpnBean, awgBean FROM proxy_entities WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("masterDnsVpnBean is nullable", cursor.isNull(0))
                assertTrue("awgBean is nullable", cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate9To10_preservesExistingRows() {
        // Create the DB at version 9 and insert a representative proxy entity.
        helper.createDatabase(TEST_DB, 9).use { db ->
            val values = ContentValues().apply {
                put("id", 1L)
                put("groupId", 1L)
                put("type", 0)
                put("userOrder", 0L)
                put("tx", 0L)
                put("rx", 0L)
                put("status", 0)
                put("ping", 0)
                put("uuid", "test-uuid-9to10")
            }
            db.insert("proxy_entities", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
        }

        // Run the auto-migration to version 10 (validateDroppedTables = true catches recreation).
        helper.runMigrationsAndValidate(TEST_DB, 10, true).use { db ->
            db.query("SELECT id, uuid FROM proxy_entities").use { cursor ->
                assertEquals("row count after 9->10 migration", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals("test-uuid-9to10", cursor.getString(cursor.getColumnIndexOrThrow("uuid")))
            }

            // The column added in v10 must now exist and be writable as NULL.
            db.query("SELECT olcrtcBean FROM proxy_entities WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("olcrtcBean is nullable", cursor.isNull(0))
            }
        }
    }
}
