package dev.macromod.engine.param

import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpandTest {
    private fun expander(vararg pairs: Pair<String, Value>): VariableExpander {
        val r = VariableRegistry()
        pairs.forEach { r.setVariable(it.first, it.second) }
        return VariableExpander(r)
    }

    @Test fun `expands a variable reference`() {
        assertEquals("hp=20", expander("#hp" to Value.Num(20)).expand("hp=%#hp%"))
    }

    @Test fun `unresolved references use type defaults`() {
        val e = expander()
        assertEquals("0", e.expand("%#x%"))
        assertEquals("", e.expand("%&s%"))
        assertEquals("False", e.expand("%flag%"))
    }

    @Test fun `quote-strings mode wraps string values`() {
        assertEquals("\"bob\"", expander("&n" to Value.Str("bob")).expand("%&n%", quoteStrings = true))
    }

    @Test fun `text without percent is passthrough`() {
        assertEquals("plain text", expander().expand("plain text"))
    }

    @Test fun `references cascade`() {
        val e = expander("&a" to Value.Str("%&b%"), "&b" to Value.Str("done"))
        assertEquals("done", e.expand("%&a%"))
    }

    @Test fun `many references on one line all expand in a single pass`() {
        val e = expander("#a" to Value.Num(1), "#b" to Value.Num(2), "#c" to Value.Num(3), "&d" to Value.Str("x"))
        assertEquals("1-2-3-x-1", e.expand("%#a%-%#b%-%#c%-%&d%-%#a%"))
    }

    @Test fun `a self-referential cycle terminates instead of spinning`() {
        // %a% -> "%a%" -> "%a%" ... fixpoint detected; result is the unexpanded ref, no hang.
        val e = expander("&a" to Value.Str("%&a%"))
        assertEquals("%&a%", e.expand("%&a%"))
    }

    @Test fun `a literal percent sign is left untouched`() {
        assertEquals("100% done", expander().expand("100% done"))
        // the bare "50%" stays literal (no name follows the %); the real ref still expands
        assertEquals("up 50%, down 7", expander("#n" to Value.Num(7)).expand("up 50%, down %#n%"))
    }

    @Test fun `a large array index reference expands`() {
        // The find-pattern index cap was widened 5 -> 9 digits to match the write path; a 6-digit
        // index used to be left literal here even though setVariable stored it.
        val e = expander("&a[100000]" to Value.Str("z"))
        assertEquals("z", e.expand("%&a[100000]%"))
    }
}
