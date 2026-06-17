package dev.macromod.engine.runtime

import dev.macromod.engine.runMacro
import dev.macromod.engine.runScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeTest {
    @Test fun `if-true runs the then branch`() {
        assertEquals(listOf("yes"), runScript("if(1==1); log(\"yes\"); else; log(\"no\"); endif").logs)
    }

    @Test fun `if-false runs the else branch`() {
        assertEquals(listOf("no"), runScript("if(2==1); log(\"yes\"); else; log(\"no\"); endif").logs)
    }

    @Test fun `elseif chain picks the first match`() {
        val out = runScript("#x := 2; if(#x==1); log(\"one\"); elseif(#x==2); log(\"two\"); else; log(\"other\"); endif")
        assertEquals(listOf("two"), out.logs)
    }

    @Test fun `do-while loops until the condition is false`() {
        assertEquals(listOf("1", "2", "3"), runScript("#i := 0; do; inc(#i); log(\"%#i%\"); while(#i < 3)").logs)
    }

    @Test fun `for counts inclusively`() {
        assertEquals(listOf("1", "2", "3"), runScript("for(#i, 1, 3); log(\"%#i%\"); next").logs)
    }

    @Test fun `for with an empty range runs zero times`() {
        assertTrue(runScript("for(#i, 5, 1); log(\"x\"); next").logs.isEmpty())
    }

    @Test fun `for with a negative step counts down`() {
        assertEquals(listOf("3", "2", "1"), runScript("for(#i, 3, 1, -1); log(\"%#i%\"); next").logs)
    }

    @Test fun `foreach iterates array elements`() {
        val out = runScript("push(&a[], \"x\"); push(&a[], \"y\"); foreach(&e, &a[]); log(\"%&e%\"); next")
        assertEquals(listOf("x", "y"), out.logs)
    }

    @Test fun `break exits the innermost loop`() {
        val out = runScript("for(#i, 1, 10); if(#i==3); break; endif; log(\"%#i%\"); next")
        assertEquals(listOf("1", "2"), out.logs)
    }

    @Test fun `nested conditionals gate correctly`() {
        val out = runScript("#x := 5; if(#x > 0); if(#x > 10); log(\"big\"); else; log(\"small\"); endif; endif")
        assertEquals(listOf("small"), out.logs)
    }

    @Test fun `chat line is sent with variable expansion`() {
        assertEquals(listOf("/msg bob"), runMacro("\$\${ &n := \"bob\" }\$\$/msg %&n%").chats)
    }

    @Test fun `unterminated block throws`() {
        assertFailsWith<ScriptException> { runScript("do; log(\"x\")") }
    }

    @Test fun `infinite loop is bounded by the step limit`() {
        assertFailsWith<ScriptException> { runScript("do; loop") }
    }
}
