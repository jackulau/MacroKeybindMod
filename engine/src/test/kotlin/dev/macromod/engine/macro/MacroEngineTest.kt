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

    @Test fun `the running iterator exposes wait-suspended macros as multi-var bundles`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Event("go"), "\$\${ wait(\"5t\") }\$\$", name = "Waiter"))
        val out = RecordingOutput()

        // Nothing parked yet -> running is empty (exercises the bundle-provider path, not the array fallback).
        assertEquals(emptyList(), engine.variables.iteratorBundles("running")?.map { it.loopValue.asString() })

        engine.fireEvent("go", out)                  // suspends on the wait -> parked in `pending`
        assertEquals(1, engine.pendingWaits)
        val parked = engine.variables.iteratorBundles("running")
        // The loop var binds MACRONAME (so single-var `foreach(&m, running)` is unchanged)...
        assertEquals(listOf("Waiter"), parked?.map { it.loopValue.asString() })
        // ...and the three MKB fixed-name vars resolve (ScriptedIteratorRunning: MACROID/MACRONAME/MACROTIME).
        val vars = parked!!.single().vars
        assertEquals("0", vars["MACROID"]?.asString())      // the binding's index in the active registry
        assertEquals("Waiter", vars["MACRONAME"]?.asString())
        assertEquals("0", vars["MACROTIME"]?.asString())    // not yet ticked -> 0 ms

        repeat(2) { engine.tickWaits() }             // 2 of the 5 ticks elapse -> still parked, run-time accrues
        assertEquals(1, engine.pendingWaits)
        assertEquals("100", engine.variables.iteratorBundles("running")!!.single().vars["MACROTIME"]?.asString())  // 2 ticks * 50 ms

        repeat(3) { engine.tickWaits() }             // remaining 3 ticks elapse -> macro finishes, unparked
        assertEquals(0, engine.pendingWaits)
        assertEquals(emptyList(), engine.variables.iteratorBundles("running")?.map { it.loopValue.asString() })
    }

    // --- CONDITIONAL mode (goal 090): a per-press if/else branch (MKB preCompileConditionalMacro) ---

    @Test fun `a conditional macro runs its main script when the condition holds`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(50), "\$\${ log(\"yes\") }\$\$",
            mode = PlaybackMode.CONDITIONAL,
            keyUpScript = "\$\${ log(\"no\") }\$\$",
            condition = "1 == 1",
        ))
        val out = RecordingOutput()
        engine.fireKey(50, out)
        assertEquals(listOf("yes"), out.logs)   // condition true -> main script, not the else branch
    }

    @Test fun `a conditional macro runs its else script when the condition fails`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(51), "\$\${ log(\"yes\") }\$\$",
            mode = PlaybackMode.CONDITIONAL,
            keyUpScript = "\$\${ log(\"no\") }\$\$",
            condition = "1 == 2",
        ))
        val out = RecordingOutput()
        engine.fireKey(51, out)
        assertEquals(listOf("no"), out.logs)     // condition false -> the key-up (else) branch
    }

    @Test fun `a conditional macro with a blank condition behaves as a one-shot (runs the main script)`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(52), "\$\${ log(\"main\") }\$\$",
            mode = PlaybackMode.CONDITIONAL,
            keyUpScript = "\$\${ log(\"else\") }\$\$",
        ))
        val out = RecordingOutput()
        engine.fireKey(52, out)
        assertEquals(listOf("main"), out.logs)   // blank condition holds -> main script
    }

    // --- KEYSTATE mode (goal 090): key-down on press, key-held repeated while held (throttled by
    //     repeatRateMs), key-up on release. Driven per-tick by tickKeys with an injected clock. ---

    @Test fun `keystate runs key-down and (lastTrigger=0) key-held on the press tick`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(70), "\$\${ log(\"down\") }\$\$",
            mode = PlaybackMode.KEYSTATE,
            keyHeldScript = "\$\${ log(\"held\") }\$\$",
            keyUpScript = "\$\${ log(\"up\") }\$\$",
            repeatRateMs = 100,
        ))
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Key(70) }   // press: key-down + key-held both fire (MKB lastTriggerTime=0)
        assertEquals(listOf("down", "held"), out.logs)
    }

    @Test fun `keystate repeats the held script only after the repeat rate elapses`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(71), "\$\${ log(\"down\") }\$\$",
            mode = PlaybackMode.KEYSTATE,
            keyHeldScript = "\$\${ log(\"held\") }\$\$",
            repeatRateMs = 100,
        ))
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Key(71) }                // press: down + held (lastTrigger -> 1000)
        engine.tickKeys(1050, out) { it == Trigger.Key(71) }                // +50ms < 100 -> no held
        engine.tickKeys(1150, out) { it == Trigger.Key(71) }                // 150ms since last fire > 100 -> held
        assertEquals(listOf("down", "held", "held"), out.logs)
    }

    @Test fun `keystate runs the key-up script once on release`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Key(72), "\$\${ log(\"down\") }\$\$",
            mode = PlaybackMode.KEYSTATE,
            keyUpScript = "\$\${ log(\"up\") }\$\$",
            repeatRateMs = 100,
        ))
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Key(72) }   // press: down (no key-held script -> empty run no-ops)
        engine.tickKeys(1050, out) { false }                   // release: up
        engine.tickKeys(1100, out) { false }                   // still up: nothing more
        assertEquals(listOf("down", "up"), out.logs)
    }

    @Test fun `tickKeys fires a one-shot binding once per press, not every tick while held`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Key(73), "\$\${ log(\"x\") }\$\$"))   // ONESHOT (default)
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Key(73) }                // press -> fire
        engine.tickKeys(1050, out) { it == Trigger.Key(73) }                // still held -> NO refire
        engine.tickKeys(1100, out) { false }                   // release -> nothing
        engine.tickKeys(1150, out) { it == Trigger.Key(73) }                // press again -> fire
        assertEquals(listOf("x", "x"), out.logs)
    }

    @Test fun `hasInputBindings reflects whether the active config has any enabled key or mouse trigger`() {
        val engine = MacroEngine()
        assertEquals(false, engine.macros.hasInputBindings())
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ log(\"e\") }\$\$"))
        assertEquals(false, engine.macros.hasInputBindings())   // event triggers don't count
        engine.macros.add(MacroBinding(Trigger.Key(80), "\$\${ log(\"k\") }\$\$"))
        assertTrue(engine.macros.hasInputBindings())            // a key trigger counts
        engine.macros.clear()
        engine.macros.add(MacroBinding(Trigger.Mouse(0), "\$\${ log(\"m\") }\$\$"))
        assertTrue(engine.macros.hasInputBindings())            // a mouse trigger counts too
    }

    // --- Mouse-button triggers (goal 091): a mouse button is a first-class trigger, driven through the
    //     SAME tickKeys state machine as keys via a trigger-aware poll (so all 3 playback modes work). ---

    @Test fun `a mouse-button binding fires once per press when driven by a mouse poll`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Mouse(3), "\$\${ log(\"m4\") }\$\$"))   // GLFW button 3 (MOUSE4), ONESHOT
        engine.macros.add(MacroBinding(Trigger.Key(3), "\$\${ log(\"key3\") }\$\$"))   // SAME int, keyboard -> must NOT fire on a mouse poll
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Mouse(3) }  // press the button
        engine.tickKeys(1050, out) { it == Trigger.Mouse(3) }  // still held -> NO refire (ONESHOT)
        engine.tickKeys(1100, out) { false }                   // release
        engine.tickKeys(1150, out) { it == Trigger.Mouse(3) }  // press again -> fire
        assertEquals(listOf("m4", "m4"), out.logs)             // fired twice; the Key(3) binding never fired (poll is trigger-aware)
    }

    @Test fun `fireMouse runs a mouse-bound macro and ignores a same-code key binding`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Mouse(1), "\$\${ log(\"rmouse\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Key(1), "\$\${ log(\"key1\") }\$\$"))
        val out = RecordingOutput()
        engine.fireMouse(1, out)
        assertEquals(listOf("rmouse"), out.logs)
    }

    @Test fun `a keystate mouse binding hold-repeats like a key (the state machine is input-agnostic)`() {
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(
            Trigger.Mouse(4), "\$\${ log(\"down\") }\$\$",
            mode = PlaybackMode.KEYSTATE,
            keyHeldScript = "\$\${ log(\"held\") }\$\$",
            keyUpScript = "\$\${ log(\"up\") }\$\$",
            repeatRateMs = 100,
        ))
        val out = RecordingOutput()
        engine.tickKeys(1000, out) { it == Trigger.Mouse(4) }  // press: down + held (lastTrigger 0)
        engine.tickKeys(1050, out) { it == Trigger.Mouse(4) }  // +50 < 100 -> no held
        engine.tickKeys(1150, out) { it == Trigger.Mouse(4) }  // >100 since last -> held
        engine.tickKeys(1200, out) { false }                   // release -> up
        assertEquals(listOf("down", "held", "held", "up"), out.logs)
    }
}
