package dev.macromod.engine.macro

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.Navigator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.runtime.Interpreter
import dev.macromod.engine.runtime.RuntimeContext
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

    /** A macro suspended on a `wait`, with the ticks remaining before it resumes. */
    private class Pending(val interp: Interpreter, var ticks: Int, val output: OutputSink)

    private val pending = ArrayList<Pending>()

    /** How many scripts are currently suspended on a wait (for diagnostics / tests). */
    val pendingWaits: Int get() = pending.size

    /** Run every enabled macro bound to [keyCode]. */
    fun fireKey(keyCode: Int, output: OutputSink) {
        for (binding in macros.forKey(keyCode)) start(binding, output)
    }

    /** Run every enabled macro bound to the named event. */
    fun fireEvent(name: String, output: OutputSink) {
        for (binding in macros.forEvent(name)) start(binding, output)
    }

    /** Start a macro on a fresh interpreter; park it if it suspends on a wait. */
    private fun start(binding: MacroBinding, output: OutputSink) {
        try {
            val program = host.compile(binding.script).program
            val interp = Interpreter(program, RuntimeContext(variables, output, input, navigator, client))
            val ticks = interp.run()
            if (ticks >= 0) pending.add(Pending(interp, ticks, output))
        } catch (e: Throwable) {
            // A macro fired by the game (key / event / wait-resume) must never let a script error
            // escape into the host's tick callback — that hard-crashes the client. Surface it to
            // the output sink and keep the host loop (and sibling bindings) running.
            reportError(output, e)
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
