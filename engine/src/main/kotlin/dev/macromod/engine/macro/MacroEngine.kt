package dev.macromod.engine.macro

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.Navigator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.runtime.Interpreter
import dev.macromod.engine.runtime.RuntimeContext
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.IteratorBundle
import dev.macromod.engine.variable.VariableRegistry

/**
 * Ties the whole platform-agnostic stack together: a [MacroRegistry] of bindings, a
 * [ScriptHost] to compile/run their scripts, and a shared [VariableRegistry] so state
 * (especially `@shared` variables) persists across firings.
 *
 * The Fabric layer owns the loop and input: it calls [fireKey] on a key press and
 * [fireEvent] each tick / on chat / on join, passing a real [OutputSink], and [tickWaits]
 * once per client tick so `wait`-suspended scripts resume on schedule. Everything here is
 * Minecraft-free and unit-testable.
 *
 * ## Async (wait) execution
 * A bound macro runs on its own resumable [Interpreter]. If a `wait` suspends it, the
 * interpreter (pointer + operator stack preserved) is parked in [pending] with the remaining
 * tick count; [tickWaits] counts it down and resumes it when the delay elapses, re-parking it
 * if it waits again. Wait-free macros complete synchronously in [fireKey]/[fireEvent] and are
 * never parked — so existing behaviour is unchanged.
 */
