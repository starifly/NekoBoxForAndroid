package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandlineTest {

    @Test
    fun toRedactedString_hidesSensitiveFlagValues() {
        val key = "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799"
        val rendered = Commandline.toRedactedString(
            listOf(
                "/data/app/libolcrtc.so",
                "-carrier",
                "jitsi",
                "-room",
                "https://meet.example.org/brief-5935",
                "-client-id",
                "device-3924",
                "-key",
                key,
                "-socks-user",
                "neko",
                "-socks-pass",
                "secret",
                "-dns",
                "9.9.9.9:53",
            ),
        )

        assertTrue(rendered.contains("-carrier jitsi"))
        assertTrue(rendered.contains("-dns 9.9.9.9:53"))
        assertFalse(rendered.contains("brief-5935"))
        assertFalse(rendered.contains("device-3924"))
        assertFalse(rendered.contains(key))
        assertFalse(rendered.contains("secret"))
        assertEquals(5, Regex("<redacted>").findAll(rendered).count())
    }

    @Test
    fun redactProcessOutput_hidesProfileFieldsAndRoomNames() {
        val key = "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799"
        val line = """
            {"roomId":"https://meet.example.org/brief-5935","clientId":"device-3924","keyHex":"$key","username":"neko","password":"secret"}
            imported olcrtc://jitsi?datachannel<cid=device-3924>@https://meet.example.org/brief-5935#$key
            invalid room URL "https://meet.example.org/brief-5935"
            invalid room id "brief-5935"
            jitsi: connected colibri-ws=wss://meet.example.org/colibri-ws/default-id/brief-5935?pwd=secret
            jitsi: joining MUC meet.example.org/brief-5935 as Example
            jitsi: full reconnect meet.example.org/brief-5935
            session abc123 opened (device=device-3924)
            session def456 reopened (device=device-3924)
            control alive session=3409404d-0e96-4353-9768-5e1aa74ec336 rtt=228ms
            Ping STUN from udp4 relay 81.200.148.114:58392 related 192.168.171.101:36762 to udp4 host 81.200.148.114:10000
            IPv6 candidate [fe80::1%wlan0]:5353 to [2001:db8::1]:10000
            Bare IPv6 candidate 2001:db8::2 and 2001:db8:0:0:0:0:0:1 and cafe:babe::dead:beef
            <iq xmlns="jabber:client" from="88528237-09bb-4635-b01a-9e9d3549957b@meet.example.org/resource" to="brief-5935@conference.meet.example.org/user" />
            <presence to="brief-5935@conference.meet.example.org/user" room="brief-5935@conference.meet.example.org" />
        """.trimIndent()

        val redacted = Commandline.redactProcessOutput(line)

        assertFalse(redacted.contains("brief-5935"))
        assertFalse(redacted.contains("device-3924"))
        assertFalse(redacted.contains(key))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("olcrtc://"))
        assertFalse(redacted.contains("3409404d"))
        assertFalse(redacted.contains("81.200.148.114"))
        assertFalse(redacted.contains("192.168.171.101"))
        assertFalse(redacted.contains("88528237"))
        assertFalse(redacted.contains("fe80::1"))
        assertFalse(redacted.contains("2001:db8"))
        assertTrue(redacted.contains("invalid room URL \"<redacted>\""))
        assertTrue(redacted.contains("invalid room id \"<redacted>\""))
        assertTrue(redacted.contains("colibri-ws=<redacted>"))
        assertTrue(redacted.contains("jitsi: joining MUC <redacted> as Example"))
        assertTrue(redacted.contains("jitsi: full reconnect <redacted>"))
        assertTrue(redacted.contains("session <redacted> opened (device=<redacted>)"))
        assertTrue(redacted.contains("session <redacted> reopened (device=<redacted>)"))
        assertTrue(redacted.contains("control alive session=<redacted>"))
        assertTrue(redacted.contains("Ping STUN from udp4 relay <endpoint> related <endpoint> to udp4 host <endpoint>"))
        assertTrue(redacted.contains("IPv6 candidate <endpoint> to <endpoint>"))
        assertTrue(redacted.contains("Bare IPv6 candidate <ip> and <ip> and <ip>"))
        assertFalse(redacted.contains("jabber<ip>lient"))
        assertTrue(redacted.contains("xmlns=\"jabber:client\""))
        assertTrue(redacted.contains("from=\"<redacted>\" to=\"<redacted>\""))
    }
}
