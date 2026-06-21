package dev.macromod.engine.parser

import dev.macromod.engine.RecordingOutput
import dev.macromod.engine.ScriptHost
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModernParserTest {
    private val host = ScriptHost()

    private fun runModern(src: String, registry: VariableRegistry = VariableRegistry()): RecordingOutput {
        val out = RecordingOutput()
        host.compileModern(src).run(host, out, registry)
        return out
    }

    @Test fun `transpiles if-else to the legacy form`() {
        val t = ModernTranspiler().transpile("if x > 3 {\n  log(\"big\")\n} else {\n  log(\"small\")\n}")
        assertEquals("if(x > 3);log(\"big\");else;log(\"small\");endif;", t)
    }

    @Test fun `if-else runs the correct branch`() {
        val out = runModern("#x = 5\nif #x > 3 {\n  log(\"big\")\n} else {\n  log(\"small\")\n}")
        assertEquals(listOf("big"), out.logs)
    }

    @Test fun `elseif chain picks the first match`() {
        val src = "#x = 2\nif #x == 1 {\n log(\"one\")\n} elseif #x == 2 {\n log(\"two\")\n} else {\n log(\"three\")\n}"
        assertEquals(listOf("two"), runModern(src).logs)
    }

    @Test fun `repeat runs a counted loop`() {
        assertEquals(listOf("hi", "hi", "hi"), runModern("repeat 3 {\n log(\"hi\")\n}").logs)
    }

    @Test fun `while is a pre-check loop`() {
        val src = "#i = 0\nwhile #i < 3 {\n #i = #i + 1\n log(\"%#i%\")\n}"
        assertEquals(listOf("1", "2", "3"), runModern(src).logs)
    }

    @Test fun `while runs zero times when initially false`() {
        assertTrue(runModern("#i = 5\nwhile #i < 3 {\n log(\"x\")\n}").logs.isEmpty())
    }

    @Test fun `foreach iterates an array`() {
        val r = VariableRegistry()
        r.push("&a[]", Value.Str("x"))
        r.push("&a[]", Value.Str("y"))
        assertEquals(listOf("x", "y"), runModern("foreach &e in &a[] {\n log(\"%&e%\")\n}", r).logs)
    }

    @Test fun `nested repeat inside if`() {
        val src = "#x = 1\nif #x == 1 {\n repeat 2 {\n log(\"a\")\n }\n}"
        assertEquals(listOf("a", "a"), runModern(src).logs)
    }

    @Test fun `forever with break`() {
        val src = "#i = 0\nforever {\n #i = #i + 1\n if #i == 3 {\n break\n }\n log(\"%#i%\")\n}"
        assertEquals(listOf("1", "2"), runModern(src).logs)
    }

    @Test fun `nested repeat loops use isolated counters`() {
        // The transpiler assigns each repeat its own synthetic loop var (#__loop0, #__loop1, …) via an
        // incrementing counter; if two nested repeats shared a var the inner loop would clobber the
        // outer's count. Outer 2 x inner 3 must produce exactly 6 iterations.
        val out = runModern("repeat 2 {\n repeat 3 {\n log(\"x\")\n }\n}")
        assertEquals(List(6) { "x" }, out.logs)
    }

    @Test fun `a break inside a nested while exits only the inner loop`() {
        // Legacy break targets the innermost loop, so the inner while's break must not terminate the
        // outer loop. Outer runs twice; each inner pass logs once (i==1) then breaks at i==2. If break
        // escaped to the outer loop the result would be just ["o1i1"].
        val src = "#o = 0\n" +
            "while #o < 2 {\n" +
            " #o = #o + 1\n" +
            " #i = 0\n" +
            " while #i < 5 {\n" +
            "  #i = #i + 1\n" +
            "  if #i == 2 {\n break\n }\n" +
            "  log(\"o%#o%i%#i%\")\n" +
            " }\n" +
            "}"
        assertEquals(listOf("o1i1", "o2i1"), runModern(src).logs)
    }
}