class MacroEngine(
    val host: ScriptHost = ScriptHost(),
    val configs: ConfigManager = ConfigManager(),
    val variables: VariableRegistry = VariableRegistry(),
    val input: InputController = InputController.NoOp,
    val navigator: Navigator = Navigator.NoOp,
    val client: ClientBridge = ClientBridge.NoOp,
) {
    /**
     * The live binding registry: the active config profile's. Per-server switching (via
     * [ConfigManager.switchToServer] / [ConfigManager.switchTo]) swaps the active profile, so this
     * transparently points at the new profile's keybinds/events with no re-wiring at the call sites.
     */
    val macros: MacroRegistry get() = configs.active.registry

    /**
     * A macro suspended on a `wait`. [ticks] is the count remaining before it resumes; [elapsedTicks]
     * is how many client ticks it has been alive (drives MACROTIME); [id] is the binding's index in
     * the active registry (drives MACROID, mirroring MKB's per-macro getID()).
     */
    private class Pending(val interp: Interpreter, var ticks: Int, val output: OutputSink, val macroName: String, val id: Int, var elapsedTicks: Int = 0)

    private val pending = ArrayList<Pending>()

    /**
     * Per-key-binding playback state for edge detection + KEYSTATE throttling, identity-keyed so two
     * value-equal bindings don't share state. [wasDown] drives the press/release edges; [lastTriggerMs]
     * throttles the held-repeat (init 0 so the held script ALSO fires on the press tick — MKB
     * Macro.java:224, lastTriggerTime starts 0 and `now - 0 > repeatRate`).
     */
    private class KeyState(var wasDown: Boolean = false, var lastTriggerMs: Long = 0, var active: Boolean = false)
    private val keyStates = java.util.IdentityHashMap<MacroBinding, KeyState>()

    /**
     * Reused per-tick snapshot buffer for [tickKeys], so the 20Hz input poll allocates nothing once
     * its capacity stabilizes (the old `macros.all()` copy cost 104 B/tick — MacroEngineTickAllocTest).
     * Not re-entrant by design: only the host's once-per-tick tickKeys writes it, and a fired script
     * re-enters the engine through run()/events, never through tickKeys.
     */
    private val tickScratch = ArrayList<MacroBinding>()

    /**
     * Reused per-tick snapshot buffer for [fireEvent], kept separate from [tickScratch] so the two
     * paths never share state. The host fires onTick every tick whenever onTick bindings exist, and the
     * old `macros.forEvent(name)` filter allocated a result list + lambda each call (80 B/tick —
     * MacroEngineTickAllocTest). Used ONLY by the outermost (non-nested) [fireEvent]; a nested re-entry
     * takes a fresh local snapshot instead (see [inFireEvent]) so it can't clobber this buffer.
     */
    private val eventScratch = ArrayList<MacroBinding>()

    /**
     * Whether an outer [fireEvent] is mid-iteration over [eventScratch]. A fired binding can
     * synchronously send chat, which the Fabric host routes straight back as `onSendChatMessage` ->
     * [fireEvent] (a *nested* call) — the same "synchronously send chat … re-enters" channel
     * [tickWaits] documents. The nested call must NOT re-`snapshotInto(eventScratch)`, or it would
     * overwrite the outer loop's snapshot mid-walk (a mid-fire bind/unbind shrinks it -> the outer
     * `for (i in buf.indices)` indexes past the end -> IndexOutOfBoundsException into Fabric's event
     * dispatch). So the common non-nested path stays on the reused buffer at 0 B; only a rare nested
     * re-entry allocates a local snapshot.
     */
    private var inFireEvent = false

    /**
     * Reused snapshot buffer for [tickWaits]. While any script is parked on a wait, tickWaits ran
     * `pending.toList()` every tick (56 B/parked-tick — MacroEngineTickAllocTest); reusing this buffer
     * makes a wait-heavy macro's idle ticks allocation-free. Cleared after each pass so finished
     * Pendings aren't pinned. Not re-entrant: a resumed script re-enters start(), never tickWaits.
     */
    private val waitScratch = ArrayList<Pending>()

    /** How many scripts are currently suspended on a wait (for diagnostics / tests). */
    val pendingWaits: Int get() = pending.size

    init {
        // `foreach(&m, running)` enumerates the macros currently in-flight. In this synchronous
        // engine the only observably-running macros are the wait-suspended ones parked in `pending`:
        // a wait-free macro completes within its own fireKey/fireEvent call and is never parked, and
        // the macro doing the iterating is itself executing (not parked) so it never self-lists.
        // Multi-var (the goal-070 bundle mechanism), mirroring MKB's ScriptedIteratorRunning: the loop
        // var binds MACRONAME (so single-var `foreach(&m, running)` is unchanged) AND each element
        // exposes MACROID (the binding's index in the active registry), MACRONAME (display name), and
        // MACROTIME (elapsed run-time in ms = elapsedTicks*50 at 20 TPS; MKB's getRunTime() is ms).
        variables.addBundleProvider { name ->
            if (name == "running") pending.map {
                IteratorBundle(Value.Str(it.macroName), linkedMapOf(
                    "MACROID" to Value.Num(it.id),
                    "MACRONAME" to Value.Str(it.macroName),
                    "MACROTIME" to Value.Num(it.elapsedTicks * 50),
                ))
            } else null
        }
    }

    /** Fire a key press: run each enabled macro bound to [keyCode] (one-shot, or the conditional branch). */
    fun fireKey(keyCode: Int, output: OutputSink) {
        for (binding in macros.forKey(keyCode)) firePress(binding, output)
    }

    /** Fire a mouse-button press: run each enabled macro bound to [button] (the [fireKey] analogue). */
    fun fireMouse(button: Int, output: OutputSink) {
        for (binding in macros.forMouse(button)) firePress(binding, output)
    }

    /** Run every enabled macro bound to the named event (events are one-shot — playback modes are key-only). */
    fun fireEvent(name: String, output: OutputSink) {
        // Snapshot once — isolates this loop from a fired bind/unbind (run() fires synchronously) and
        // matches inline, allocation-free. The reused buffer is the OUTER loop's; a nested re-entry
        // (a fired binding sends chat -> host fires onSendChatMessage -> fireEvent) takes a local
        // snapshot so it can't clobber the buffer the outer loop is mid-walk over. See [inFireEvent].
        val nested = inFireEvent
        val buf = if (nested) ArrayList<MacroBinding>() else eventScratch.also { inFireEvent = true }
        try {
            macros.snapshotInto(buf)
            for (i in buf.indices) {
                val binding = buf[i]
                val t = binding.trigger
                if (binding.enabled && t is Trigger.Event && t.name.equals(name, ignoreCase = true)) {
                    run(binding, binding.script, output)
                }
            }
        } finally {
            if (!nested) inFireEvent = false
        }
    }

    /**
     * A key-binding's press action, dispatched by [PlaybackMode]: ONESHOT and KEYSTATE run the key-down
     * [MacroBinding.script]; CONDITIONAL evaluates its guard once (MKB `preCompileConditionalMacro` — a
     * blank condition holds, so the binding then behaves as a one-shot) and runs the main script if it
     * holds, else the key-up (else) script.
     */
    private fun firePress(binding: MacroBinding, output: OutputSink) {
        val script = if (binding.mode == PlaybackMode.CONDITIONAL) {
            val holds = binding.condition.isBlank() ||
                RuntimeContext(variables, output, input, navigator, client).evaluate(binding.condition).asBoolean()
            if (holds) binding.script else binding.keyUpScript
        } else {
            binding.script
        }
        run(binding, script, output)
    }

    /** Compile + run [script] for [binding] on a fresh interpreter; park it if it suspends on a wait. */
    private fun run(binding: MacroBinding, script: String, output: OutputSink) {
        if (script.isEmpty()) return  // an unset phase script (e.g. no key-up / else branch) — nothing to run
        try {
            val program = host.compile(script).program
            val interp = Interpreter(program, RuntimeContext(variables, output, input, navigator, client))
            val ticks = interp.run()
            if (ticks >= 0) pending.add(Pending(interp, ticks, output, binding.name, macros.indexOf(binding)))
        } catch (e: Throwable) {
            // A macro fired by the game (key / event / wait-resume) must never let a script error
            // escape into the host's tick callback — that hard-crashes the client. Surface it to
            // the output sink and keep the host loop (and sibling bindings) running.
            reportError(output, e)
        }
    }

    /**
     * Drive every input-bound macro (keyboard key OR mouse button) for one client tick. The host calls this
     * once per tick (guarded by `mc.screen == null` so macros don't fire while a GUI/chat is open, and by
     * [MacroRegistry.hasInputBindings] so an idle config costs nothing). [pressed] reports whether a given
     * trigger's input is currently held -- the host routes a [Trigger.Key] to the keyboard and a
     * [Trigger.Mouse] to the mouse, so this state machine stays input-agnostic; [nowMs] is the wall clock in
     * ms, injected so the KEYSTATE throttle is unit-testable. Per binding it handles the press edge
     * (ONESHOT/CONDITIONAL one-shot, KEYSTATE key-down), the held repeat (KEYSTATE key-held every
     * [MacroBinding.repeatRateMs]), and the release edge (KEYSTATE key-up). [onFire] runs just before any
     * script fires so the host can stamp the trigger into %KEYID% / %KEYNAME%.
     */
    fun tickKeys(nowMs: Long, output: OutputSink, onFire: (Trigger) -> Unit = {}, modifiers: Modifiers = Modifiers.NONE, pressed: (Trigger) -> Boolean) {
        macros.snapshotInto(tickScratch) // snapshot once into a reused buffer — isolates this loop from a
        for (i in tickScratch.indices) { // fired bind/unbind (run() fires synchronously) without per-tick garbage
            val binding = tickScratch[i]
            val t = binding.trigger
            if (binding.enabled && (t is Trigger.Key || t is Trigger.Mouse)) {
                tickBinding(binding, pressed(t), nowMs, modifiers, output, onFire)
            }
        }
    }

    /**
     * Advance one binding's input-state machine for this tick (MKB Macro.play, KEYSTATE branch). [onFire]
     * runs just before any of this binding's scripts fires, so the host can stamp the trigger into
     * %KEYID% / %KEYNAME% for the script to read. [modifiers] gates the PRESS EDGE only — a binding with a
     * modifier requirement activates only if the modifier is held when the key first goes down (MKB checks
     * once, at createInstance); an already-active KEYSTATE binding finishes its held/up cycle even if the
     * modifier is later released, and holding the key and adding a modifier afterwards never back-activates it.
     */
    private fun tickBinding(binding: MacroBinding, isDown: Boolean, nowMs: Long, modifiers: Modifiers, output: OutputSink, onFire: (Trigger) -> Unit) {
        val state = keyStates.getOrPut(binding) { KeyState() }
        fun fire(script: String) { if (script.isNotEmpty()) { onFire(binding.trigger); run(binding, script, output) } }
        if (binding.mode == PlaybackMode.KEYSTATE) {
            if (isDown) {
                if (!state.wasDown) {                                           // press edge: activate iff modifiers satisfied (checked once)
                    state.active = modifiers.satisfies(binding)
                    if (state.active) fire(binding.script)                      // key-down
                }
                if (state.active && nowMs - state.lastTriggerMs > binding.repeatRateMs) {  // key-held while active, throttled
                    state.lastTriggerMs = nowMs
                    fire(binding.keyHeldScript)
                }
                state.wasDown = true
            } else {
                if (state.wasDown && state.active) fire(binding.keyUpScript)    // key-up only if this press had activated
                state.wasDown = false
                state.active = false
                state.lastTriggerMs = 0                                         // re-arm so the next press fires key-held immediately again
            }
        } else {
            // ONESHOT / CONDITIONAL: press edge only, gated on modifiers being satisfied at that edge.
            if (isDown && !state.wasDown && modifiers.satisfies(binding)) { onFire(binding.trigger); firePress(binding, output) }
            state.wasDown = isDown
        }
    }

    /** Route a script failure to the user's HUD/log instead of crashing the client. */
    private fun reportError(output: OutputSink, e: Throwable) {
        output.log("[macro error] " + (e.message ?: e.javaClass.simpleName))
    }

    /**
     * Advance every wait-suspended script by one client tick; resume those whose wait has
     * elapsed (and re-park or drop them depending on whether they wait again / finish).
     * Called once per client tick by the Fabric bridge. No-op when nothing is waiting.
     */
    fun tickWaits() {
        if (pending.isEmpty()) return
        // Iterate a snapshot, not a live iterator: a resumed macro can synchronously send chat,
        // which (via the host's chat sink firing onSendChatMessage) re-enters start() and adds to
        // `pending` mid-iteration — a live iterator throws ConcurrentModificationException. The
        // snapshot processes only macros parked at tick start; anything newly parked resumes next
        // tick. Finished macros are collected and removed after the pass.
        val snapshot = waitScratch         // reused buffer, filled element-wise (addAll would alloc a toArray)
        snapshot.clear()
        for (i in pending.indices) snapshot.add(pending[i])
        var finished: ArrayList<Pending>? = null
        for (i in snapshot.indices) {
            val p = snapshot[i]
            p.elapsedTicks++                   // accrue one client tick of run-time (for MACROTIME)
            if (--p.ticks > 0) continue        // still counting down
            val next = try {
                p.interp.run()                 // delay elapsed -> resume
            } catch (e: Throwable) {
                reportError(p.output, e)
                -1                             // treat a failed resume as finished
            }
            if (next >= 0) p.ticks = next else (finished ?: ArrayList<Pending>().also { finished = it }).add(p)
        }
        finished?.let { done -> pending.removeAll(done.toSet()) }
        snapshot.clear()                       // drop this tick's Pending refs so finished interpreters can be GC'd
    }
}
