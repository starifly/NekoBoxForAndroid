package io.nekohasekai.sagernet.utils

import java.util.*

/**
 * Commandline objects help handling command lines specifying processes to
 * execute.
 *
 * The class can be used to define a command line as nested elements or as a
 * helper to define a command line by an application.
 *
 *
 * `
 * <someelement><br></br>
 * &nbsp;&nbsp;<acommandline executable="/executable/to/run"><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument value="argument 1" /><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument line="argument_1 argument_2 argument_3" /><br></br>
 * &nbsp;&nbsp;&nbsp;&nbsp;<argument value="argument 4" /><br></br>
 * &nbsp;&nbsp;</acommandline><br></br>
 * </someelement><br></br>
` *
 *
 * Based on: https://github.com/apache/ant/blob/588ce1f/src/main/org/apache/tools/ant/types/Commandline.java
 *
 * Adds support for escape character '\'.
 */
object Commandline {

    private val SENSITIVE_VALUE_FLAGS = setOf(
        "-client-id",
        "--client-id",
        "-key",
        "--key",
        "-password",
        "--password",
        "-pass",
        "--pass",
        "-room",
        "--room",
        "-socks-pass",
        "--socks-pass",
        "-socks-user",
        "--socks-user",
    )

    private val SENSITIVE_OUTPUT_PATTERNS = listOf(
        Regex(
            "(?i)(\\\"" +
                "(?:clientId|key|keyHex|password|roomId|serverPassword|serverUsername|" +
                "socksPass|socksUser|username)" +
                "\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
        ) to "\$1<redacted>\$2",
        Regex("(?i)(\\broom=['\"])[^'\"]+(['\"])") to "\$1<redacted>\$2",
        Regex(
            "(?i)((?:from|to)=['\"])[^'\"]+@" +
                "(?:conference|muc)\\.[^'\"]+(['\"])",
        ) to "\$1<redacted>\$2",
        Regex("(?i)((?:from|to)=['\"])[^'\"]+@[^'\"]+(['\"])") to "\$1<redacted>\$2",
        Regex("(?i)\\bolcrtc://\\S+") to "<redacted>",
        Regex("(?i)(colibri-ws=)\\S+") to "\$1<redacted>",
        Regex(
            "(?i)((?:room(?:\\s+(?:url|id))?|roomID|roomId)" +
                "[^'\\\"\\n]*['\\\"])[^'\\\"\\s<>]+(['\\\"])",
        ) to "\$1<redacted>\$2",
        Regex("(?i)\\b[0-9a-f]{64}\\b") to "<redacted>",
        Regex("(?i)(\\bsession=)[0-9a-f]{8}-[0-9a-f-]{27,}") to "\$1<redacted>",
        Regex("(?i)\\[[^\\]\\s]+]:\\d{1,5}") to "<endpoint>",
        Regex(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){4,}[0-9a-f]{1,4}" +
                "(?:%[A-Za-z0-9_.-]+)?(?![0-9a-f:])|" +
                "(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){0,7}:[0-9a-f:]{1,}" +
                "(?:%[A-Za-z0-9_.-]+)?(?![0-9a-f:])",
        ) to "<ip>",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}\\b") to "<endpoint>",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "<ip>",
        Regex("(?i)(jitsi: joining MUC )\\S+( as )") to "\$1<redacted>\$2",
        Regex("(?i)(jitsi: MUC joined )\\S+(; waiting for peer)") to "\$1<redacted>\$2",
        Regex("(?i)(jitsi: (?:rejoin|reconnected|full reconnect) )\\S+") to "\$1<redacted>",
        Regex("(?i)(session )\\S+( ((?:re)?opened \\(device=))[^)]+(\\))") to "\$1<redacted>\$2<redacted>\$4",
    )

    /**
     * Quote the parts of the given array in way that makes them
     * usable as command line arguments.
     * @param args the list of arguments to quote.
     * @return empty string for null or no command, else every argument split
     * by spaces and quoted by quoting rules.
     */
    fun toString(args: Iterable<String>?): String {
        // empty path return empty string
        args ?: return ""
        // path containing one or more elements
        val result = StringBuilder()
        for (arg in args) {
            if (result.isNotEmpty()) result.append(' ')
            arg.indices.map { arg[it] }.forEach {
                when (it) {
                    ' ', '\\', '"', '\'' -> {
                        result.append('\\') // intentionally no break
                        result.append(it)
                    }
                    else -> result.append(it)
                }
            }
        }
        return result.toString()
    }

    fun toRedactedString(args: Iterable<String>?): String = toString(redact(args))

    fun toRedactedString(args: Array<String>) = toRedactedString(args.asIterable())

    fun redactProcessOutput(line: String): String {
        var redacted = line
        for ((pattern, replacement) in SENSITIVE_OUTPUT_PATTERNS) {
            redacted = pattern.replace(redacted, replacement)
        }
        return redacted
    }

    private fun redact(args: Iterable<String>?): List<String>? {
        args ?: return null
        val redacted = ArrayList<String>()
        var redactNext = false
        for (arg in args) {
            val eq = arg.indexOf('=')
            val flag = if (eq > 0) arg.substring(0, eq) else arg
            when {
                redactNext -> {
                    redacted.add("<redacted>")
                    redactNext = false
                }
                flag in SENSITIVE_VALUE_FLAGS && eq > 0 -> redacted.add("$flag=<redacted>")
                flag in SENSITIVE_VALUE_FLAGS -> {
                    redacted.add(arg)
                    redactNext = true
                }
                else -> redacted.add(arg)
            }
        }
        return redacted
    }

    /**
     * Quote the parts of the given array in way that makes them
     * usable as command line arguments.
     * @param args the list of arguments to quote.
     * @return empty string for null or no command, else every argument split
     * by spaces and quoted by quoting rules.
     */
    fun toString(args: Array<String>) = toString(args.asIterable()) // thanks to Java, arrays aren't iterable

    /**
     * Crack a command line.
     * @param toProcess the command line to process.
     * @return the command line broken into strings.
     * An empty or null toProcess parameter results in a zero sized array.
     */
    fun translateCommandline(toProcess: String?): Array<String> {
        if (toProcess == null || toProcess.isEmpty()) {
            // no command? no string
            return arrayOf()
        }
        // parse with a simple finite state machine

        val normal = 0
        val inQuote = 1
        val inDoubleQuote = 2
        var state = normal
        val tok = StringTokenizer(toProcess, "\\\"\' ", true)
        val result = ArrayList<String>()
        val current = StringBuilder()
        var lastTokenHasBeenQuoted = false
        var lastTokenIsSlash = false

        while (tok.hasMoreTokens()) {
            val nextTok = tok.nextToken()
            when (state) {
                inQuote -> if ("\'" == nextTok) {
                    lastTokenHasBeenQuoted = true
                    state = normal
                } else {
                    current.append(nextTok)
                }
                inDoubleQuote -> when (nextTok) {
                    "\"" -> if (lastTokenIsSlash) {
                        current.append(nextTok)
                        lastTokenIsSlash = false
                    } else {
                        lastTokenHasBeenQuoted = true
                        state = normal
                    }
                    "\\" -> lastTokenIsSlash = if (lastTokenIsSlash) {
                        current.append(nextTok)
                        false
                    } else {
                        true
                    }
                    else -> {
                        if (lastTokenIsSlash) {
                            current.append("\\") // unescaped
                            lastTokenIsSlash = false
                        }
                        current.append(nextTok)
                    }
                }
                else -> {
                    when {
                        lastTokenIsSlash -> {
                            current.append(nextTok)
                            lastTokenIsSlash = false
                        }
                        "\\" == nextTok -> lastTokenIsSlash = true
                        "\'" == nextTok -> state = inQuote
                        "\"" == nextTok -> state = inDoubleQuote
                        " " == nextTok -> if (lastTokenHasBeenQuoted || current.isNotEmpty()) {
                            result.add(current.toString())
                            current.setLength(0)
                        }
                        else -> current.append(nextTok)
                    }
                    lastTokenHasBeenQuoted = false
                }
            }
        }
        if (lastTokenHasBeenQuoted || current.isNotEmpty()) result.add(current.toString())
        require(state != inQuote && state != inDoubleQuote) { "unbalanced quotes in $toProcess" }
        require(!lastTokenIsSlash) { "escape character following nothing in $toProcess" }
        return result.toTypedArray()
    }
}
