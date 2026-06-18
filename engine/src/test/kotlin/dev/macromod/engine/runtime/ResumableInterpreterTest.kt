package dev.macromod.engine.runtime

import dev.macromod.engine.RecordingOutput
import dev.macromod.engine.ScriptHost
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the interpreter is resumable: `wait` suspends and a later [Interpreter.run] resumes. */
class ResumableInterpreterTest {

    private fun interp(body: String, out: RecordingOutput): Interpreter {
        val prog = ScriptHost().compile("\$\${ $body }\$\$").program
        return Interpreter(prog, RuntimeContext(VariableRegistry(), out))
    }

    @Test fun `wait suspends, returning ticks, then resumes to completion`() {
        val out = RecordingOutput()
        val i = interp("log(\"a\"); wait(\"20t\"); log(\"b\")", out)
        assertEquals(20, i.run())            // suspended for 20 ticks
        assertEquals(listOf("a"), out.logs)  // only the pre-wait line ran
        assertEquals(-1, i.run())            // resume -> finished
        assertEquals(listOf("a", "b"), out.logs)
    }

    @Test fun `a wait-free script completes in one call`() {
        val out = RecordingOutput()
        assertEquals(-1, interp("log(\"x\")", out).run())
        assertEquals(listOf("x"), out.logs)
    }

    @Test fun `wait inside a loop suspends each iteration and keeps loop state`() {
        val out = RecordingOutput()
        val i = interp("for(#n, 1, 2); log(\"%#n%\"); wait(\"1t\"); next", out)
        assertEquals(1, i.run()); assertEquals(listOf("1"), out.logs)
        assertEquals(1, i.run()); assertEquals(listOf("1", "2"), out.logs)
        assertEquals(-1, i.run())
    }

    @Test fun `wait parses seconds and milliseconds`() {
        assertEquals(40, interp("wait(\"2\")", RecordingOutput()).run())     // 2s = 40 ticks
        assertEquals(2, interp("wait(\"100ms\")", RecordingOutput()).run())  // 100ms = 2 ticks
    }
}
