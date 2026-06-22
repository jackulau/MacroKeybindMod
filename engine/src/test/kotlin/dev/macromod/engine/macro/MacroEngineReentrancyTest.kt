package dev.macromod.engine.macro

import dev.macromod.engine.action.OutputSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the `fireEvent` re-entrancy hole.
 *
 * `fireEvent` snapshots the live bindings into a reused buffer (the 0-B/tick optimization, goal 096)
 * and iterates it by index. But a fired binding can synchronously send chat, and the Fabric host
 * routes that send straight back as `onSendChatMessage` -> `engine.fireEvent(...)` (a *nested* call).
 * `tickWaits` already documents this exact "a resumed macro can synchronously send chat … re-enters"
 * channel and defends against it. `fireEvent` did not: the nested call re-ran `snapshotInto` on the
 * *shared* buffer the outer loop was mid-iteration over, so if the binding set shrank during the
 * re-entry (a `bind`/`unbind`/config-switch action) the outer `for (i in buf.indices)` walked past
 * the end -> IndexOutOfBoundsException escaping into Fabric's event dispatch (client crash).
 */
class MacroEngineReentrancyTest {

    /** A sink that models the host's chat sink: the first chat send mutates the binding set and
     *  synchronously re-fires `onSendChatMessage`, exactly as MacroModClient's ClientSendMessageEvents
     *  callback does (connection.sendChat -> Fabric mixin -> fireEvent("onSendChatMessage")). */
    private class ReenteringSink(val engine: MacroEngine) : OutputSink {
        val logs = mutableListOf<String>()
        val chats = mutableListOf<String>()
        var reentered = false
        override fun log(message: String) { logs.add(message) }
        override fun chat(message: String) {
            chats.add(message)
            if (!reentered) {
                reentered = true
                // A real action (config switch / unbind) can change the binding set mid-fire.
                engine.macros.remove(engine.macros.all().last())
                // The host fires this synchronously from inside the chat send.
                engine.fireEvent("onSendChatMessage", this)
            }
        }
    }

    @Test fun `fireEvent survives a fired macro re-entering via chat while the binding set shrinks`() {
        val engine = MacroEngine()
        // An onSendChatMessage listener so the nested fire actually runs a binding.
        engine.macros.add(MacroBinding(Trigger.Event("onSendChatMessage"), "\$\${ log(\"sent\") }\$\$"))
        // onTick bindings: the first sends chat (a bare line -> ChatLine -> output.chat), which
        // re-enters; the later ones must still run from the OUTER loop's stable snapshot.
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "hello"))
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ log(\"tick2\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ log(\"tick3\") }\$\$"))

        val out = ReenteringSink(engine)
        // Before the fix this throws IndexOutOfBoundsException from the outer loop after the nested
        // fire shrank the shared buffer.
        engine.fireEvent("onTick", out)

        assertTrue(out.reentered, "the nested onSendChatMessage fire must have run")
        assertEquals(listOf("hello"), out.chats)
        // The outer snapshot isolates the loop from the mid-fire remove, so tick2 AND tick3 (the
        // already-snapshotted binding removed during re-entry) both still run exactly once, plus the
        // nested onSendChatMessage's "sent". No crash, no skipped/double-run binding.
        assertEquals(listOf("sent", "tick2", "tick3"), out.logs)
    }

    @Test fun `deeply nested fireEvent re-entries each keep their own snapshot`() {
        // Two levels of re-entry (onTick chat -> onSendChatMessage chat -> onChat) must not corrupt
        // any outer loop's snapshot. The first re-entry shrinks the set (removes a throwaway binding);
        // none of the three levels may IOOBE or drop a binding that belongs to its own snapshot.
        val engine = MacroEngine()
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "level0"))
        engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ log(\"t-tail\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Event("onSendChatMessage"), "send-level"))
        engine.macros.add(MacroBinding(Trigger.Event("onSendChatMessage"), "\$\${ log(\"s-tail\") }\$\$"))
        engine.macros.add(MacroBinding(Trigger.Event("onChat"), "\$\${ log(\"c\") }\$\$"))
        // A throwaway binding removed during the first re-entry to shrink the set without touching the
        // listeners the assertions rely on.
        val throwaway = engine.macros.add(MacroBinding(Trigger.Event("onTick"), "\$\${ log(\"throwaway\") }\$\$"))

        val out = object : OutputSink {
            val logs = mutableListOf<String>()
            override fun log(message: String) { logs.add(message) }
            override fun chat(message: String) {
                when (message) {
                    "level0" -> { engine.macros.remove(throwaway); engine.fireEvent("onSendChatMessage", this) }
                    "send-level" -> { engine.fireEvent("onChat", this) }
                }
            }
        }
        engine.fireEvent("onTick", out) // must not throw
        // t-tail (outer), s-tail (mid), c (inner) each ran once.
        assertTrue(out.logs.containsAll(listOf("t-tail", "s-tail", "c")), "all tails ran: ${out.logs}")
    }
}
