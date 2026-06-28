package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal object BackupFormatV2 {
    const val VERSION = 2

    fun encodeProfiles(profiles: List<ProxyEntity>): JSONArray = JSONArray().apply {
        profiles.forEach { put(encodeProfile(it)) }
    }

    fun decodeProfiles(array: JSONArray): List<ProxyEntity> = array.mapObjects(::decodeProfile)

    fun encodeGroups(groups: List<ProxyGroup>): JSONArray = JSONArray().apply {
        groups.forEach { put(encodeGroup(it)) }
    }

    fun decodeGroups(array: JSONArray): List<ProxyGroup> = array.mapObjects(::decodeGroup)

    fun encodeRules(rules: List<RuleEntity>): JSONArray = JSONArray().apply {
        rules.forEach { put(encodeRule(it)) }
    }

    fun decodeRules(array: JSONArray): List<RuleEntity> = array.mapObjects(::decodeRule)

    fun encodeSettings(settings: List<KeyValuePair>): JSONArray = JSONArray().apply {
        settings.forEach { put(encodeSetting(it)) }
    }

    fun decodeSettings(array: JSONArray): List<KeyValuePair> = array.mapObjects(::decodeSetting)

    fun encodeProfile(profile: ProxyEntity): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("groupId", profile.groupId)
        put("type", profile.type)
        put("userOrder", profile.userOrder)
        put("tx", profile.tx)
        put("rx", profile.rx)
        put("status", profile.status)
        put("ping", profile.ping)
        put("uuid", profile.uuid)
        putNullable("error", profile.error)
        put("dirty", profile.dirty)
        put("bean", encodeBytes(KryoConverters.serialize(profile.requireBean())))
    }

    fun decodeProfile(json: JSONObject): ProxyEntity = ProxyEntity(
        id = json.getLong("id"),
        groupId = json.getLong("groupId"),
        type = json.getInt("type"),
        userOrder = json.getLong("userOrder"),
        tx = json.getLong("tx"),
        rx = json.getLong("rx"),
        status = json.getInt("status"),
        ping = json.getInt("ping"),
        uuid = json.getString("uuid"),
        error = json.optNullableString("error"),
    ).apply {
        dirty = json.optBoolean("dirty", false)
        putByteArray(decodeBytes(json.getString("bean")))
    }

    fun encodeGroup(group: ProxyGroup): JSONObject = JSONObject().apply {
        put("id", group.id)
        put("userOrder", group.userOrder)
        put("ungrouped", group.ungrouped)
        putNullable("name", group.name)
        put("type", group.type)
        put("order", group.order)
        put("isSelector", group.isSelector)
        put("frontProxy", group.frontProxy)
        put("landingProxy", group.landingProxy)
        putNullable("subscription", group.subscription?.let { encodeBytes(KryoConverters.serialize(it)) })
    }

    fun decodeGroup(json: JSONObject): ProxyGroup = ProxyGroup(
        id = json.getLong("id"),
        userOrder = json.getLong("userOrder"),
        ungrouped = json.getBoolean("ungrouped"),
        name = json.optNullableString("name"),
        type = json.getInt("type"),
        subscription = json.optNullableString("subscription")?.let {
            KryoConverters.subscriptionDeserialize(decodeBytes(it))
        },
        order = json.getInt("order"),
        isSelector = json.optBoolean("isSelector", false),
        frontProxy = json.optLong("frontProxy", -1L),
        landingProxy = json.optLong("landingProxy", -1L),
    )

    fun encodeRule(rule: RuleEntity): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("config", rule.config)
        put("userOrder", rule.userOrder)
        put("enabled", rule.enabled)
        put("domains", rule.domains)
        put("ip", rule.ip)
        put("port", rule.port)
        put("sourcePort", rule.sourcePort)
        put("network", rule.network)
        put("source", rule.source)
        put("protocol", rule.protocol)
        put("ruleset", rule.ruleset)
        put("outbound", rule.outbound)
        put("packages", JSONArray().apply { rule.packages.sorted().forEach { put(it) } })
    }

    fun decodeRule(json: JSONObject): RuleEntity = RuleEntity(
        id = json.getLong("id"),
        name = json.getString("name"),
        config = json.getString("config"),
        userOrder = json.getLong("userOrder"),
        enabled = json.getBoolean("enabled"),
        domains = json.getString("domains"),
        ip = json.getString("ip"),
        port = json.getString("port"),
        sourcePort = json.getString("sourcePort"),
        network = json.getString("network"),
        source = json.getString("source"),
        protocol = json.getString("protocol"),
        ruleset = json.getString("ruleset"),
        outbound = json.getLong("outbound"),
        packages = json.getJSONArray("packages").mapStrings().toSet(),
    )

    fun encodeSetting(setting: KeyValuePair): JSONObject = JSONObject().apply {
        put("key", setting.key)
        put("valueType", setting.valueType)
        put("value", encodeBytes(setting.value))
    }

    fun decodeSetting(json: JSONObject): KeyValuePair = KeyValuePair().apply {
        key = json.getString("key")
        valueType = json.getInt("valueType")
        value = decodeBytes(json.getString("value"))
    }

    private fun encodeBytes(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeBytes(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (!has(name) || isNull(name)) null else getString(name)
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        return (0 until length()).map { index -> transform(getJSONObject(index)) }
    }

    private fun JSONArray.mapStrings(): List<String> {
        return (0 until length()).map { index -> getString(index) }
    }
}
