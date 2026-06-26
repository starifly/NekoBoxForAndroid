package io.nekohasekai.sagernet.group

/**
 * Minimal INI reader for WireGuard / AmneziaWG `.conf` files, replacing the abandoned
 * org.ini4j dependency (Plan 020). Scoped to exactly the shape those configs use:
 *
 * - repeated sections with the same name (`[Peer]` can appear multiple times),
 * - repeated keys within a section (`Address` can appear multiple times),
 * - `key = value` (whitespace around `=` trimmed; first `=` splits),
 * - `#` and `;` comment lines, blank lines ignored,
 * - section/key lookup is case-sensitive (matches how the existing keys are referenced).
 *
 * This is pure Kotlin (no Android / libcore), so it is directly JVM-unit-testable.
 */
class IniConfig private constructor(private val sections: List<Section>) {

    class Section(val name: String) {
        // Preserves insertion order and duplicates.
        private val entries = ArrayList<Pair<String, String>>()

        internal fun add(key: String, value: String) {
            entries.add(key to value)
        }

        /** First value for [key], or null if absent (mirrors ini4j Section.get / section[key]). */
        operator fun get(key: String): String? =
            entries.firstOrNull { it.first == key }?.second

        /** All values for [key] in order, or null if none (mirrors ini4j Section.getAll). */
        fun getAll(key: String): List<String>? =
            entries.filter { it.first == key }.map { it.second }.takeIf { it.isNotEmpty() }
    }

    /** First section named [name], or null (mirrors ini4j Ini.get / ini[name]). */
    operator fun get(name: String): Section? = sections.firstOrNull { it.name == name }

    /** All sections named [name] in order, or null if none (mirrors ini4j Ini.getAll). */
    fun getAll(name: String): List<Section>? =
        sections.filter { it.name == name }.takeIf { it.isNotEmpty() }

    companion object {
        private val sectionHeader = Regex("""^\[(.+)]$""")

        fun parse(text: String): IniConfig {
            val sections = ArrayList<Section>()
            var current: Section? = null
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#") || line.startsWith(";")) continue
                val header = sectionHeader.matchEntire(line)
                if (header != null) {
                    current = Section(header.groupValues[1].trim()).also { sections.add(it) }
                    continue
                }
                val eq = line.indexOf('=')
                if (eq < 0) continue // not a key=value line; ignore (ini4j tolerates loosely)
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.isEmpty()) continue
                current?.add(key, value)
            }
            return IniConfig(sections)
        }
    }
}
