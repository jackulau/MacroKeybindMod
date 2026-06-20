package dev.macromod.engine.action

import dev.macromod.engine.runScript
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionTest {
    private fun exec(body: String): VariableRegistry {
        val r = VariableRegistry()
        runScript(body, r)
        return r
    }

    @Test fun `iif returns the chosen branch`() {
        assertEquals("a", exec("&r = iif(1, \"a\", \"b\")").getVariable("&r")!!.asString())
        assertEquals("b", exec("&r = iif(0, \"a\", \"b\")").getVariable("&r")!!.asString())
    }

    @Test fun `set then assign with an expression`() {
        assertEquals(20, exec("#x := 10; #y = #x * 2").getVariable("#y")!!.asInt())
    }

    @Test fun `set with the value omitted sets the target TRUE`() {
        // ACTIONS.md:59 — SET(<target>,[value]): "Set target to value (or TRUE if omitted)".
        assertEquals(true, exec("set(ready)").getVariable("ready")!!.asBoolean())
        // and the flag must be usable as a truthy condition immediately afterwards
        assertEquals("yes", exec("set(go); if(go); &r := \"yes\"; endif").getVariable("&r")?.asString())
        // a value, when present, still wins
        assertEquals("x", exec("set(&s, \"x\")").getVariable("&s")!!.asString())
    }

    @Test fun `inc and dec with default and explicit amounts`() {
        // 5 → +1 → +3 → −1 = 8
        assertEquals(8, exec("#n := 5; inc(#n); inc(#n, 3); dec(#n)").getVariable("#n")!!.asInt())
    }

    @Test fun `bare inc and dec default to the counter variable`() {
        // ScriptActionInc:19 / DSL-REFERENCE.md:784 — the counter defaults to `#counter` (step 1),
        // and a bare `inc`/`dec` must not crash on the empty arg list.
        assertEquals(1, exec("inc").getVariable("#counter")!!.asInt())
        assertEquals(-1, exec("dec").getVariable("#counter")!!.asInt())
        assertEquals(2, exec("inc; inc; inc; dec").getVariable("#counter")!!.asInt())
    }

    @Test fun `string case and length`() {
        val r = exec("&u = ucase(\"hi\"); &l = lcase(\"HI\"); #len = length(\"hello\")")
        assertEquals("HI", r.getVariable("&u")!!.asString())
        assertEquals("hi", r.getVariable("&l")!!.asString())
        assertEquals(5, r.getVariable("#len")!!.asInt())
    }

    @Test fun `replace and indexof`() {
        val r = exec("&s = replace(\"a-b-c\", \"-\", \"+\"); #i = indexof(\"hello\", \"ll\")")
        assertEquals("a+b+c", r.getVariable("&s")!!.asString())
        assertEquals(2, r.getVariable("#i")!!.asInt())
    }

    @Test fun `calc evaluates an expression`() {
        assertEquals(14, exec("#r = calc(\"2 + 3 * 4\")").getVariable("#r")!!.asInt())
    }

    @Test fun `log goes to the log sink`() {
        assertEquals(listOf("hello"), runScript("log(\"hello\")").logs)
    }

    @Test fun `sendmessage goes to chat`() {
        assertEquals(listOf("/hi"), runScript("sendmessage(\"/hi\")").chats)
    }

    @Test fun `echo sends to the server as a chat packet`() {
        // Decompiled ScriptActionEcho returns ReturnValueChat (perm group "chat") — echo is a
        // server send, NOT a local log. `log` remains the local-only option.
        assertEquals(listOf("hello"), runScript("echo(\"hello\")").chats)
        assertEquals(emptyList(), runScript("echo(\"hello\")").logs)
    }

    @Test fun `array push size and pop`() {
        val r = exec("push(&a[], \"x\"); push(&a[], \"y\"); #n = arraysize(&a[]); &p = pop(&a[])")
        assertEquals(2, r.getVariable("#n")!!.asInt())
        assertEquals("y", r.getVariable("&p")!!.asString())
    }
}
