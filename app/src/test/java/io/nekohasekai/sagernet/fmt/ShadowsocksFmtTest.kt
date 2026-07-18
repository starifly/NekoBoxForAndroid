package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization tests for the Shadowsocks (ss://) URI decoder.
 *
 * These pin CURRENT behavior of parseShadowsocks (see Plan 007). Golden values are
 * hand-traced from ShadowsocksFmt.kt; if one looks wrong, it is recorded as-is and flagged,
 * not "fixed" here.
 */
class ShadowsocksFmtTest {

    /**
     * SIP002-style with base64url userinfo is intentionally NOT asserted here: the exact
     * decode path (toHttpUrlOrNull on base64 userinfo, jms fallback) is fiddly and deriving a
     * stable golden needs deeper tracing; characterized later alongside Plan 022. The
     * plaintext-userinfo form below is the stable, asserted golden.
     */
    @Test
    fun parseShadowsocks_plaintextUserinfo() {
        val link = "ss://aes-128-gcm:pw@example.com:443#my%20node"
        val bean = parseShadowsocks(link)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("aes-128-gcm", bean.method)
        assertEquals("pw", bean.password)
        // link.fragment is URL-decoded by the HttpUrl parser.
        assertEquals("my node", bean.name)
    }
}
