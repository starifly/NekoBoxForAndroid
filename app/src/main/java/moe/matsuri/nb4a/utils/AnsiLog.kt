package moe.matsuri.nb4a.utils

/**
 * Minimal ANSI SGR (Select Graphic Rendition) parser for the embedded sing-box
 * log output. sing-box bakes aurora color codes (e.g. ESC[36mINFO ESC[0m, plus a
 * per-connection-id ESC[38;5;<n>m token) into each log line before it reaches the
 * Android side, so the raw bytes in cache/neko.log contain escape sequences.
 *
 * This is a pure-JVM utility (no Android types) so it can be unit tested: it
 * returns foreground color runs as plain ARGB ints, and the Logs viewer wraps
 * them in ForegroundColorSpans. The exporter uses [strip] to produce a clean,
 * human-readable .log for bug reports.
 *
 * Only foreground color is rendered. Background/bold/italic and any non-SGR CSI
 * sequence are parsed and dropped so they never leak into the displayed text.
 */
object AnsiLog {

    /** A foreground color run over [start, end) indices of the cleaned text. */
    data class Span(val start: Int, val end: Int, val color: Int)

    /** Cleaned text (escape codes removed) plus the foreground color runs. */
    data class Rendered(val text: String, val spans: List<Span>)

    private const val ESC = '\u001B'

    /** Parse SGR codes into clean text + foreground color runs (ARGB ints). */
    fun render(input: String): Rendered {
        val out = StringBuilder(input.length)
        val spans = ArrayList<Span>()

        var currentColor: Int? = null
        var runStart = -1

        fun closeRun() {
            if (currentColor != null && runStart >= 0 && out.length > runStart) {
                spans.add(Span(runStart, out.length, currentColor!!))
            }
            runStart = -1
        }

        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            if (c == ESC) {
                // Expect a CSI sequence: ESC '[' params finalByte. The final byte is in
                // '@'..'~'; everything before it (digits, ';', private/intermediate bytes
                // like '?') is part of the sequence and must be consumed.
                if (i + 1 < n && input[i + 1] == '[') {
                    var j = i + 2
                    while (j < n && input[j] !in '@'..'~') j++
                    if (j < n) {
                        val finalByte = input[j]
                        val params = input.substring(i + 2, j)
                        if (finalByte == 'm') {
                            val newColor = applySgr(params, currentColor)
                            if (newColor != currentColor) {
                                closeRun()
                                currentColor = newColor
                                runStart = if (newColor != null) out.length else -1
                            }
                        }
                        // Any other CSI final byte (cursor moves, private modes, ...):
                        // consume the whole sequence and drop it (no text emitted).
                        i = j + 1
                        continue
                    }
                    // Unterminated CSI at end of input (truncated 50KB window): drop the
                    // whole partial "ESC[..." fragment so its bytes never leak as text.
                    i = j
                    continue
                }
                // Lone ESC not starting a CSI: drop it.
                i++
                continue
            }
            out.append(c)
            i++
        }
        closeRun()
        return Rendered(out.toString(), spans)
    }

    /** Same parse as [render] but drops all color, returning plain text only. */
    fun strip(input: String): String = render(input).text

    /**
     * Apply an SGR parameter list to the current foreground color.
     * Returns the new foreground color (null = default/unstyled).
     */
    private fun applySgr(params: String, current: Int?): Int? {
        // Empty params (ESC[m) means reset.
        if (params.isEmpty()) return null
        val codes = params.split(';')
        var color = current
        var k = 0
        while (k < codes.size) {
            val code = codes[k].toIntOrNull()
            if (code == null) {
                k++
                continue
            }
            when {
                code == 0 -> color = null // reset
                code in 30..37 -> color = STANDARD[code - 30]
                code in 90..97 -> color = BRIGHT[code - 90]
                code == 39 -> color = null // default foreground
                code == 38 -> {
                    // Extended foreground: 38;5;n (256) or 38;2;r;g;b (truecolor).
                    when (codes.getOrNull(k + 1)?.toIntOrNull()) {
                        5 -> {
                            val idx = codes.getOrNull(k + 2)?.toIntOrNull()
                            if (idx != null) color = color256(idx)
                            k += 2
                        }
                        2 -> {
                            val r = codes.getOrNull(k + 2)?.toIntOrNull()
                            val g = codes.getOrNull(k + 3)?.toIntOrNull()
                            val b = codes.getOrNull(k + 4)?.toIntOrNull()
                            // Only apply when all components are present; otherwise leave
                            // the active color unchanged (don't coerce missing parts to 0).
                            if (r != null && g != null && b != null) color = argb(r, g, b)
                            k += 4
                        }
                        // Unknown sub-mode (e.g. a future 38;6;...): advance past the mode
                        // value so it is not re-processed as a standalone SGR code.
                        else -> k += 1
                    }
                }
                // Background (40..47, 100..107, 48;...), bold/dim/etc: ignore.
            }
            k++
        }
        return color
    }

    /** Map an xterm 256-color index to ARGB. */
    private fun color256(n: Int): Int = when {
        n in 0..7 -> STANDARD[n]
        n in 8..15 -> BRIGHT[n - 8]
        n in 16..231 -> {
            // 6x6x6 color cube; xterm component levels are 0,95,135,175,215,255.
            val v = n - 16
            argb(CUBE[v / 36], CUBE[(v / 6) % 6], CUBE[v % 6])
        }
        n in 232..255 -> {
            val level = 8 + (n - 232) * 10
            argb(level, level, level)
        }
        else -> STANDARD[7]
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or
        ((r.coerceIn(0, 255)) shl 16) or
        ((g.coerceIn(0, 255)) shl 8) or
        (b.coerceIn(0, 255))

    // xterm 6x6x6 color-cube component levels.
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    // Palette tuned for a dark log background (VS Code dark terminal theme).
    // sing-box uses: White=37 (debug/trace), Cyan=36 (info), Yellow=33 (warn),
    // Red=31 (error/fatal/panic), plus a computed 256-color for the conn id.
    private val STANDARD = intArrayOf(
        argb(0, 0, 0), // 30 black
        argb(0xCD, 0x31, 0x31), // 31 red
        argb(0x0D, 0xBC, 0x79), // 32 green
        argb(0xE5, 0xE5, 0x10), // 33 yellow
        argb(0x24, 0x72, 0xC8), // 34 blue
        argb(0xBC, 0x3F, 0xBC), // 35 magenta
        argb(0x11, 0xA8, 0xCD), // 36 cyan
        argb(0xE5, 0xE5, 0xE5), // 37 white
    )

    private val BRIGHT = intArrayOf(
        argb(0x66, 0x66, 0x66), // 90 bright black (gray)
        argb(0xF1, 0x4C, 0x4C), // 91 bright red
        argb(0x23, 0xD1, 0x8B), // 92 bright green
        argb(0xF5, 0xF5, 0x43), // 93 bright yellow
        argb(0x3B, 0x8E, 0xEA), // 94 bright blue
        argb(0xD6, 0x70, 0xD6), // 95 bright magenta
        argb(0x29, 0xB8, 0xDB), // 96 bright cyan
        argb(0xFF, 0xFF, 0xFF), // 97 bright white
    )
}
