package logic

/**
 * Parsing of uploaded symbol tables.
 *
 * Separate from [PulseCrashLogic] and free of any dependency, because the input is whatever `nm`
 * produced on someone's machine and the tolerant cases are what need pinning down.
 */
object SymbolTable {

    /**
     * Read `nm -n` style text: an address in hex, an optional one-letter type, then the name.
     * Undefined symbols (no address) and anything unparseable are skipped rather than rejected —
     * a table with a few odd lines is still worth loading.
     *
     * Returns start addresses sorted ascending, deduplicated: two symbols at one address cannot
     * both be the answer for it, and keeping both would make resolution depend on scan order.
     */
    fun parse(text: String): List<Pair<Long, String>> =
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE)
                if (parts.size < 2) return@mapNotNull null
                val address = parts[0].removePrefix("0x").toLongOrNull(16) ?: return@mapNotNull null
                // `nm` puts a one-letter type between address and name; a plain "address name"
                // map has no such column. Detect it rather than demanding one form.
                val name = if (parts.size >= 3 && parts[1].length == 1) parts.drop(2).joinToString(" ")
                else parts.drop(1).joinToString(" ")
                if (name.isBlank()) null else address to name
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()

    private val WHITESPACE = Regex("\\s+")
}
