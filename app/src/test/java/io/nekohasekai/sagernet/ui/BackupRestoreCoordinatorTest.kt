package io.nekohasekai.sagernet.ui

import android.app.Application
import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.test.runTest
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRestoreCoordinatorTest {

    @Test
    fun allSelected_commitsProfilesRulesSettingsInOrder() = runTest {
        val operations = FakeOperations()

        restoreBackup(backup(BackupFormatV2.VERSION), true, true, true, operations)

        assertEquals(listOf("profiles", "rules", "settings"), operations.calls)
        assertEquals(listOf(10L), operations.profiles.map { it.id })
        assertEquals(listOf(20L), operations.groups.map { it.id })
        assertEquals(listOf("synthetic-rule"), operations.rules.map { it.name })
        assertEquals(listOf("ordinary-setting"), operations.settings.map { it.key })
        assertEquals("synthetic-value", operations.settings.single().string)
    }

    @Test
    fun unselectedMalformedRules_areNotDecodedOrCommittedAndRemainUntouched() = runTest {
        val operations = FakeOperations()
        val initialRules = operations.rules
        val content = backup(BackupFormatV2.VERSION).apply {
            put("rules", JSONArray().put(JSONObject()))
        }

        restoreBackup(content, true, false, false, operations)

        assertEquals(listOf("profiles"), operations.calls)
        assertEquals(listOf(10L), operations.profiles.map { it.id })
        assertEquals(initialRules, operations.rules)
        assertEquals(listOf("existing-setting"), operations.settings.map { it.key })
    }

    @Test
    fun profilesFailure_preventsRulesAndSettingsAndLeavesFakeStateUntouched() = runTest {
        val operations = FakeOperations(failAt = "profiles")
        val initialState = operations.snapshot()

        val failure = restoreFailure(operations)

        assertNotNull(failure)
        assertEquals(listOf("profiles"), operations.calls)
        assertEquals(initialState, operations.snapshot())
    }

    @Test
    fun rulesFailure_occursAfterProfilesCommitAndPreventsSettings() = runTest {
        val operations = FakeOperations(failAt = "rules")
        val initialRules = operations.rules
        val initialSettings = operations.settings

        val failure = restoreFailure(operations)

        assertNotNull(failure)
        assertEquals(listOf("profiles", "rules"), operations.calls)
        assertEquals(listOf(10L), operations.profiles.map { it.id })
        assertEquals(listOf(20L), operations.groups.map { it.id })
        assertEquals(initialRules, operations.rules)
        assertEquals(initialSettings, operations.settings)
    }

    @Test
    fun settingsFailure_occursAfterProfilesAndRulesCommit() = runTest {
        val operations = FakeOperations(failAt = "settings")
        val initialSettings = operations.settings

        val failure = restoreFailure(operations)

        assertNotNull(failure)
        assertEquals(listOf("profiles", "rules", "settings"), operations.calls)
        assertEquals(listOf(10L), operations.profiles.map { it.id })
        assertEquals(listOf(20L), operations.groups.map { it.id })
        assertEquals(listOf("synthetic-rule"), operations.rules.map { it.name })
        assertEquals(initialSettings, operations.settings)
    }

    @Test
    fun malformedSelectedProfilesGroupsRulesOrSettings_causeZeroOperations() = runTest {
        for (section in listOf("profiles", "groups", "rules", "settings")) {
            val operations = FakeOperations()
            val initialState = operations.snapshot()
            val malformed = backup(BackupFormatV2.VERSION).apply {
                put(section, JSONArray().put(JSONObject()))
            }

            val failure = runCatching {
                restoreBackup(malformed, true, true, true, operations)
            }.exceptionOrNull()

            assertNotNull("Expected malformed $section to fail", failure)
            assertEquals("No operation may run for malformed $section", emptyList<String>(), operations.calls)
            assertEquals(initialState, operations.snapshot())
        }
    }

    @Test
    fun selectedProfilesWithoutGroups_failsBeforeAnyOperation() = runTest {
        val operations = FakeOperations()
        val initialState = operations.snapshot()
        val content = backup(BackupFormatV2.VERSION).apply { remove("groups") }

        val failure = runCatching {
            restoreBackup(content, true, false, false, operations)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(emptyList<String>(), operations.calls)
        assertEquals(initialState, operations.snapshot())
    }

    @Test
    fun settingsCommit_excludesPluginApprovalAndRetainsOrdinarySetting() = runTest {
        val operations = FakeOperations()
        val ordinary = KeyValuePair("ordinary-setting").put("synthetic-value")
        val approval = KeyValuePair(Key.PLUGIN_SIGNER_APPROVALS).put(setOf("synthetic-approval"))
        val content = JSONObject().apply {
            put("version", BackupFormatV2.VERSION)
            put(
                "settings",
                JSONArray().apply {
                    put(BackupFormatV2.encodeSetting(ordinary))
                    put(BackupFormatV2.encodeSetting(approval))
                },
            )
        }

        restoreBackup(content, false, false, true, operations)

        assertEquals(listOf("settings"), operations.calls)
        assertEquals(listOf("ordinary-setting"), operations.settings.map { it.key })
        assertEquals("synthetic-value", operations.settings.single().string)
    }

    @Test
    fun v1AndV2Inputs_produceEquivalentOperationCallsAndValues() = runTest {
        val v1Operations = FakeOperations()
        val v2Operations = FakeOperations()

        restoreBackup(backup(1), true, true, true, v1Operations)
        restoreBackup(backup(BackupFormatV2.VERSION), true, true, true, v2Operations)

        assertEquals(listOf("profiles", "rules", "settings"), v1Operations.calls)
        assertEquals(v1Operations.calls, v2Operations.calls)
        assertEquals(v1Operations.snapshot(), v2Operations.snapshot())
    }

    @Test
    fun noSectionSelected_performsNoDestructiveOperation() = runTest {
        val operations = FakeOperations()
        val initialState = operations.snapshot()
        val content = JSONObject().apply {
            put("version", BackupFormatV2.VERSION)
            put("profiles", JSONArray().put(JSONObject()))
            put("rules", JSONArray().put(JSONObject()))
            put("settings", JSONArray().put(JSONObject()))
        }

        restoreBackup(content, false, false, false, operations)

        assertEquals(emptyList<String>(), operations.calls)
        assertEquals(initialState, operations.snapshot())
    }

    private suspend fun restoreFailure(operations: FakeOperations) = runCatching {
        restoreBackup(backup(BackupFormatV2.VERSION), true, true, true, operations)
    }.exceptionOrNull()

    private fun backup(version: Int): JSONObject {
        val profiles = listOf(profile())
        val groups = listOf(group())
        val rules = listOf(rule())
        val settings = listOf(KeyValuePair("ordinary-setting").put("synthetic-value"))
        return JSONObject().apply {
            put("version", version)
            if (version == BackupFormatV2.VERSION) {
                put("profiles", BackupFormatV2.encodeProfiles(profiles))
                put("groups", BackupFormatV2.encodeGroups(groups))
                put("rules", BackupFormatV2.encodeRules(rules))
                put("settings", BackupFormatV2.encodeSettings(settings))
            } else {
                put("profiles", encodeParcels(profiles))
                put("groups", encodeParcels(groups))
                put("rules", encodeParcels(rules))
                put("settings", encodeParcels(settings))
            }
        }
    }

    private fun profile() = ProxyEntity(
        id = 10L,
        groupId = 20L,
        userOrder = 30L,
        tx = 40L,
        rx = 50L,
        status = 1,
        ping = 60,
        uuid = "synthetic-profile",
        error = "synthetic-error",
    ).apply {
        dirty = true
        putBean(
            SOCKSBean().apply {
                serverAddress = "192.0.2.10"
                serverPort = 1080
                username = "synthetic-user"
                password = "synthetic-password"
                name = "synthetic-profile"
                initializeDefaultValues()
            },
        )
    }

    private fun group() = ProxyGroup(
        id = 20L,
        userOrder = 2L,
        name = "synthetic-group",
    )

    private fun rule() = RuleEntity(
        id = 30L,
        name = "synthetic-rule",
        config = "synthetic-config",
        userOrder = 3L,
        enabled = true,
        domains = "example.invalid",
        ip = "192.0.2.0/24",
        port = "443",
        sourcePort = "1024:2048",
        network = "tcp",
        source = "198.51.100.1",
        protocol = "tls",
        ruleset = "synthetic-ruleset",
        outbound = -1L,
        packages = setOf("invalid.example.synthetic"),
    )

    private fun encodeParcels(values: List<Parcelable>) = JSONArray().apply {
        values.forEach { value ->
            val parcel = Parcel.obtain()
            try {
                value.writeToParcel(parcel, 0)
                put(Util.b64EncodeUrlSafe(parcel.marshall()))
            } finally {
                parcel.recycle()
            }
        }
    }

    private class FakeOperations(
        private val failAt: String? = null,
    ) : BackupRestoreOperations {
        val calls = mutableListOf<String>()
        var profiles = listOf(profile(id = 900L, groupId = 901L))
        var groups = listOf(ProxyGroup(id = 901L, name = "existing-group"))
        var rules = listOf(RuleEntity(id = 902L, name = "existing-rule"))
        var settings = listOf(KeyValuePair("existing-setting").put("existing-value"))

        override suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>) {
            calls += "profiles"
            failIfRequested("profiles")
            this.profiles = profiles
            this.groups = groups
        }

        override suspend fun replaceRules(rules: List<RuleEntity>) {
            calls += "rules"
            failIfRequested("rules")
            this.rules = rules
        }

        override suspend fun replaceSettings(settings: List<KeyValuePair>) {
            calls += "settings"
            failIfRequested("settings")
            this.settings = settings
        }

        fun snapshot() = listOf(
            profiles.map { profile ->
                val bean = profile.requireBean() as SOCKSBean
                listOf(
                    profile.id,
                    profile.groupId,
                    profile.type,
                    profile.userOrder,
                    profile.tx,
                    profile.rx,
                    profile.status,
                    profile.ping,
                    profile.uuid,
                    profile.error,
                    profile.dirty,
                    bean.serverAddress,
                    bean.serverPort,
                    bean.username,
                    bean.password,
                    bean.name,
                )
            },
            groups,
            rules,
            settings.map { setting ->
                listOf(setting.key, setting.valueType, setting.value.contentToString())
            },
        )

        private fun failIfRequested(operation: String) {
            if (failAt == operation) error("Synthetic $operation failure")
        }

        companion object {
            private fun profile(id: Long, groupId: Long) = ProxyEntity(id = id, groupId = groupId).apply {
                putBean(
                    SOCKSBean().apply {
                        serverAddress = "203.0.113.1"
                        serverPort = 1081
                        name = "existing-profile"
                        initializeDefaultValues()
                    },
                )
            }
        }
    }
}
