package dev.macromod.engine.action

import dev.macromod.engine.runScript
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskConfigActionTest {

    @Test fun `store appends and storeover replaces the list`() {
        val r = VariableRegistry()
        runScript("store(\"&q[]\", \"a\"); store(\"&q[]\", \"b\")", r)
        assertEquals(listOf("a", "b"), r.arrayValues("&q[]").map { it.asString() })
        runScript("storeover(\"&q[]\", \"z\")", r)
        assertEquals(listOf("z"), r.arrayValues("&q[]").map { it.asString() })
    }

    @Test fun `isrunning reports false (no async run tracker yet)`() {
        val r = VariableRegistry()
        runScript("ok = isrunning(\"foo\")", r)
        assertEquals(false, r.getVariable("ok")!!.asBoolean())
    }

    @Test fun `exec is recognised and reports its invocation`() {
        assertEquals(listOf("[exec] greet.txt"), runScript("exec(\"greet.txt\")").logs)
    }
}
