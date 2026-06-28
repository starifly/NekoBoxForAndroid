package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AnsiLog: the parser that turns sing-box's ANSI-colored log bytes
 * into clean text + foreground color runs (and strips them for export).
 *
 * sing-box uses aurora: White=37 (debug/trace), Cyan=36 (info), Yellow=33
 * (warn), Red=31 (error), and a 256-color (38;5;n) for the connection id.
 */
class AnsiLogTest {

    private val ESC = "\u001B"

    // Expected ARGB values (mirror AnsiLog's palette).
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val cyan = argb(0x11, 0xA8, 0xCD)
    private val yellow = argb(0xE5, 0xE5, 0x10)
    private val red = argb(0xCD, 0x31, 0x31)
    private val white = argb(0xE5, 0xE5, 0xE5)

    @Test
    fun infoLine_stripsCodes_andColorsLevelCyan() {
        val raw = "$ESC[36mINFO$ESC[0m[0000] router: started"
        val r = AnsiLog.render(raw)
        assertEquals("INFO[0000] router: started", r.text)
        // The "INFO" token (0..4) is cyan.
        assertEquals(1, r.spans.count { it.color == cyan })
        val span = r.spans.first { it.color == cyan }
        assertEquals("INFO", r.text.substring(span.start, span.end))
    }

    @Test
    fun warnLine_yellow_errorLine_red_debugLine_white() {
        assertTrue(AnsiLog.render("$ESC[33mWARN$ESC[0m x").spans.any { it.color == yellow })
        assertTrue(AnsiLog.render("$ESC[31mERROR$ESC[0m x").spans.any { it.color == red })
        assertTrue(AnsiLog.render("$ESC[37mDEBUG$ESC[0m x").spans.any { it.color == white })
    }

    @Test
    fun connectionId_256Color_mapsToCubeArgb() {
        // 38;5;120 -> n=120; v=104; cube indices r=104/36=2, g=(104/6)%6=5, b=104%6=2
        // xterm cube levels [0,95,135,175,215,255] -> r=135, g=255, b=135
        val raw = "$ESC[38;5;120m2647384431$ESC[0m done"
        val r = AnsiLog.render(raw)
        assertEquals("2647384431 done", r.text)
        val expected = argb(135, 255, 135)
        val span = r.spans.first()
        assertEquals(expected, span.color)
        assertEquals("2647384431", r.text.substring(span.start, span.end))
    }

    @Test
    fun emptyParamReset_isReset() {
        // ESC[m is a reset (no params).
        val raw = "$ESC[36mINFO$ESC[mplain"
        val r = AnsiLog.render(raw)
        assertEquals("INFOplain", r.text)
        // Only "INFO" is colored; "plain" is not.
        val span = r.spans.first { it.color == cyan }
        assertEquals("INFO", r.text.substring(span.start, span.end))
        assertTrue(r.spans.none { it.start >= 4 })
    }

    @Test
    fun truncatedLeadingFragment_doesNotLeakCodes() {
        // A mid-sequence fragment that lost its leading ESC (e.g. "6mINFO") has no ESC,
        // so it is just literal text - acceptable for the one partial first line.
        assertEquals("6mINFO", AnsiLog.render("6mINFO").text)

        // The key guarantee: a real escape sequence later in the window is still parsed
        // and no ESC char survives in the output.
        val raw = "[0m tail line $ESC[33mWARN$ESC[0m end"
        val r = AnsiLog.render(raw)
        assertEquals("[0m tail line WARN end", r.text)
        assertTrue("no ESC should remain", !r.text.contains(ESC))
    }

    @Test
    fun nonSgrCsiSequence_isConsumed() {
        // A non-SGR CSI like ESC[?25l (hide cursor) must be fully dropped, not leak "?25l".
        assertEquals("abcdef", AnsiLog.render("abc$ESC[?25ldef").text)
        assertEquals("ab", AnsiLog.render("a$ESC[2Kb").text)
    }

    @Test
    fun incompleteTruecolor_leavesActiveColorUnchanged() {
        // ESC[38;2;255m is missing g/b: must NOT coerce to black; keep the prior color.
        val raw = "$ESC[33mWA$ESC[38;2;255mRN$ESC[0m"
        val r = AnsiLog.render(raw)
        assertEquals("WARN", r.text)
        // The whole "WARN" stays yellow (the malformed truecolor is ignored).
        assertTrue(r.spans.all { it.color == yellow })
    }

    @Test
    fun unknownExtendedSubMode_doesNotLeakAsColor() {
        // ESC[38;33;100m: 33 is the (unknown) sub-mode, must not be applied as yellow.
        val raw = "$ESC[38;33;100mtext$ESC[0m"
        val r = AnsiLog.render(raw)
        assertEquals("text", r.text)
        assertTrue("unknown 38 sub-mode must not color text", r.spans.none { it.color == yellow })
    }

    @Test
    fun truncatedTrailingEsc_isDropped() {
        // Window cut right after an ESC (or an unterminated CSI) at end of input.
        assertEquals("hello", AnsiLog.render("hello$ESC").text)
        assertEquals("hello", AnsiLog.render("hello$ESC[3").text)
        assertEquals("hello", AnsiLog.render("hello$ESC[38;5").text)
    }

    @Test
    fun noCodes_passThrough_andStripEqualsText() {
        val raw = "2026/06/27 21:29:58 [Debug] plain go-log line"
        val r = AnsiLog.render(raw)
        assertEquals(raw, r.text)
        assertTrue(r.spans.isEmpty())
        assertEquals(raw, AnsiLog.strip(raw))
    }

    @Test
    fun strip_removesAllCodes() {
        val raw = "$ESC[36mINFO$ESC[0m[0000] $ESC[38;5;120mid$ESC[0m msg"
        assertEquals("INFO[0000] id msg", AnsiLog.strip(raw))
    }
}
