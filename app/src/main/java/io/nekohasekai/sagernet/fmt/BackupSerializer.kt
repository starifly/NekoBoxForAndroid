package io.nekohasekai.sagernet.fmt

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.routerStableId
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/** The Parcelable-backed JSON array format used by NekoBox backups. */
object BackupSerializer {

    const val BACKUP_VERSION = 3

    data class RouterRuleReference(
        val ruleId: Long,
        val routerGroupId: Long,
        val routerStableTag: String? = null,
    )

    /** Capture related rows together; exporting must never repair or delete user data. */
    fun exportDatabase(database: SagerDatabase, profiles: Boolean, rules: Boolean): JSONObject {
        val json = JSONObject().put("version", BACKUP_VERSION)
        database.runInTransaction {
            if (profiles) {
                val allProfiles = database.proxyDao().getAll()
                allProfiles.forEach { it.requireBean() }
                putParcelableArray(json, "profiles", allProfiles)
                putParcelableArray(json, "groups", database.groupDao().allGroups())
                putParcelableArray(json, "routerGroups", database.routerGroupDao().all())
                putParcelableArray(json, "routerMembers", database.routerMemberDao().all())
                putParcelableArray(json, "routerSources", database.routerGroupSourceDao().all())
            }
            if (rules) {
                val allRules = database.rulesDao().allRules()
                val allRouters = database.routerGroupDao().all()
                putParcelableArray(json, "rules", allRules)
                putRouterRuleReferences(json, allRules, allRouters)
                if (!profiles) {
                    // When exporting rules without profiles, the import side cannot reconstruct
                    // backupProxies from the backup. Attach a stable-identity index so the importer
                    // can verify that each rule's outbound proxy still refers to the same node.
                    putRuleOutboundRefs(json, allRules, database)
                }
            }
        }
        return json
    }

    fun putRouterRuleReferences(
        json: JSONObject,
        rules: Iterable<RuleEntity>,
        routers: Iterable<RouterGroup> = emptyList(),
    ) {
        val routerTagMap = routers.associate { it.id to it.stableTag }
        json.put("routerRuleRefs", JSONArray().apply {
            rules.filter { it.routerGroupId > 0L }.forEach { rule ->
                put(JSONObject().apply {
                    put("ruleId", rule.id)
                    put("routerGroupId", rule.routerGroupId)
                    routerTagMap[rule.routerGroupId]?.takeIf { it.isNotBlank() }?.let {
                        put("routerStableTag", it)
                    }
                })
            }
        })
    }

    /**
     * Emits a `ruleOutboundRefs` section mapping each rule's legacy outbound proxy ID to the
     * node's stable identity string. Used when exporting rules without profiles so the import
     * side can verify node identity without needing the full profiles section.
     */
    fun putRuleOutboundRefs(
        json: JSONObject,
        rules: Iterable<RuleEntity>,
        database: SagerDatabase,
    ) {
        val outboundIds = rules.mapNotNull { r -> r.outbound.takeIf { it > 0L } }.toSet()
        if (outboundIds.isEmpty()) return
        val stableIdMap = outboundIds.associateWith { proxyId ->
            database.proxyDao().getById(proxyId)?.let { proxy ->
                runCatching { proxy.requireBean() }.getOrNull()
                    ?.let { proxy.routerStableId() }
            }
        }
        json.put("ruleOutboundRefs", JSONArray().apply {
            rules.filter { it.outbound > 0L }.forEach { rule ->
                val stableId = stableIdMap[rule.outbound] ?: return@forEach
                put(JSONObject().apply {
                    put("ruleId", rule.id)
                    put("outbound", rule.outbound)
                    put("stableId", stableId)
                })
            }
        })
    }

    /** Returns a map from rule ID to outbound stable ID, read from `ruleOutboundRefs`. */
    fun getRuleOutboundStableIds(json: JSONObject): Map<Long, String> {
        if (!json.has("ruleOutboundRefs")) return emptyMap()
        require(!json.isNull("ruleOutboundRefs")) { "Section 'ruleOutboundRefs' in backup cannot be null" }
        val values = json.optJSONArray("ruleOutboundRefs")
            ?: throw IllegalArgumentException("Section 'ruleOutboundRefs' in backup must be a JSON array")
        val result = HashMap<Long, String>()
        for (i in 0 until values.length()) {
            val obj = values.getJSONObject(i)
            val ruleId = obj.getLong("ruleId")
            val stableId = obj.optString("stableId").takeIf { it.isNotBlank() } ?: continue
            result[ruleId] = stableId
        }
        return result
    }

    fun validateRuleReferences(
        rules: Collection<RuleEntity>,
        routerIds: Set<Long>,
        proxyIds: Set<Long>
    ) {
        for (rule in rules) {
            if (rule.routerGroupId > 0L) {
                require(rule.routerGroupId in routerIds) {
                    "Rule ${rule.id} references missing router group ${rule.routerGroupId}"
                }
            } else if (rule.outbound > 0L) {
                require(rule.outbound in proxyIds) {
                    "Rule ${rule.id} references missing profile ${rule.outbound}"
                }
            }
        }
    }

    fun getRouterRuleReferenceList(json: JSONObject): List<RouterRuleReference> {
        if (!json.has("routerRuleRefs")) {
            return emptyList()
        }
        require(!json.isNull("routerRuleRefs")) { "Section 'routerRuleRefs' in backup cannot be null" }
        val values = json.optJSONArray("routerRuleRefs")
            ?: throw IllegalArgumentException("Section 'routerRuleRefs' in backup must be a JSON array")
        val list = ArrayList<RouterRuleReference>(values.length())
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            val ruleId = value.getLong("ruleId")
            val routerGroupId = value.getLong("routerGroupId")
            val routerStableTag = value.optString("routerStableTag").takeIf { it.isNotBlank() }
            if (ruleId > 0L && routerGroupId > 0L) {
                list.add(RouterRuleReference(ruleId, routerGroupId, routerStableTag))
            }
        }
        return list
    }

    fun getRouterRuleReferences(json: JSONObject): Map<Long, Long> {
        return getRouterRuleReferenceList(json).associate { it.ruleId to it.routerGroupId }
    }

    fun putParcelableArray(
        json: JSONObject,
        key: String,
        values: Iterable<out Parcelable>
    ) {
        json.put(key, JSONArray().apply {
            values.forEach { put(encode(it)) }
        })
    }

    fun <T : Parcelable> getParcelableArray(
        json: JSONObject,
        key: String,
        creator: Parcelable.Creator<T>
    ): List<T> {
        if (!json.has(key)) return emptyList()
        require(!json.isNull(key)) { "Section '$key' in backup cannot be null" }
        val values = json.optJSONArray(key)
            ?: throw IllegalArgumentException("Section '$key' in backup must be a JSON array")
        return (0 until values.length()).map { index ->
            decode(values.getString(index), creator)
        }
    }

    fun <T> getParcelableArray(
        json: JSONObject,
        key: String,
        decoder: (Parcel) -> T
    ): List<T> {
        if (!json.has(key)) return emptyList()
        require(!json.isNull(key)) { "Section '$key' in backup cannot be null" }
        val values = json.optJSONArray(key)
            ?: throw IllegalArgumentException("Section '$key' in backup must be a JSON array")
        return (0 until values.length()).map { index ->
            val data = Util.b64Decode(values.getString(index))
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                decoder(parcel)
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun encode(value: Parcelable): String {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun <T : Parcelable> decode(
        encoded: String,
        creator: Parcelable.Creator<T>
    ): T {
        val data = Util.b64Decode(encoded)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
