package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for parseDnsHosts: every accepted address must also parse under Go's
 * net/netip (sing-box decodes the predefined map into netip.Addr), because one
 * bad value fails the whole config load. Malformed lines must be dropped, never
 * emitted.
 */
class DnsHostsParseTest {

    @Test
    fun basicEntries_parsed() {
        val hosts = parseDnsHosts(
            """
            example.com 1.1.1.1
            www.example.com 1.1.1.1 1.1.1.2
            """.trimIndent(),
        )
        assertEquals(
            mapOf("example.com" to listOf("1.1.1.1"), "www.example.com" to listOf("1.1.1.1", "1.1.1.2")),
            hosts,
        )
    }

    @Test
    fun commentsAndBlankLines_ignored() {
        val hosts = parseDnsHosts("# comment line\n\nexample.com 1.1.1.1 # trailing comment\n#example.org 2.2.2.2")
        assertEquals(mapOf("example.com" to listOf("1.1.1.1")), hosts)
    }

    @Test
    fun duplicateDomain_mergedAndDeduplicated() {
        val hosts = parseDnsHosts("example.com 1.1.1.1\nexample.com 1.1.1.2\nexample.com 1.1.1.1")
        assertEquals(mapOf("example.com" to listOf("1.1.1.1", "1.1.1.2")), hosts)
    }

    @Test
    fun separators_tabAndNbsp_accepted() {
        val hosts = parseDnsHosts("example.com\t1.1.1.1\nexample.org\u00A02.2.2.2")
        assertEquals(mapOf("example.com" to listOf("1.1.1.1"), "example.org" to listOf("2.2.2.2")), hosts)
    }

    @Test
    fun ipv6_bracketsUnwrappedAndValidFormsAccepted() {
        val hosts = parseDnsHosts(
            """
            a.example [2001:db8::1]
            b.example 2001:db8::2
            c.example ::1
            d.example 1:2:3:4:5:6:7:8
            e.example 2001:0db8::1
            """.trimIndent(),
        )
        assertEquals(listOf("2001:db8::1"), hosts["a.example"])
        assertEquals(listOf("2001:db8::2"), hosts["b.example"])
        assertEquals(listOf("::1"), hosts["c.example"])
        assertEquals(listOf("1:2:3:4:5:6:7:8"), hosts["d.example"])
        assertEquals(listOf("2001:0db8::1"), hosts["e.example"])
    }

    @Test
    fun ipv6_netipInvalidForms_rejected() {
        // All of these pass the loose NGUtil regex but fail Go's netip.ParseAddr;
        // any of them reaching the config would abort the sing-box config load.
        val hosts = parseDnsHosts(
            """
            a.example :1::2
            b.example :::1
            c.example 1:2:::3
            d.example :1::
            e.example 1:2:3:4:5:6:7:8::
            f.example ::1:2:3:4:5:6:7:8
            g.example 1::2::3
            """.trimIndent(),
        )
        assertTrue(hosts.isEmpty())
    }

    @Test
    fun ipv4_leadingZeros_rejected() {
        val hosts = parseDnsHosts("a.example 01.2.3.4\nb.example 1.2.3.04\nc.example 1.2.3.4")
        assertEquals(mapOf("c.example" to listOf("1.2.3.4")), hosts)
    }

    @Test
    fun nonIpTokens_droppedNotEmitted() {
        val hosts = parseDnsHosts("example.com 1.1.1.1 not-an-ip\nexample.org not-an-ip")
        assertEquals(mapOf("example.com" to listOf("1.1.1.1")), hosts)
    }

    @Test
    fun invalidDomains_rejected() {
        val hosts = parseDnsHosts(
            """
            1.2.3.4 1.1.1.1
            -bad.example 1.1.1.1
            bad-.example 1.1.1.1
            bad..example 1.1.1.1
            ba*d.example 1.1.1.1
            """.trimIndent(),
        )
        assertTrue(hosts.isEmpty())
    }

    @Test
    fun domain_normalization() {
        val hosts = parseDnsHosts("EXAMPLE.COM. 1.1.1.1\n_dmarc.example.org 2.2.2.2\nbücher.example 3.3.3.3")
        assertEquals(listOf("1.1.1.1"), hosts["example.com"])
        assertEquals(listOf("2.2.2.2"), hosts["_dmarc.example.org"])
        // IDN converted to the punycode form used in real DNS queries.
        assertEquals(listOf("3.3.3.3"), hosts["xn--bcher-kva.example"])
    }
}
