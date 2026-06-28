package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class BackupFormatV2Test {

    @Test
    fun profileRoundTrip_preservesTypeAndBean() {
        val profile = ProxyEntity(
            id = 10L,
            groupId = 20L,
            userOrder = 30L,
            tx = 40L,
            rx = 50L,
            status = 1,
            ping = 123,
            uuid = "profile-uuid",
            error = "last error",
        ).apply {
            putBean(
                SOCKSBean().apply {
                    serverAddress = "192.0.2.10"
                    serverPort = 1080
                    username = "user"
                    password = "pass"
                    protocol = 2
                    name = "socks node"
                    initializeDefaultValues()
                },
            )
        }

        val decoded = BackupFormatV2.decodeProfile(BackupFormatV2.encodeProfile(profile))
        val bean = decoded.requireBean() as SOCKSBean

        assertEquals(profile.id, decoded.id)
        assertEquals(profile.groupId, decoded.groupId)
        assertEquals(profile.type, decoded.type)
        assertEquals(profile.userOrder, decoded.userOrder)
        assertEquals(profile.tx, decoded.tx)
        assertEquals(profile.rx, decoded.rx)
        assertEquals(profile.status, decoded.status)
        assertEquals(profile.ping, decoded.ping)
        assertEquals(profile.uuid, decoded.uuid)
        assertEquals(profile.error, decoded.error)
        assertEquals("192.0.2.10", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
        assertEquals(2, bean.protocol)
        assertEquals("socks node", bean.name)
    }

    @Test
    fun profileRoundTrip_trojanBeanPreservesFields() {
        val profile = ProxyEntity(id = 11L, groupId = 22L).apply {
            putBean(
                TrojanBean().apply {
                    serverAddress = "example.com"
                    serverPort = 443
                    password = "secret"
                    name = "trojan node"
                    initializeDefaultValues()
                },
            )
        }

        val decoded = BackupFormatV2.decodeProfile(BackupFormatV2.encodeProfile(profile))
        val bean = decoded.requireBean() as TrojanBean

        assertEquals(ProxyEntity.TYPE_TROJAN, decoded.type)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("secret", bean.password)
        assertEquals("trojan node", bean.name)
    }

    @Test
    fun groupsRoundTrip_preservesBasicAndSubscriptionGroups() {
        val subscription = SubscriptionBean().apply {
            initializeDefaultValues()
            link = "https://example.com/sub"
            autoUpdate = true
            autoUpdateDelay = 30
            lastUpdated = 1234
        }
        val groups = listOf(
            ProxyGroup(
                id = 1L,
                userOrder = 2L,
                ungrouped = true,
                name = null,
                type = GroupType.BASIC,
                isSelector = true,
                frontProxy = 10L,
                landingProxy = 11L,
            ),
            ProxyGroup(
                id = 3L,
                userOrder = 4L,
                name = "subscription group",
                type = GroupType.SUBSCRIPTION,
                subscription = subscription,
            ),
        )

        val decoded = BackupFormatV2.decodeGroups(BackupFormatV2.encodeGroups(groups))

        assertEquals(2, decoded.size)
        assertEquals(1L, decoded[0].id)
        assertEquals(true, decoded[0].ungrouped)
        assertNull(decoded[0].name)
        assertEquals(true, decoded[0].isSelector)
        assertEquals(10L, decoded[0].frontProxy)
        assertEquals(11L, decoded[0].landingProxy)
        assertEquals(GroupType.SUBSCRIPTION, decoded[1].type)
        assertEquals("subscription group", decoded[1].name)
        assertEquals("https://example.com/sub", decoded[1].subscription!!.link)
        assertEquals(true, decoded[1].subscription!!.autoUpdate)
        assertEquals(30, decoded[1].subscription!!.autoUpdateDelay)
        assertEquals(1234, decoded[1].subscription!!.lastUpdated)
    }

    @Test
    fun ruleRoundTrip_preservesPackages() {
        val rule = RuleEntity(
            id = 5L,
            name = "rule",
            config = "config",
            userOrder = 6L,
            enabled = true,
            domains = "example.com",
            ip = "192.0.2.0/24",
            port = "443",
            sourcePort = "1000:2000",
            network = "tcp",
            source = "10.0.0.1",
            protocol = "tls",
            ruleset = "geoip-cn",
            outbound = -1L,
            packages = setOf("com.example.one", "com.example.two"),
        )

        val decoded = BackupFormatV2.decodeRule(BackupFormatV2.encodeRule(rule))

        assertEquals(rule.id, decoded.id)
        assertEquals(rule.name, decoded.name)
        assertEquals(rule.config, decoded.config)
        assertEquals(rule.userOrder, decoded.userOrder)
        assertEquals(rule.enabled, decoded.enabled)
        assertEquals(rule.domains, decoded.domains)
        assertEquals(rule.ip, decoded.ip)
        assertEquals(rule.port, decoded.port)
        assertEquals(rule.sourcePort, decoded.sourcePort)
        assertEquals(rule.network, decoded.network)
        assertEquals(rule.source, decoded.source)
        assertEquals(rule.protocol, decoded.protocol)
        assertEquals(rule.ruleset, decoded.ruleset)
        assertEquals(rule.outbound, decoded.outbound)
        assertEquals(rule.packages, decoded.packages)
    }

    @Test
    fun settingsRoundTrip_preservesStringAndStringSetValues() {
        val settings = listOf(
            KeyValuePair("string-key").put("value"),
            KeyValuePair("set-key").put(setOf("one", "two")),
        )

        val decoded = BackupFormatV2.decodeSettings(BackupFormatV2.encodeSettings(settings))

        assertEquals("string-key", decoded[0].key)
        assertEquals(KeyValuePair.TYPE_STRING, decoded[0].valueType)
        assertEquals("value", decoded[0].string)
        assertEquals("set-key", decoded[1].key)
        assertEquals(KeyValuePair.TYPE_STRING_SET, decoded[1].valueType)
        assertEquals(setOf("one", "two"), decoded[1].stringSet)
    }
}
