package io.nekohasekai.sagernet.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDAVSecurityTest {

    @Test
    fun redactedUrl_removesPath() {
        val redacted = redactedWebDavUrlForLog("https://example.com/secret/path/file.zip".toHttpUrl())

        assertEquals("https://example.com/<redacted-path>", redacted)
    }

    @Test
    fun redactedUrl_removesCredentialsAndQuery() {
        val redacted = redactedWebDavUrlForLog("https://user:pass@example.com/private?token=x".toHttpUrl())

        assertEquals("https://example.com/<redacted-path>", redacted)
    }

    @Test
    fun redactedUrl_preservesNonDefaultPort() {
        val redacted = redactedWebDavUrlForLog("https://example.com:8443/a/b".toHttpUrl())

        assertEquals("https://example.com:8443/<redacted-path>", redacted)
    }

    @Test
    fun redactedUrl_bracketsIpv6Host() {
        val redacted = redactedWebDavUrlForLog("https://[2001:db8::1]:8443/a/b".toHttpUrl())

        assertEquals("https://[2001:db8::1]:8443/<redacted-path>", redacted)
    }
}
