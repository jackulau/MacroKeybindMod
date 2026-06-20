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

    @Test fun `random with an Int MAX upper bound does not crash`() {
        // `b + 1` used to overflow to Int.MIN_VALUE when b == Int.MAX_VALUE, making the
        // underlying nextInt(origin, bound) throw because bound <= origin.
        val r = VariableRegistry()
        repeat(20) {
            exec("#x = random(2147483646, 2147483647)", r)
            assertTrue(r.getVariable("#x")!!.asInt() in 2147483646..2147483647)
        }
        exec("#y = random(0, 2147483647)", r) // must not throw
        assertTrue(r.getVariable("#y")!!.asInt() >= 0)
    }

    @Test fun `random with one arg is inclusive of the max`() {
        // ACTIONS.md RANDOM(<#target>,[max],[min]) / MKB ScriptActionRandom: random([max]) -> [0, max]
        // INCLUSIVE (was exclusive 0..max-1). Only `max` is reachable proves the inclusive upper bound.
        val r = VariableRegistry()
        var sawMax = false
        repeat(300) {
            exec("#x = random(5)", r)
            val v = r.getVariable("#x")!!.asInt()
            assertTrue(v in 0..5)
            if (v == 5) sawMax = true
        }
        assertTrue(sawMax)
    }

    @Test fun `bare random is in 0 to 100`() {
        // MKB bare random() -> 0..100 (was a full-range Int).
        val r = VariableRegistry()
        repeat(300) {
            exec("#x = random()", r)
            assertTrue(r.getVariable("#x")!!.asInt() in 0..100)
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
        // glue-first arg order, matching ScriptActionJoin + ACTIONS.md:69 / DSL-REFERENCE.md:799
        assertEquals("x-y-z", exec("&r = join(\"-\", &a[])", r).getVariable("&r")!!.asString())
    }

    @Test fun `regexreplace replaces all matches`() {
        assertEquals("a#b#", exec("&r = regexreplace(\"a1b2\", \"[0-9]\", \"#\")").getVariable("&r")!!.asString())
    }

    @Test fun `match returns the first match`() {
        assertEquals("123", exec("&r = match(\"abc123\", \"[0-9]+\")").getVariable("&r")!!.asString())
        // case-insensitive, matching the decompiled ScriptActionMatch (Pattern.compile(regex, 2))
        assertEquals("ABC", exec("&r = match(\"ABC\", \"[a-z]+\")").getVariable("&r")!!.asString())
    }

    @Test fun `match with an explicit group index returns that group`() {
        // ([0-9]+)-([0-9]+): group 1 = year, group 2 = month, group 0 = whole match (ScriptActionMatch:61)
        assertEquals("01", exec("&r = match(\"2024-01\", \"([0-9]+)-([0-9]+)\", 2)").getVariable("&r")!!.asString())
        assertEquals("2024", exec("&r = match(\"2024-01\", \"([0-9]+)-([0-9]+)\", 1)").getVariable("&r")!!.asString())
        assertEquals("2024-01", exec("&r = match(\"2024-01\", \"([0-9]+)-([0-9]+)\", 0)").getVariable("&r")!!.asString())
    }

    @Test fun `match returns the default when nothing matches`() {
        // no digit in "abc" -> the default param (ScriptActionMatch:89); without one -> empty
        assertEquals("none", exec("&r = match(\"abc\", \"[0-9]+\", 0, \"none\")").getVariable("&r")!!.asString())
        assertEquals("", exec("&r = match(\"abc\", \"[0-9]+\")").getVariable("&r")!!.asString())
    }

    @Test fun `indexof finds an element in an array`() {
        val r = VariableRegistry()
        r.push("&a", Value.Str("alpha")); r.push("&a", Value.Str("beta")); r.push("&a", Value.Str("gamma"))
        assertEquals(1, exec("&i = indexof(&a, \"beta\")", r).getVariable("&i")!!.asInt())
        assertEquals(-1, exec("&i = indexof(&a, \"delta\")", r).getVariable("&i")!!.asInt())
        // MKB getArrayIndexOf default caseSensitive=false -> case-insensitive
        assertEquals(2, exec("&i = indexof(&a, \"GAMMA\")", r).getVariable("&i")!!.asInt())
        // arg2 = true -> case-sensitive, so the upper-case needle misses
        assertEquals(-1, exec("&i = indexof(&a, \"GAMMA\", true)", r).getVariable("&i")!!.asInt())
    }

    @Test fun `indexof on non-array text does a substring search`() {
        assertEquals(2, exec("#i = indexof(\"hello\", \"ll\")").getVariable("#i")!!.asInt())
        assertEquals(-1, exec("#i = indexof(\"hello\", \"z\")").getVariable("#i")!!.asInt())
    }

    @Test fun `ifcontains gates on substring`() {
        assertEquals(listOf("yes"), runScript("ifcontains(\"hello\", \"ell\"); log(\"yes\"); else; log(\"no\"); endif").logs)
        assertEquals(listOf("no"), runScript("ifcontains(\"hello\", \"xyz\"); log(\"yes\"); else; log(\"no\"); endif").logs)
        // case-insensitive (ScriptActionIfContains lowercases both operands)
        assertEquals(listOf("yes"), runScript("ifcontains(\"Hello\", \"ELL\"); log(\"yes\"); else; log(\"no\"); endif").logs)
    }

    @Test fun `ifbeginswith and ifendswith`() {
        assertEquals(listOf("b"), runScript("ifbeginswith(\"hello\", \"he\"); log(\"b\"); endif").logs)
        assertEquals(listOf("e"), runScript("ifendswith(\"hello\", \"lo\"); log(\"e\"); endif").logs)
        // case-insensitive AND trimmed (both originals do .toLowerCase().trim())
        assertEquals(listOf("b"), runScript("ifbeginswith(\"  HELLO\", \"he\"); log(\"b\"); endif").logs)
        assertEquals(listOf("e"), runScript("ifendswith(\"HELLO  \", \"lo\"); log(\"e\"); endif").logs)
    }

    @Test fun `ifmatches gates on a regex`() {
        assertEquals(listOf("m"), runScript("ifmatches(\"abc123\", \"[0-9]+\"); log(\"m\"); endif").logs)
        assertTrue(runScript("ifmatches(\"abc\", \"[0-9]+\"); log(\"m\"); endif").logs.isEmpty())
        // case-insensitive (IfMatches compiles with Pattern.compile(pattern, 2))
        assertEquals(listOf("m"), runScript("ifmatches(\"HELLO\", \"[a-z]+\"); log(\"m\"); endif").logs)
    }

    @Test fun `toggle flips a flag`() {
        assertTrue(exec("toggle(ready)").getVariable("ready")!!.asBoolean())
        assertEquals(false, exec("toggle(ready); toggle(ready)").getVariable("ready")!!.asBoolean())
    }

    @Test fun `split builds an array`() {
        // delimiter-first arg order, matching ScriptActionSplit + ACTIONS.md:70 / DSL-REFERENCE.md:798
        val r = exec("&p[] = split(\",\", \"a,b,c\")")
        assertEquals(listOf("a", "b", "c"), r.arrayValues("&p[]").map { it.asString() })
        // delimiter is split LITERALLY (regex metachars are not special)
        val d = exec("&q[] = split(\".\", \"a.b.c\")")
        assertEquals(listOf("a", "b", "c"), d.arrayValues("&q[]").map { it.asString() })
    }

    @Test fun `pass is a no-op`() {
        assertEquals(listOf("after"), runScript("pass; log(\"after\")").logs)
    }

    @Test fun `stop ends the macro`() {
        assertEquals(listOf("a"), runScript("log(\"a\"); stop; log(\"b\")").logs)
    }

    @Test fun `stop exits a loop cleanly`() {
        assertEquals(listOf("x"), runScript("do; log(\"x\"); stop; loop").logs)
    }

    @Test fun `sqrt is an integer square root`() {
        assertEquals(4, exec("#r = sqrt(16)").getVariable("#r")!!.asInt())
        assertEquals(1, exec("#r = sqrt(2)").getVariable("#r")!!.asInt())   // truncated (int value model)
        assertEquals(0, exec("#r = sqrt(-4)").getVariable("#r")!!.asInt())  // negatives clamp to 0
    }

    @Test fun `strip removes section formatting codes`() {
        assertEquals("hello", exec("&r = strip(\"§ahel§rlo\")").getVariable("&r")!!.asString())
    }

    @Test fun `encode then decode round-trips`() {
        assertEquals("aGk=", exec("&r = encode(\"hi\")").getVariable("&r")!!.asString())
        assertEquals("hi", exec("&r = decode(\"aGk=\")").getVariable("&r")!!.asString())
        assertEquals("round trip", exec("&e = encode(\"round trip\"); &r = decode(\"%&e%\")").getVariable("&r")!!.asString())
    }

    @Test fun `decode of invalid base64 is empty`() {
        assertEquals("", exec("&r = decode(\"!!!notbase64!!!\")").getVariable("&r")!!.asString())
    }

    @Test fun `time formats the current date`() {
        val year = exec("&r = time(\"yyyy\")").getVariable("&r")!!.asString()
        assertTrue(year.matches(Regex("\\d{4}")), "expected a 4-digit year, got '$year'")
        assertTrue(exec("&r = time()").getVariable("&r")!!.asString().isNotEmpty())
    }
}
