package dev.macromod.engine.expr

import dev.macromod.engine.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressionTest {
    private fun eval(expr: String, vars: Map<String, Value> = emptyMap()): Value =
        ExpressionEvaluator { name -> vars[name] }.evaluate(expr)

    @Test fun `arithmetic precedence — multiply binds tighter than add`() {
        assertEquals(14, eval("2+3*4").asInt())
        assertEquals(20, eval("(2+3)*4").asInt())
        assertEquals(2, eval("10-2*4+0").asInt()) // 10 - 8 = 2
    }

    @Test fun `integer division and modulo`() {
        assertEquals(3, eval("10/3").asInt())
        assertEquals(1, eval("10%3").asInt())
        assertEquals(0, eval("5/0").asInt()) // guarded
    }

    @Test fun `comparisons and equality return booleans`() {
        assertTrue(eval("5 == 5").asBoolean())
        assertTrue(eval("5 != 4").asBoolean())
        assertTrue(eval("3 < 5").asBoolean())
        assertTrue(eval("5 <= 5").asBoolean())
        assertFalse(eval("5 < 5").asBoolean())
        assertTrue(eval("9 >= 2").asBoolean())
    }

    @Test fun `boolean logic and negation`() {
        assertTrue(eval("true && true").asBoolean())
        assertFalse(eval("true && false").asBoolean())
        assertTrue(eval("false || true").asBoolean())
        assertTrue(eval("!false").asBoolean())
        assertFalse(eval("!(1 == 1)").asBoolean())
    }

    @Test fun `comparison binds looser than arithmetic`() {
        assertTrue(eval("1 + 2 == 3").asBoolean())
        assertTrue(eval("2 * 3 > 5").asBoolean())
    }

    @Test fun `string equality compares by value`() {
        val vars = mapOf("&name" to Value.Str("bob"))
        assertTrue(eval("&name == \"bob\"", vars).asBoolean())
        assertFalse(eval("&name == \"alice\"", vars).asBoolean())
    }

    @Test fun `variables resolve through the lookup`() {
        val vars = mapOf("#count" to Value.Num(7))
        assertEquals(8, eval("#count + 1", vars).asInt())
        assertTrue(eval("#count > 5", vars).asBoolean())
    }

    @Test fun `unresolved variable defaults to zero`() {
        assertEquals(1, eval("#missing + 1").asInt())
    }
}
