package dev.macromod.engine.macro

import dev.macromod.engine.action.OutputSink
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Allocation regression guard for the per-tick input poll [MacroEngine.tickKeys].
 *
 * The host calls tickKeys ~20x/second whenever input bindings exist and no screen is open, for the
 * whole session — so it must not allocate per tick. Before this guard it did: `macros.all()` returns a
 * defensive COPY of the bindings list (correct for external callers, wasteful here), so every tick
 * allocated a fresh ArrayList + backing array. The fix snapshots into a reused scratch buffer
 * (preserving the copy's isolation from a fired bind/unbind mutating the list mid-tick) so it drops to
 * ~0 B/call once the buffer capacity stabilizes. Mirrors ParamProcessAllocTest's measurement pattern.
 */
class MacroEngineTickAllocTest {
    private object NullSink : OutputSink {
        override fun chat(message: String) {}
        override fun log(message: String) {}
        override fun clearChat() {}
        override fun logRaw(json: String) {}
        override fun logTo(target: String, text: String) {}
        override fun selectChannel(channel: String) {}
    }

    @Test fun `tickKeys is allocation-free on the steady-state input poll`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val engine = MacroEngine()
        // a realistic spread of enabled key + mouse bindings (none is "down", so none fires)
        for (k in 0 until 12) engine.macros.add(MacroBinding(Trigger.Key(65 + k), "\$\${ log(\"x\") }\$\$"))
        for (b in 0 until 4) engine.macros.add(MacroBinding(Trigger.Mouse(b), "\$\${ log(\"m\") }\$\$"))
        val down: (Trigger) -> Boolean = { false } // allocated once; nothing pressed -> no script fires
        var now = 0L
        repeat(200_000) { engine.tickKeys(now++, NullSink, pressed = down) } // warm JIT + populate keyStates
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { engine.tickKeys(now++, NullSink, pressed = down) }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        // 16 bindings: the old all() copy cost ~16*4 + ArrayList/array overhead (~80 B/call). The reused
        // scratch buffer drops it to ~0; assert well under the old level (neg-control: reverting to all() fails).
        assertTrue(perCall < 16.0, "steady-state tickKeys should be ~0 B/call, was $perCall")
    }

    @Test fun `fireEvent dispatch is allocation-free on the per-tick onTick path`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val engine = MacroEngine()
        // Empty-script onTick bindings: fireEvent dispatches them, but run() returns immediately on the
        // empty script, so the measurement isolates the LOOKUP allocation (the old forEvent filter — a
        // fresh ArrayList + a capturing lambda per call) from script execution. The host fires onTick
        // every tick whenever onTick bindings exist (MacroModClient:553), so this path must not allocate.
        for (i in 0 until 2) engine.macros.add(MacroBinding(Trigger.Event("onTick"), ""))
        for (k in 0 until 14) engine.macros.add(MacroBinding(Trigger.Key(65 + k), "")) // non-matching noise
        repeat(200_000) { engine.fireEvent("onTick", NullSink) } // warm JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { engine.fireEvent("onTick", NullSink) }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        // The old forEvent filter allocated a result list + lambda every tick; the reused eventScratch
        // buffer + inline match drops it to ~0 (neg-control: reverting to forEvent fails this).
        assertTrue(perCall < 16.0, "steady-state onTick dispatch should be ~0 B/call, was $perCall")
    }

    @Test fun `tickWaits is allocation-free while scripts are parked`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val engine = MacroEngine()
        // Park 3 scripts on a wait far longer than the measurement, so tickWaits keeps snapshotting the
        // pending list every call without ever resuming. A wait-heavy macro (act; wait; act; wait) leaves
        // a script parked most ticks, so this per-tick snapshot must not allocate.
        engine.macros.add(MacroBinding(Trigger.Event("go"), "\$\${ wait(\"99999999t\") }\$\$"))
        repeat(3) { engine.fireEvent("go", NullSink) }
        repeat(200_000) { engine.tickWaits() } // warm JIT; decrements countdown but never resumes
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { engine.tickWaits() }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        // The old `pending.toList()` snapshot allocated a fresh list every parked tick; the reused buffer
        // drops it to ~0 (neg-control: reverting to pending.toList() fails this).
        assertTrue(perCall < 16.0, "steady-state parked tickWaits should be ~0 B/call, was $perCall")
    }
}
