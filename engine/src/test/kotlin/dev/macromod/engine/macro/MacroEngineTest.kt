package dev.macromod.engine.macro

import dev.macromod.engine.RecordingOutput
import dev.macromod.engine.action.OutputSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun `a resumed macro re-entering the engine does not throw a concurrent modification`() {
        val engine = MacroEngine()
        // Outer: park one tick, then emit output on resume. The re-entering binding parks too, so
        // firing it during the resume adds to `pending` while tickWaits is iterating it.
        engine.macros.add(MacroBinding(Trigger.Event("go"), "\$\${ wait(\"1t\"); log(\"resumed\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Event("reenter"), "\$\${ wait(\"5t\") }\$\$"))

        var reentered = false
        // Mirrors the Fabric chat sink firing a synchronous event back into the engine.
        val sink = object : OutputSink {
            override fun chat(message: String) {}
            override fun log(message: String) {
                if (!reentered) { reentered = true; engine.fireEvent("reenter", this) }
            }
            override fun clearChat() {}
            override fun logRaw(json: String) {}
            override fun logTo(target: String, text: String) {}
            override fun selectChannel(channel: String) {}
        }

        engine.fireEvent("go", sink)          // outer parks on wait(1t)
        assertEquals(1, engine.pendingWaits)
        engine.tickWaits()                     // resume outer -> log -> re-enter -> parks a new wait
        // Pre-fix this threw ConcurrentModificationException from the live iterator.
        assertTrue(reentered)
        assertEquals(1, engine.pendingWaits)   // outer finished; the re-entered wait(5t) stays parked
    }
}
