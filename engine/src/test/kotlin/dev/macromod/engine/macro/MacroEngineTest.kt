package dev.macromod.engine.macro

import dev.macromod.engine.RecordingOutput
import kotlin.test.Test
import kotlin.test.assertEquals

class MacroEngineTest {
    @Test fun `firing a key runs its bound macro end to end`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Key(65), "\$\${ log(\"hit A\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Key(66), "\$\${ log(\"hit B\") }\$\$"))

        val out = RecordingOutput()
        engine.fireKey(65, out)
        assertEquals(listOf("hit A"), out.logs)
    }

    @Test fun `a key with multiple bindings runs all of them`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Key(1), "\$\${ log(\"one\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Key(1), "\$\${ log(\"two\") }\$\$"))

        val out = RecordingOutput()
        engine.fireKey(1, out)
        assertEquals(listOf("one", "two"), out.logs)
    }

    @Test fun `shared state persists across firings`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ inc(@#ticks) }\$\$"))

        val out = RecordingOutput()
        engine.fireEvent("ontick", out)
        engine.fireEvent("onTick", out) // case-insensitive
        engine.fireEvent("ONTICK", out)

        assertEquals(3, engine.variables.getVariable("@#ticks")!!.asInt())
    }

    @Test fun `a chat-line macro reaches the chat sink`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Key(7), "/warp home"))

        val out = RecordingOutput()
        engine.fireKey(7, out)
        assertEquals(listOf("/warp home"), out.chats)
    }

    @Test fun `wait suspends a macro and tickWaits resumes it after the delay`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Event("go"), "\$\${ log(\"a\"); wait(\"3t\"); log(\"b\") }\$\$"))

        val out = RecordingOutput()
        engine.fireEvent("go", out)
        assertEquals(listOf("a"), out.logs)          // ran up to the wait, then suspended
        assertEquals(1, engine.pendingWaits)

        engine.tickWaits()                           // 3 -> 2
        engine.tickWaits()                           // 2 -> 1
        assertEquals(listOf("a"), out.logs)          // still waiting
        assertEquals(1, engine.pendingWaits)

        engine.tickWaits()                           // 1 -> 0 -> resume
        assertEquals(listOf("a", "b"), out.logs)
        assertEquals(0, engine.pendingWaits)
    }

    @Test fun `a wait-free macro completes immediately and is never parked`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Event("go"), "\$\${ log(\"done\") }\$\$"))
        val out = RecordingOutput()
        engine.fireEvent("go", out)
        assertEquals(listOf("done"), out.logs)
        assertEquals(0, engine.pendingWaits)
    }
}
