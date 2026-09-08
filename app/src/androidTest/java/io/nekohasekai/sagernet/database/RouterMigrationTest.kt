package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouterMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesVersion8WithoutChangingLegacyRowsAndLeavesRouterStateEmpty() {
        migrationHelper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                "INSERT INTO proxy_groups " +
                    "(id, userOrder, ungrouped, name, type, subscription, `order`, isSelector, frontProxy, landingProxy) " +
                    "VALUES (1, 5, 0, 'legacy group', 0, NULL, 0, 0, -1, -1)"
            )
            execSQL(
                "INSERT INTO proxy_entities " +
                    "(id, groupId, type, userOrder, tx, rx, status, ping, uuid) " +
                    "VALUES (2, 1, 0, 7, 11, 13, 0, 42, 'legacy-proxy')"
            )
            execSQL(
                "INSERT INTO rules " +
                    "(id, name, userOrder, enabled, domains, ip, port, sourcePort, network, source, protocol, outbound, packages) " +
                    "VALUES (3, 'legacy rule', 9, 1, 'example.com', '', '', '', '', '', '', 2, '')"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            SagerDatabase_AutoMigration_8_9_Impl()
        ).use { database ->
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM proxy_groups"))
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM proxy_entities"))
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM rules"))
            assertEquals(1L, database.singleLong("SELECT id FROM proxy_groups WHERE name = 'legacy group'"))
            assertEquals(2L, database.singleLong("SELECT id FROM proxy_entities WHERE uuid = 'legacy-proxy'"))
            assertEquals(3L, database.singleLong("SELECT id FROM rules WHERE name = 'legacy rule'"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM router_groups"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM router_members"))
        }
    }

    @Test
    fun migratesVersion9WithoutCreatingGroupsOrChangingLegacyRoutes() {
        migrationHelper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO rules " +
                    "(id, name, userOrder, enabled, domains, ip, port, sourcePort, network, source, protocol, outbound, packages, config, ruleset) " +
                    "VALUES (1, 'legacy', 0, 1, '', '', '', '', '', '', '', -1, '', '', '')"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DB,
            10,
            true,
            SagerDatabase_AutoMigration_9_10_Impl()
        ).use { database ->
            assertEquals(0L, database.singleLong("SELECT routerGroupId FROM rules WHERE id = 1"))
            assertEquals(-1L, database.singleLong("SELECT outbound FROM rules WHERE id = 1"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM router_groups"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM router_group_sources"))
        }
    }

    @Test
    fun sourcesAndMembersCanBeSharedAcrossRouters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val routerA = database.routerGroupDao().create(RouterGroup(stableTag = "router.a"))
            val routerB = database.routerGroupDao().create(RouterGroup(stableTag = "router.b"))
            database.routerGroupSourceDao().insert(
                listOf(RouterGroupSource(routerA, 10), RouterGroupSource(routerB, 10))
            )
            database.routerMemberDao().insert(
                listOf(RouterMember(routerA, 20), RouterMember(routerB, 20))
            )

            assertEquals(
                listOf(routerA, routerB),
                database.routerGroupSourceDao().routersForSource(10).map { it.routerId }
            )
            assertEquals(listOf(20L), database.routerMemberDao().getByRouter(routerA).map { it.proxyId })
            assertEquals(listOf(20L), database.routerMemberDao().getByRouter(routerB).map { it.proxyId })
        } finally {
            database.close()
        }
    }

    @Test
    fun memberDaoOrdersReplacesAndCleansMembersByProxy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val routerId = database.routerGroupDao().create(RouterGroup(stableTag = "router.us"))
            val members = database.routerMemberDao()

            members.replaceMembers(
                routerId,
                listOf(
                    RouterMember(proxyId = 30, userOrder = 2),
                    RouterMember(proxyId = 10, userOrder = 1)
                )
            )
            assertEquals(listOf(10L, 30L), members.getByRouter(routerId).map { it.proxyId })

            assertEquals(1, members.deleteByProxy(10))
            assertEquals(listOf(30L), members.getByRouter(routerId).map { it.proxyId })

            members.replaceMembers(routerId, listOf(RouterMember(proxyId = 50, userOrder = 0)))
            assertEquals(listOf(50L), members.getByRouter(routerId).map { it.proxyId })
        } finally {
            database.close()
        }
    }

    private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one row for query: $sql" }
        cursor.getLong(0)
    }

    @Test
    fun clearsDanglingSelectionsIncludingDisabledRoutersWithoutChangingValidSelections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO proxy_entities " +
                    "(id, groupId, type, userOrder, tx, rx, status, ping, uuid) " +
                    "VALUES (20, 10, 0, 0, 0, 0, 0, 0, 'node')"
            )
            val routers = database.routerGroupDao()
            val valid = routers.create(RouterGroup(
                stableTag = "router.valid", selectedProxyId = 20, selectedNodeKey = "10:node",
            ))
            val deleted = routers.create(RouterGroup(
                stableTag = "router.deleted", enabled = false,
                selectedProxyId = 30, selectedNodeKey = "10:deleted",
            ))
            val nonMember = routers.create(RouterGroup(
                stableTag = "router.nonmember", selectedProxyId = 20, selectedNodeKey = "10:node",
            ))
            database.routerMemberDao().insert(listOf(RouterMember(valid, 20), RouterMember(deleted, 30)))

            assertEquals(2, routers.clearInvalidSelections())
            assertEquals(20L, routers.getById(valid)!!.selectedProxyId)
            assertEquals("10:node", routers.getById(valid)!!.selectedNodeKey)
            for (id in listOf(deleted, nonMember)) {
                assertEquals(RouterGroup.NO_SELECTION, routers.getById(id)!!.selectedProxyId)
                assertEquals("", routers.getById(id)!!.selectedNodeKey)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "router-migration-test"
    }
}
