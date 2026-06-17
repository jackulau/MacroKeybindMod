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
}
