package dev.macromod.engine.parser

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the concrete RESULTS that the docs' macro examples claim in their `// ->` comments.
 * Tick 13's DocExampleTest proves the examples COMPILE to real actions; this proves they
 * PRODUCE the documented values. A wrong result comment (or an engine value regression that
 * silently diverges from the documented behaviour) fails here instead of misleading a reader.
 *
 * Each expectation is transcribed verbatim from a macro example's own result annotation in
 * the `docs/guide` tree, cited inline.
 */
class DocResultTest {
    private val host = ScriptHost()
    private fun run(src: String): VariableRegistry =
        VariableRegistry().also { host.compile(src).run(host, registry = it) }

    // dsl-language.md "Expressions vs literals": `#total = 2 + 3 * 4;  // expression -> 14`
    // The key precedence case: our Pratt evaluator gives 2 + (3*4) = 14 (MKB's no-precedence
    // split-on-first-op would give (2+3)*4 = 20). The doc claims 14 -> must match our design.
    @Test fun `documented arithmetic result is 14 (precedence honoured)`() {
        assertEquals(14, run("\$\${ #total = 2 + 3 * 4 }\$\$").getVariable("#total")!!.asInt())
    }

    // dsl-language.md "capture": `&upper = ucase("hello");  // &upper = "HELLO"`
    //                            `#where = indexof("hello", "ll");  // #where = 2`
    @Test fun `documented string + index results`() {
        val r = run("\$\${ &upper = ucase(\"hello\"); #where = indexof(\"hello\", \"ll\") }\$\$")
        assertEquals("HELLO", r.getVariable("&upper")!!.asString())
        assertEquals(2, r.getVariable("#where")!!.asInt()) // 0-indexed
    }

    // dsl-language.md "scopes": `#x := 1; @#x := 99;  // local 1, shared 99`
    @Test fun `documented local vs shared scope results`() {
        val r = run("\$\${ #x := 1; @#x := 99 }\$\$")
        assertEquals(1, r.getVariable("#x")!!.asInt())
        assertEquals(99, r.getVariable("@#x")!!.asInt())
    }

    // dsl-language.md "do-while (post-check)": `#i := 0; do; inc(#i); ...; while(#i < 3)  // 1, 2, 3`
    @Test fun `documented post-check loop counter ends at 3`() {
        assertEquals(3, run("\$\${ #i := 0; do; inc(#i); while(#i < 3) }\$\$").getVariable("#i")!!.asInt())
    }
}
