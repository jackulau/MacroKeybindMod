package dev.macromod.engine.variable

import dev.macromod.engine.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariableTest {
    @Test fun `parses sigils and types`() {
        assertEquals(VarType.COUNTER, Variable.parse("#count")!!.type)
        assertEquals(VarType.STRING, Variable.parse("&name")!!.type)
        assertEquals(VarType.FLAG, Variable.parse("ready")!!.type)
    }

    @Test fun `parses shared prefix and array index`() {
        val gold = Variable.parse("@#gold")!!
        assertTrue(gold.shared)
        assertEquals(VarType.COUNTER, gold.type)

        val elem = Variable.parse("&items[3]")!!
        assertEquals(3, elem.index)
        assertTrue(elem.isArrayElement)

        val spec = Variable.parse("scores[]")!!
        assertTrue(spec.isArraySpecifier)
    }

    @Test fun `rejects invalid names`() {
        assertNull(Variable.parse("9bad"))
        assertNull(Variable.parse(""))
        assertNull(Variable.parse("has space"))
    }

    @Test fun `registry stores and coerces by sigil`() {
        val r = VariableRegistry()
        r.setVariable("#x", Value.Str("5"))     // counter coerces "5" -> 5
        assertEquals(5, r.getVariable("#x")!!.asInt())
        r.setVariable("&s", Value.Num(42))      // string coerces 42 -> "42"
        assertEquals("42", r.getVariable("&s")!!.asString())
        r.setVariable("flag", Value.Str("true"))
        assertTrue(r.getVariable("flag")!!.asBoolean())
    }

    @Test fun `shared vs local scopes are separate`() {
        val r = VariableRegistry()
        r.setVariable("#x", Value.Num(1))
        r.setVariable("@#x", Value.Num(99))
        assertEquals(1, r.getVariable("#x")!!.asInt())
        assertEquals(99, r.getVariable("@#x")!!.asInt())
    }

    @Test fun `large array indices round-trip instead of being silently dropped`() {
        // The 5-digit index cap made parse() fail for a[100000]+, so setVariable silently
        // no-op'd and the write was lost with no error. Arrays are sparse (TreeMap), so a big
        // index is cheap; it must now store and read back.
        val r = VariableRegistry()
        r.setVariable("&a[100000]", Value.Str("z"))
        assertEquals("z", r.getVariable("&a[100000]")!!.asString())
        r.setVariable("#b[999999999]", Value.Num(7)) // 9-digit boundary, still within Int
        assertEquals(7, r.getVariable("#b[999999999]")!!.asInt())
    }

    @Test fun `array push pop and size`() {
        val r = VariableRegistry()
        r.push("&list[]", Value.Str("a"))
        r.push("&list[]", Value.Str("b"))
        assertEquals(listOf("a", "b"), r.arrayValues("&list[]").map { it.asString() })
        assertEquals("b", r.pop("&list[]")!!.asString())
        assertEquals(listOf("a"), r.arrayValues("&list[]").map { it.asString() })
    }

    @Test fun `environment providers resolve first`() {
        val r = VariableRegistry()
        r.addEnvProvider { name -> if (name == "HEALTH") Value.Num(20) else null }
        assertEquals(20, r.getVariable("HEALTH")!!.asInt())
    }

    @Test fun `increment counter`() {
        val r = VariableRegistry()
        r.increment("#n", 1)
        r.increment("#n", 4)
        assertEquals(5, r.getVariable("#n")!!.asInt())
    }
}
