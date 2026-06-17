package dev.macromod.engine.parser

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.ast.Instruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserTest {
    private val host = ScriptHost()
    private fun compile(src: String) = host.compile(src).program
    private fun invoke(src: String) = compile(src).first() as Instruction.Invoke

    @Test fun `plain text becomes a chat line`() {
        val program = compile("hello world")
        assertEquals(listOf(Instruction.ChatLine("hello world")), program)
    }

    @Test fun `script island parses to an action invoke`() {
        val inv = invoke("\$\${ log(\"hi\") }\$\$")
        assertEquals("log", inv.action.name)
        assertEquals("hi", inv.args[0])
    }

    @Test fun `mixed chat and script split positionally`() {
        val program = compile("a \$\${ log(\"x\") }\$\$ b")
        assertEquals(3, program.size)
        assertEquals(Instruction.ChatLine("a "), program[0])
        assertTrue(program[1] is Instruction.Invoke)
        assertEquals(Instruction.ChatLine(" b"), program[2])
    }

    @Test fun `colon-equals maps to set with two args`() {
        val inv = invoke("\$\${ #x := 5 }\$\$")
        assertEquals("set", inv.action.name)
        assertEquals("#x", inv.args[0])
        assertEquals("5", inv.args[1])
    }

    @Test fun `equals maps to assign with the raw expression`() {
        val inv = invoke("\$\${ #x = 2 + 3 }\$\$")
        assertEquals("assign", inv.action.name)
        assertEquals("2 + 3", inv.args[1])
    }

    @Test fun `rhs action call captures into out variable`() {
        val inv = invoke("\$\${ &y = lcase(\"AB\") }\$\$")
        assertEquals("lcase", inv.action.name)
        assertEquals("&y", inv.outVar)
        assertEquals("AB", inv.args[0])
    }

    @Test fun `statements split on semicolons`() {
        assertEquals(2, compile("\$\${ log(\"a\"); log(\"b\") }\$\$").size)
    }

    @Test fun `comments and empty statements are skipped`() {
        assertEquals(1, compile("\$\${ // note\n log(\"a\"); ; }\$\$").size)
    }

    @Test fun `unknown keyword compiles to a no-op invoke`() {
        assertEquals("florp", invoke("\$\${ florp(1) }\$\$").action.name)
    }

    @Test fun `bare directive has no args`() {
        val inv = invoke("\$\${ break }\$\$")
        assertEquals("break", inv.action.name)
        assertTrue(inv.args.isEmpty())
    }

    @Test fun `quoted arguments keep embedded commas`() {
        val inv = invoke("\$\${ log(\"a, b, c\") }\$\$")
        assertEquals(1, inv.args.size)
        assertEquals("a, b, c", inv.args[0])
    }
}
