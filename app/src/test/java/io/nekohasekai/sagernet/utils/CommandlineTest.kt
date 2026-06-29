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
            <presence to="brief-5935@conference.meet.example.org/user" room="brief-5935@conference.meet.example.org" />
        """.trimIndent()

        val redacted = Commandline.redactProcessOutput(line)

        assertFalse(redacted.contains("brief-5935"))
        assertFalse(redacted.contains("device-3924"))
        assertFalse(redacted.contains(key))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("olcrtc://"))
        assertTrue(redacted.contains("invalid room URL \"<redacted>\""))
        assertTrue(redacted.contains("invalid room id \"<redacted>\""))
        assertTrue(redacted.contains("colibri-ws=<redacted>"))
        assertTrue(redacted.contains("jitsi: joining MUC <redacted> as Example"))
        assertTrue(redacted.contains("jitsi: full reconnect <redacted>"))
        assertTrue(redacted.contains("session <redacted> opened (device=<redacted>)"))
        assertTrue(redacted.contains("session <redacted> reopened (device=<redacted>)"))
    }
}
