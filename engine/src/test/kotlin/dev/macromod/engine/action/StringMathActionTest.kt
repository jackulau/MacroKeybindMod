package dev.macromod.engine.action

import dev.macromod.engine.runScript
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringMathActionTest {
    private fun exec(body: String, registry: VariableRegistry = VariableRegistry()): VariableRegistry {
        runScript(body, registry)
        return registry
    }

    @Test fun `random stays within the inclusive range`() {
        val r = VariableRegistry()
        repeat(50) {
            exec("#x = random(1, 6)", r)
            assertTrue(r.getVariable("#x")!!.asInt() in 1..6)
        }
    }

    @Test fun `abs min max`() {
        assertEquals(5, exec("#r = abs(-5)").getVariable("#r")!!.asInt())
        assertEquals(3, exec("#r = min(3, 7)").getVariable("#r")!!.asInt())
        assertEquals(7, exec("#r = max(3, 7)").getVariable("#r")!!.asInt())
    }

    @Test fun `substr with and without length`() {
        assertEquals("ell", exec("&r = substr(\"hello\", 1, 3)").getVariable("&r")!!.asString())
        assertEquals("llo", exec("&r = substr(\"hello\", 2)").getVariable("&r")!!.asString())
    }

    @Test fun `trim removes surrounding whitespace`() {
        assertEquals("hi", exec("&r = trim(\"  hi  \")").getVariable("&r")!!.asString())
    }

    @Test fun `join concatenates array elements`() {
        val r = VariableRegistry()
        r.push("&a[]", Value.Str("x"))
        r.push("&a[]", Value.Str("y"))
        r.push("&a[]", Value.Str("z"))
        assertEquals("x-y-z", exec("&r = join(&a[], \"-\")", r).getVariable("&r")!!.asString())
    }

    @Test fun `regexreplace replaces all matches`() {
        assertEquals("a#b#", exec("&r = regexreplace(\"a1b2\", \"[0-9]\", \"#\")").getVariable("&r")!!.asString())
    }

    @Test fun `match returns the first match`() {
        assertEquals("123", exec("&r = match(\"abc123\", \"[0-9]+\")").getVariable("&r")!!.asString())
    }

    @Test fun `ifcontains gates on substring`() {
        assertEquals(listOf("yes"), runScript("ifcontains(\"hello\", \"ell\"); log(\"yes\"); else; log(\"no\"); endif").logs)
        assertEquals(listOf("no"), runScript("ifcontains(\"hello\", \"xyz\"); log(\"yes\"); else; log(\"no\"); endif").logs)
    }

    @Test fun `ifbeginswith and ifendswith`() {
        assertEquals(listOf("b"), runScript("ifbeginswith(\"hello\", \"he\"); log(\"b\"); endif").logs)
        assertEquals(listOf("e"), runScript("ifendswith(\"hello\", \"lo\"); log(\"e\"); endif").logs)
    }

    @Test fun `ifmatches gates on a regex`() {
        assertEquals(listOf("m"), runScript("ifmatches(\"abc123\", \"[0-9]+\"); log(\"m\"); endif").logs)
        assertTrue(runScript("ifmatches(\"abc\", \"[0-9]+\"); log(\"m\"); endif").logs.isEmpty())
    }
}
