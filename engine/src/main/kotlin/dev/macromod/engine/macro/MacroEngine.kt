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
    private class KeyState(var wasDown: Boolean = false, var lastTriggerMs: Long = 0)
    private val keyStates = java.util.IdentityHashMap<MacroBinding, KeyState>()

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
        for (binding in macros.forEvent(name)) run(binding, binding.script, output)
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
            if (ticks >= 0) pending.add(Pending(interp, ticks, output, binding.name, macros.all().indexOf(binding)))
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
    fun tickKeys(nowMs: Long, output: OutputSink, onFire: (Trigger) -> Unit = {}, pressed: (Trigger) -> Boolean) {
        for (binding in macros.all()) {
            val t = binding.trigger
            if (binding.enabled && (t is Trigger.Key || t is Trigger.Mouse)) {
                tickBinding(binding, pressed(t), nowMs, output, onFire)
            }
        }
    }

    /**
     * Advance one binding's input-state machine for this tick (MKB Macro.play, KEYSTATE branch). [onFire]
     * runs just before any of this binding's scripts fires, so the host can stamp the trigger into
     * %KEYID% / %KEYNAME% for the script to read.
     */
    private fun tickBinding(binding: MacroBinding, isDown: Boolean, nowMs: Long, output: OutputSink, onFire: (Trigger) -> Unit) {
        val state = keyStates.getOrPut(binding) { KeyState() }
        fun fire(script: String) { if (script.isNotEmpty()) { onFire(binding.trigger); run(binding, script, output) } }
        if (binding.mode == PlaybackMode.KEYSTATE) {
            if (isDown) {
                if (!state.wasDown) fire(binding.script)                        // key-down on the press edge
                if (nowMs - state.lastTriggerMs > binding.repeatRateMs) {       // key-held, throttled (fires on press: lastTriggerMs starts 0)
                    state.lastTriggerMs = nowMs
                    fire(binding.keyHeldScript)
                }
                state.wasDown = true
            } else {
                if (state.wasDown) fire(binding.keyUpScript)                    // key-up on the release edge
                state.wasDown = false
                state.lastTriggerMs = 0                                         // re-arm so the next press fires key-held immediately again
            }
        } else {
            if (isDown && !state.wasDown) { onFire(binding.trigger); firePress(binding, output) }   // ONESHOT / CONDITIONAL: press edge only
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
        val snapshot = pending.toList()
        var finished: ArrayList<Pending>? = null
        for (p in snapshot) {
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
    }
}
