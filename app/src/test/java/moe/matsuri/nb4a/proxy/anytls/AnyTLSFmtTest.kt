package moe.matsuri.nb4a.proxy.anytls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnyTLSFmtTest {
    @Test
    fun singBoxOutboundSubscriptionBecomesRegularAnyTlsNode() {
        val bean = parseSingBoxAnyTLSMap(mapOf("type" to "anytls", "tag" to "web3-us", "server" to "example.com", "server_port" to 443,
            "password" to "secret", "tls" to mapOf("server_name" to "cdn.example.com", "insecure" to true,
                "utls" to mapOf("fingerprint" to "chrome"), "alpn" to listOf("h2", "http/1.1"))))!!
        assertEquals("web3-us", bean.name)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("secret", bean.password)
        assertEquals("cdn.example.com", bean.sni)
        assertTrue(bean.allowInsecure)
        assertEquals("chrome", bean.utlsFingerprint)
        assertEquals("h2\nhttp/1.1", bean.alpn)
    }
}
