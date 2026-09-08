package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterMember
import io.nekohasekai.sagernet.database.RouterGroupSource
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.BackupSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import org.junit.Assert.assertThrows

class BackupSerializationTest {

    @Test
    fun exportOfDamagedProfileFailsWithoutDeletingProfilesMembersOrRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).build()
        try {
            database.groupDao().insert(listOf(ProxyGroup(id = 10, ungrouped = true)))
            database.proxyDao().insert(listOf(ProxyEntity(id = 20, groupId = 10)))
            database.routerGroupDao().insert(listOf(RouterGroup(id = 30, stableTag = "router.test")))
            database.routerMemberDao().insert(listOf(RouterMember(30, 20)))
            database.rulesDao().insert(listOf(RuleEntity(id = 40, routerGroupId = 30)))
            assertThrows(IllegalStateException::class.java) {
                BackupSerializer.exportDatabase(database, true, true)
            }
            assertEquals(listOf(20L), database.proxyDao().getAll().map { it.id })
            assertEquals(listOf(20L), database.routerMemberDao().all().map { it.proxyId })
            assertEquals(30L, database.rulesDao().allRules().single().routerGroupId)
        } finally {
            database.close()
        }
    }

    @Test
    fun routerGroupsMembersSourcesAndRuleReferencesSurviveBackupRoundTrip() {
        val group = RouterGroup(
            id = 42L,
            stableTag = "router.us",
            name = "US",
            mode = RouterGroup.MODE_URL_TEST,
            enabled = true,
            matchConfig = "{\"regions\":[\"US\"]}",
            selectedProxyId = 7L,
            userOrder = 3L
        )
        val member = RouterMember(
            routerId = group.id,
            proxyId = group.selectedProxyId,
            userOrder = 1L,
            lastMatchedAt = 123456789L
        )
        val source = RouterGroupSource(group.id, 99L, 2L)
        val rule = RuleEntity(id = 8L, name = "AI", routerGroupId = group.id)

        val backup = JSONObject().apply {
            BackupSerializer.putParcelableArray(this, "routerGroups", listOf(group))
            BackupSerializer.putParcelableArray(this, "routerMembers", listOf(member))
            BackupSerializer.putParcelableArray(this, "routerSources", listOf(source))
            BackupSerializer.putRouterRuleReferences(this, listOf(rule))
        }

        assertEquals(
            listOf(group),
            BackupSerializer.getParcelableArray(backup, "routerGroups", RouterGroup.CREATOR)
        )
        assertEquals(
            listOf(member),
            BackupSerializer.getParcelableArray(backup, "routerMembers", RouterMember.CREATOR)
        )
        assertEquals(
            listOf(source),
            BackupSerializer.getParcelableArray(backup, "routerSources", RouterGroupSource.CREATOR)
        )
        assertEquals(mapOf(rule.id to group.id), BackupSerializer.getRouterRuleReferences(backup))
    }

    @Test
    fun legacyBackupWithoutRouterArraysIsAcceptedAndLeavesLegacySectionsUntouched() {
        val legacyRules = JSONArray().put("legacy-adblock").put("legacy.invalid")
        val legacySettings = JSONArray().put("base-setting")
        val backup = JSONObject().apply {
            put("version", 1)
            put("profiles", JSONArray())
            put("groups", JSONArray())
            put("rules", legacyRules)
            put("settings", legacySettings)
        }

        assertFalse(backup.has("routerGroups"))
        assertFalse(backup.has("routerMembers"))
        assertTrue(BackupSerializer.getRouterRuleReferences(backup).isEmpty())
        assertTrue(
            BackupSerializer.getParcelableArray(
                backup,
                "routerGroups",
                RouterGroup.CREATOR
            ).isEmpty()
        )
        assertEquals(legacyRules.toString(), backup.getJSONArray("rules").toString())
        assertEquals(legacySettings.toString(), backup.getJSONArray("settings").toString())
    }

    @Test
    fun versionThreeRoundTripsRelationsAndVersionTwoDefaultsThem() {
        val json = JSONObject().put("version", 3)
        BackupSerializer.putParcelableArray(json, "routerSources", listOf(RouterGroupSource(1, 10), RouterGroupSource(2, 10)))
        assertEquals(
            listOf(RouterGroupSource(1, 10), RouterGroupSource(2, 10)),
            BackupSerializer.getParcelableArray(json, "routerSources", RouterGroupSource.CREATOR),
        )
        val old = JSONObject().put("version", 2)
        assertTrue(BackupSerializer.getParcelableArray(old, "routerSources", RouterGroupSource.CREATOR).isEmpty())
    }
}
