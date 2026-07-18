package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.test.runTest
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterParseTest {

    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun clashBasic_parsesShadowsocksAndVmess() = runTest {
        val beans = RawUpdater.parseRaw(fixture("clash-basic.yaml"))!!

        assertEquals(2, beans.size)
        val shadowsocks = beans.filterIsInstance<ShadowsocksBean>().single()
        assertEquals("192.0.2.1", shadowsocks.serverAddress)
        assertEquals(443, shadowsocks.serverPort)
        assertEquals("aes-128-gcm", shadowsocks.method)
        assertEquals("alpha", shadowsocks.password)
        val vmess = beans.filterIsInstance<VMessBean>().single()
        assertEquals("example.com", vmess.serverAddress)
        assertEquals(8443, vmess.serverPort)
        assertEquals("00000000-0000-4000-8000-000000000001", vmess.uuid)
    }

    @Test
    fun clashMalformedNode_skipsOnlyMalformedEntry() = runTest {
        val beans = RawUpdater.parseRaw(fixture("clash-malformed.yaml"))!!

        assertEquals(2, beans.size)
        assertEquals(listOf("ss-first", "vm-last"), beans.map { it.displayName() })
    }

    @Test
    fun clashUnknownGlobalTag_isRejected() = runTest {
        assertNull(RawUpdater.parseRaw(fixture("clash-unknown-tag.yaml")))
    }

    @Test
    fun clashCollectionAliases_overLimitAreRejected() = runTest {
        val input = buildString {
            appendLine("node: &node")
            appendLine("  name: repeated")
            appendLine("  type: ss")
            appendLine("  server: 192.0.2.3")
            appendLine("  port: 443")
            appendLine("  cipher: aes-128-gcm")
            appendLine("  password: alpha")
            appendLine("proxies:")
            repeat(201) { appendLine("  - *node") }
        }

        assertNull(RawUpdater.parseRaw(input))
    }

    @Test
    fun base64UriList_parsesShadowsocksAndSocks() = runTest {
        val links = listOf(
            "ss://aes-128-gcm:alpha@example.com:443#ss-one",
            "socks://reader:beta@192.0.2.8:1080#socks-one",
        ).joinToString("\n")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(links.toByteArray())

        val beans = RawUpdater.parseRaw(encoded)!!

        assertEquals(2, beans.size)
        assertTrue(beans.any { it is ShadowsocksBean && it.password == "alpha" })
        assertTrue(
            beans.any {
                it is SOCKSBean && it.serverAddress == "192.0.2.8" &&
                    it.serverPort == 1080 && it.username == "reader" && it.password == "beta"
            },
        )
    }

    @Test
    fun singboxOutbounds_wrapsOutboundAsConfigBean() = runTest {
        val bean = RawUpdater.parseRaw(fixture("singbox-outbounds.json"))!!.single() as ConfigBean
        val config = JSONObject(bean.config)

        assertEquals(1, bean.type)
        assertEquals("edge-one", bean.name)
        assertEquals("socks", config.getString("type"))
        assertEquals("192.0.2.9", config.getString("server"))
        assertEquals(1080, config.getInt("server_port"))
    }

    @Test
    fun wireguardConfig_parsesPeerAndFileName() = runTest {
        val bean = RawUpdater.parseRaw(fixture("wireguard.conf"), "office.conf")!!.single() as WireGuardBean

        assertEquals("office", bean.name)
        assertEquals("192.0.2.2/32", bean.localAddress)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", bean.privateKey)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", bean.peerPublicKey)
    }

    @Test
    fun emptyInput_returnsNull() = runTest {
        assertNull(RawUpdater.parseRaw(""))
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/subscriptions/$name")).readText()
}
