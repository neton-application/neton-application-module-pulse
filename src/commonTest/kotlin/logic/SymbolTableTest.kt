package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Symbol table parsing. The input is whatever `nm -n` produced on someone's machine, so the
 * tolerant cases matter as much as the happy one.
 */
class SymbolTableTest {

    @Test
    fun nmOutputIsParsedAndSorted() {
        val text = """
            0000000100002000 T _second
            0000000100001000 T _first
            0000000100003000 t _local
        """.trimIndent()
        val parsed = SymbolTable.parse(text)
        assertEquals(3, parsed.size)
        assertEquals(listOf(0x100001000L, 0x100002000L, 0x100003000L), parsed.map { it.first })
        assertEquals("_first", parsed.first().second)
    }

    @Test
    fun aPlainAddressNameMapWorksToo() {
        // Hand-written maps and some atos listings have no type column.
        val parsed = SymbolTable.parse("100001000 myFunction\n100002000 otherFunction")
        assertEquals(2, parsed.size)
        assertEquals("myFunction", parsed.first().second)
    }

    @Test
    fun undefinedAndJunkLinesAreSkipped() {
        val text = """
                             U _CFRelease
            0000000100001000 T _real

            not a symbol line
        """.trimIndent()
        val parsed = SymbolTable.parse(text)
        assertEquals(1, parsed.size)
        assertEquals("_real", parsed.single().second)
    }

    @Test
    fun namesWithSpacesSurvive() {
        // Kotlin/Native mangles signatures into the symbol name, spaces and all.
        val parsed = SymbolTable.parse("0000000100001000 T _kfun:foo(kotlin.Int, kotlin.String)")
        assertEquals("_kfun:foo(kotlin.Int, kotlin.String)", parsed.single().second)
    }

    @Test
    fun duplicateAddressesCollapse() {
        // Two symbols at one address cannot both be "the" function for it; keeping both would make
        // resolution depend on scan order.
        val parsed = SymbolTable.parse("100001000 T _a\n100001000 T _b")
        assertEquals(1, parsed.size)
    }

    @Test
    fun hexPrefixIsAccepted() {
        assertEquals(0x100001000L, SymbolTable.parse("0x100001000 T _x").single().first)
    }
}
