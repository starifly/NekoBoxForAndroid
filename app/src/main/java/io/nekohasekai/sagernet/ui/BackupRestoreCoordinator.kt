package io.nekohasekai.sagernet.ui

import android.os.Parcel
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ParcelizeBridge
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

internal interface BackupRestoreOperations {
    suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>)

    suspend fun replaceRules(rules: List<RuleEntity>)

    suspend fun replaceSettings(settings: List<KeyValuePair>)
}

internal object DatabaseBackupRestoreOperations : BackupRestoreOperations {
    override suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>) {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
        }
    }

    override suspend fun replaceRules(rules: List<RuleEntity>) {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
    }

    override suspend fun replaceSettings(settings: List<KeyValuePair>) {
        // Drain earlier write-through work, then replace settings through the store's ordered
        // durable path. This keeps approval merges and restore on one serialization boundary.
        DataStore.configurationStore.awaitWrites()
        DataStore.configurationStore.replaceAllDurable(settings)
    }
}

internal suspend fun restoreBackup(
    content: JSONObject,
    profile: Boolean,
    rule: Boolean,
    setting: Boolean,
    operations: BackupRestoreOperations,
) {
    // Validate-then-commit: decode every selected section before the first destructive write.
    val version = content.optInt("version", 1)
    if (version != 1 && version != BackupFormatV2.VERSION) {
        error("Unsupported backup version: $version")
    }

    val importConfigs = profile && content.has("profiles")
    val profiles = if (importConfigs) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeProfiles(content.getJSONArray("profiles"))
            else -> decodeArray(content.getJSONArray("profiles")) { ProxyEntity.CREATOR.createFromParcel(it) }
        }
    } else {
        null
    }
    val groups = if (importConfigs) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeGroups(content.getJSONArray("groups"))
            else -> decodeArray(content.getJSONArray("groups")) { ProxyGroup.CREATOR.createFromParcel(it) }
        }
    } else {
        null
    }
    val rules = if (rule && content.has("rules")) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeRules(content.getJSONArray("rules"))
            else -> decodeArray(content.getJSONArray("rules")) { ParcelizeBridge.createRule(it) }
        }
    } else {
        null
    }
    val settings = if (setting && content.has("settings")) {
        // Local plugin signer approvals are never accepted from imported material.
        BackupFormatV2.sanitizeSettings(
            when (version) {
                BackupFormatV2.VERSION -> BackupFormatV2.decodeSettings(content.getJSONArray("settings"))
                else -> decodeArray(content.getJSONArray("settings")) {
                    KeyValuePair.CREATOR.createFromParcel(it)
                }
            },
        )
    } else {
        null
    }

    if (profiles != null && groups != null) {
        operations.replaceProfiles(profiles, groups)
    }
    rules?.let { operations.replaceRules(it) }
    settings?.let { operations.replaceSettings(it) }
}

/**
 * Decodes each legacy Parcel entry independently so every Parcel is recycled even when an entry
 * is malformed. Version 1 is the only caller; version 2 uses the explicit JSON schema.
 */
private fun <T> decodeArray(array: JSONArray, create: (Parcel) -> T): List<T> {
    val out = ArrayList<T>(array.length())
    for (i in 0 until array.length()) {
        val data = Util.b64Decode(array[i] as String)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            out.add(create(parcel))
        } finally {
            parcel.recycle()
        }
    }
    return out
}
